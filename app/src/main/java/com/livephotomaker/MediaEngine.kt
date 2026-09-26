package com.livephotomaker

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin
import kotlin.random.Random

/**
 * 媒体处理引擎：对标原 Python 脚本（video_转Live图.py）
 *  - 视频：取首帧做封面；用 MediaExtractor + MediaMuxer 流拷贝为 MP4（与 Python 中
 *    ffmpeg -t 3 -c copy 一致），只保留前 3 秒，并从第一个关键帧开始，避免花屏。
 *  - 图片：生成 3 秒「手持漂移感」视频（整幅轻微平移+微旋转，不做变焦放大），封装为 H.264 MP4
 *
 * 色彩关键修正（图片生成视频）：
 *  - 输入给 H.264 编码器的 YUV 必须是 NV12（U 在前、V 在后）。Android 的
 *    COLOR_FormatYUV420Flexible/SemiPlanar 编码器期望的就是 NV12，之前误用 NV21(V,U)
 *    会导致色度整体翻转、颜色发飘。
 *  - 编码器显式声明 BT.601 / 限定范围，和本类生成的 YUV 一致，避免播放端误判色彩矩阵。
 */
class MediaEngine(private val context: Context) {

    /** 解码图片，长边不超过 maxDim，避免内存/编码过大 */
    fun decodeImage(uri: Uri, maxDim: Int): Bitmap? {
        val cr = context.contentResolver
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        val w = opts.outWidth
        val h = opts.outHeight
        if (w <= 0 || h <= 0) return null
        var scale = 1
        val longSide = max(w, h)
        if (longSide > maxDim) {
            scale = longSide / maxDim
            if (longSide % maxDim != 0) scale += 1
        }
        val opts2 = BitmapFactory.Options().apply { inSampleSize = scale }
        return cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts2) }
    }

    fun bitmapToJpeg(bitmap: Bitmap, quality: Int): ByteArray {
        val os = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, os)
        return os.toByteArray()
    }

    /** 从视频提取首帧作为封面 JPEG（质量 92，与原脚本一致） */
    fun extractCoverFromVideo(uri: Uri): ByteArray? {
        return extractCoverFromVideo(uri, 0L)
    }

    /** 从视频指定时间（微秒）提取一帧作为封面 JPEG */
    fun extractCoverFromVideo(uri: Uri, timeUs: Long): ByteArray? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            val bmp = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: return null
            val data = bitmapToJpeg(bmp, 92)
            bmp.recycle()
            data
        } catch (e: Exception) {
            null
        } finally {
            retriever.release()
        }
    }

    /** 读取视频总时长（微秒）；失败返回 null */
    fun getVideoDurationUs(uri: Uri): Long? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            val ms = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            ms?.times(1000)
        } catch (e: Exception) {
            null
        } finally {
            retriever.release()
        }
    }

    /**
     * 把单张图片生成「手持漂移感」视频，封装为 H.264 MP4。
     *
     * 旧版是「以画面正中心等比放大」（smoothstep 1.0 → 1.035），看起来假的原因是：
     *  1) 纯中心缩放会让每个像素沿半径等比例向外扩散，这是典型的"数字变焦"特征；
     *     而真实手持拍的实况图几乎不变焦，是整幅画面在轻微位移；
     *  2) 锚点固定在几何中心，"完美对称"本身就不像自然拍摄。
     *
     * 本版改为模拟手持拍摄：
     *  - 主运动 = 整幅画面的平移 + 微旋转（可选极缓慢的轻微推近），不做等比中心变焦；
     *  - 运动曲线 = 两组不同频率的正弦叠加（有机、非匀速），而非单一缓动曲线；
     *  - 用 sin(πt) 做包络，首帧/末帧回到原始取景 —— 封面与原始图片一致，播放无跳变；
     *  - 每帧按当时位移量动态计算最小安定裁切（overscan），既不出黑边，也把裁切压到最低。
     *
     * 每次调用会从内置的 [MOTION_STYLES] 中**随机挑一种风格**，并随机化运动相位，
     * 因此同一张图重复转换也会得到不同的动效，避免"一看就是同一套模板"的重复感。
     *
     * @param durationSec 动效时长（秒），建议 3.0（与 iPhone 实况图一致）
     * @param fps         帧率，建议 30（运动更细腻，不会一卡一卡）
     * @return 本次实际使用的风格名称（供上层日志展示）
     */
    fun makeHandheldVideo(src: Bitmap, outFile: File, durationSec: Float, fps: Int): String {
        var width = (src.width / 2) * 2
        var height = (src.height / 2) * 2
        if (width <= 0) width = 2
        if (height <= 0) height = 2

        val totalFrames = max(2, (durationSec * fps).toInt())

        // 随机挑一种内置风格；相位也随机化 → 同风格重复生成也不会一模一样
        val style = MOTION_STYLES[Random.nextInt(MOTION_STYLES.size)]
        val phases = floatArrayOf(
            Random.nextFloat() * TAU,
            Random.nextFloat() * TAU,
            Random.nextFloat() * TAU
        )
        val bitrate = (width * height * fps * 0.25).toInt().coerceIn(1_000_000, 8_000_000)
        val colorFormat = pickColorFormat()

        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            setInteger(MediaFormat.KEY_COLOR_FORMAT, colorFormat)
            setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
            setColorStandard(this)
        }

        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        encoder.start()

        val muxer = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var muxerStarted = false
        var trackIndex = -1
        val bufferInfo = MediaCodec.BufferInfo()

        val base = if (width != src.width || height != src.height) {
            Bitmap.createScaledBitmap(src, width, height, true)
        } else src

        val frameDurUs = 1_000_000L / fps
        var frameIndex = 0
        var inputDone = false
        var outputDone = false
        var pts = 0L

        while (!outputDone) {
            if (!inputDone) {
                val inIdx = encoder.dequeueInputBuffer(10_000)
                if (inIdx >= 0) {
                    if (frameIndex < totalFrames) {
                        val motion = handheldMotion(frameIndex, totalFrames, width, height, style, phases)
                        val frame = produceMotionFrame(base, motion, width, height)
                        val nv12 = bitmapToNV12(frame)
                        frame.recycle()
                        val inBuf = encoder.getInputBuffer(inIdx)!!
                        inBuf.clear(); inBuf.put(nv12)
                        encoder.queueInputBuffer(inIdx, 0, nv12.size, pts, 0)
                        pts += frameDurUs
                        frameIndex++
                    } else {
                        encoder.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    }
                }
            }

            val outIdx = encoder.dequeueOutputBuffer(bufferInfo, 10_000)
            when {
                outIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> { /* 继续轮询 */ }
                outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    trackIndex = muxer.addTrack(encoder.outputFormat)
                    muxer.start(); muxerStarted = true
                }
                outIdx >= 0 -> {
                    val outBuf = encoder.getOutputBuffer(outIdx)!!
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && muxerStarted) {
                        muxer.writeSampleData(trackIndex, outBuf, bufferInfo)
                    }
                    encoder.releaseOutputBuffer(outIdx, false)
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                }
            }
        }

        muxer.stop(); muxer.release()
        encoder.stop(); encoder.release()
        if (base != src) base.recycle()
        return style.name
    }

    /** 单帧的手持变换参数 */
    private class MotionFrame(val scale: Float, val dx: Float, val dy: Float, val rotDeg: Float)

    /**
     * 内置手持运动风格。
     *
     * 位移/旋转都由「两组频率不可通约的正弦叠加」生成：
     * 频率不成整数比 ⇒ 运动不会周期性重复，观感更有机、不像机械匀速推拉。
     *
     * @param ampX / ampY 横向 / 纵向位移幅度（占画幅比例，0.025 ≈ 2.5% 画宽）
     * @param ampRot      旋转幅度（度）
     * @param zoomAmp     缓慢推近幅度（0 = 完全不推近）
     * @param fx1/fx2     横向运动的两组频率；wx1 为第一组权重（第二组为 1-wx1）
     * @param fy1/fy2/wy1 纵向运动的频率与权重
     * @param fr1/fr2/wr1 旋转运动的频率与权重
     * @param offY1/offY2 纵向相对横向的相位偏移（频率相同且偏移为 0 ⇒ 斜向同相运动）
     * @param offR1/offR2 旋转相对横向的相位偏移
     * @param fz          推近运动的频率
     */
    private class MotionStyle(
        val name: String,
        val ampX: Float, val ampY: Float, val ampRot: Float, val zoomAmp: Float,
        val fx1: Float, val fx2: Float, val wx1: Float,
        val fy1: Float, val fy2: Float, val wy1: Float,
        val fr1: Float, val fr2: Float, val wr1: Float,
        val offY1: Float, val offY2: Float,
        val offR1: Float, val offR2: Float,
        val fz: Float
    )

    /**
     * 计算第 index 帧的手持变换。
     * 位移/旋转/推近都乘 sin(πt) 包络 → 首末帧回到原始取景（封面与原图一致、播放无跳变）。
     * 裁切量按当帧实际位移实时计算，取"刚好不露黑边"的最小值。
     */
    private fun handheldMotion(
        index: Int, totalFrames: Int, w: Int, h: Int,
        style: MotionStyle, phases: FloatArray
    ): MotionFrame {
        val t = index.toFloat() / (totalFrames - 1).toFloat()
        val env = sin(PI * t)                                   // 0 → 1 → 0

        val dx = style.ampX * w * env *
                wave(t, style.fx1, style.fx2, style.wx1, phases[0], phases[1])
        val dy = style.ampY * h * env *
                wave(t, style.fy1, style.fy2, style.wy1, phases[0] + style.offY1, phases[1] + style.offY2)
        val rotDeg = style.ampRot * env *
                wave(t, style.fr1, style.fr2, style.wr1, phases[0] + style.offR1, phases[1] + style.offR2)

        // 缓慢推近：0 → zoomAmp → 0（同受包络约束，首末帧不推近）
        val zoom = style.zoomAmp * env * (0.5f + 0.5f * sin(TAU * style.fz * t + phases[2]))

        // 旋转会让四角额外位移约 r·θ，把它并入安全边距
        val rotPx = 0.5f * hypot(w.toFloat(), h.toFloat()) * (abs(rotDeg) * PI / 180f)
        val needX = 2f * (abs(dx) + rotPx) / w
        val needY = 2f * (abs(dy) + rotPx) / h
        // 0.4% 兜底防采样边缘露底；推近本身只会裁得更多，不会露黑边
        val scale = (1f + max(needX, needY) + 0.004f) * (1f + zoom)

        return MotionFrame(scale, dx, dy, rotDeg)
    }

    /** 两组"不可通约"频率的正弦叠加：有机、非匀速，避免机械感 */
    private fun wave(t: Float, f1: Float, f2: Float, w1: Float, p1: Float, p2: Float): Float =
        w1 * sin(TAU * f1 * t + p1) + (1f - w1) * sin(TAU * f2 * t + p2)

    /** 绘制一帧：绕画幅中心缩放 → 绕中心旋转 → 平移（post 链顺序即变换施加顺序） */
    private fun produceMotionFrame(src: Bitmap, m: MotionFrame, w: Int, h: Int): Bitmap {
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val matrix = Matrix()
        matrix.setScale(m.scale, m.scale, w / 2f, h / 2f)
        matrix.postRotate(m.rotDeg, w / 2f, h / 2f)
        matrix.postTranslate(m.dx, m.dy)
        canvas.drawBitmap(src, matrix, Paint(Paint.FILTER_BITMAP_FLAG))
        return out
    }

    private companion object {
        private const val PI = 3.14159265f
        private const val TAU = 6.2831855f

        /**
         * 内置的 5 种手持风格。差异体现在四个维度上：
         * **速度**（频率高低）、**方向**（横 / 纵 / 斜向）、**幅度**、**是否带缓慢推近**。
         *
         * 每次生成随机取一种，并额外随机化运动相位，所以同一张图重复转换也不会一模一样。
         */
        private val MOTION_STYLES = listOf(
            // ① 中速随手漂移：横纵都有、旋转轻微 —— 最接近日常随手拍的实况图
            MotionStyle(
                "自然手持", 0.022f, 0.017f, 0.24f, 0.000f,
                0.70f, 1.90f, 0.62f,
                0.55f, 1.60f, 0.65f,
                0.45f, 1.20f, 0.60f,
                1.30f, 2.10f, 0.50f, 1.20f, 0.00f
            ),
            // ② 慢速上下浮沉 + 极缓慢轻微推近 —— 像拍摄者在轻轻呼吸
            MotionStyle(
                "呼吸微推", 0.013f, 0.015f, 0.10f, 0.010f,
                0.40f, 1.10f, 0.55f,
                0.32f, 1.25f, 0.68f,
                0.28f, 0.85f, 0.55f,
                1.10f, 1.90f, 0.70f, 2.40f, 0.50f
            ),
            // ③ 慢速横移为主、纵向很弱 —— 像边走边拍，画面缓缓横向掠过
            MotionStyle(
                "漫步横移", 0.030f, 0.012f, 0.15f, 0.000f,
                0.32f, 1.05f, 0.72f,
                0.26f, 0.90f, 0.60f,
                0.30f, 0.95f, 0.62f,
                1.60f, 2.70f, 0.90f, 1.80f, 0.00f
            ),
            // ④ 频率偏高、旋转偏多 —— 像手不太稳的随手一拍，抖动感明显
            MotionStyle(
                "轻快微抖", 0.020f, 0.017f, 0.32f, 0.000f,
                1.05f, 2.60f, 0.58f,
                0.95f, 2.30f, 0.62f,
                0.80f, 2.10f, 0.66f,
                1.40f, 2.80f, 0.60f, 1.50f, 0.00f
            ),
            // ⑤ 横纵同频同相（相位偏移为 0）—— 画面斜向缓缓摇过
            MotionStyle(
                "斜向摇镜", 0.026f, 0.021f, 0.20f, 0.000f,
                0.45f, 1.35f, 0.78f,
                0.45f, 1.35f, 0.78f,
                0.42f, 1.15f, 0.68f,
                0.00f, 0.00f, 0.55f, 1.40f, 0.00f
            )
        )
    }

    /**
     * Bitmap(ARGB) -> NV12(YUV420SP, U 在前 V 在后) 字节数组。
     * 使用标准 BT.601 限定范围系数，与编码器声明的色彩标准一致。
     */
    private fun bitmapToNV12(bitmap: Bitmap): ByteArray {
        val w = bitmap.width
        val h = bitmap.height
        val argb = IntArray(w * h)
        bitmap.getPixels(argb, 0, w, 0, 0, w, h)
        val ySize = w * h
        val uvSize = w * h / 2
        val yuv = ByteArray(ySize + uvSize)
        var yI = 0
        var uvI = ySize
        for (j in 0 until h) {
            for (i in 0 until w) {
                val p = argb[j * w + i]
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                var y = ((66 * r + 129 * g + 25 * b + 128) shr 8) + 16
                var u = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
                var v = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128
                y = y.coerceIn(0, 255)
                u = u.coerceIn(0, 255)
                v = v.coerceIn(0, 255)
                yuv[yI++] = y.toByte()
                if (j % 2 == 0 && i % 2 == 0) {
                    yuv[uvI++] = u.toByte()   // NV12: U 在前
                    yuv[uvI++] = v.toByte()   //           V 在后
                }
            }
        }
        return yuv
    }

    /** 选择编码器支持的 YUV420 颜色格式（优先 Flexible，其次 SemiPlanar，均为 NV12 布局） */
    private fun pickColorFormat(): Int {
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        val caps = codec.codecInfo.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)
        codec.release()
        for (cf in caps.colorFormats) {
            if (cf == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible) return cf
        }
        for (cf in caps.colorFormats) {
            if (cf == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar) return cf
        }
        return MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar
    }

    /** 在编码器格式上声明 BT.601 限定范围，匹配本类生成的 YUV */
    private fun setColorStandard(format: MediaFormat) {
        try {
            format.setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT601_PAL)
            format.setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
            format.setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
        } catch (_: Exception) { /* 部分设备忽略这些键，不影响编码 */ }
    }

    /** 检测视频文件的视频轨道 MIME 类型（如 video/hevc、video/avc），用于报错提示 */
    fun detectVideoMime(uri: Uri): String? {
        val cr = context.contentResolver
        val pfd = cr.openFileDescriptor(uri, "r") ?: return null
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(pfd.fileDescriptor)
            for (i in 0 until extractor.trackCount) {
                val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)
                if (mime != null && mime.startsWith("video/")) return mime
            }
        } catch (_: Exception) {
        } finally {
            extractor.release()
            pfd.close()
        }
        return null
    }

    /**
     * 把任意 Android 可解封装的视频（H.264 / HEVC / 其他）流拷贝为 H.264/HEVC MP4。
     * 与原 Python 脚本 `ffmpeg -t 3 -c copy` 一致：不解码、不重编码，只截取前 3 秒。
     * 从第一个关键帧（I 帧）开始写，避免开头花屏。
     * 返回实际视频时长（微秒）；失败返回 null。
     */
    fun transmuxVideoToMp4(uri: Uri, maxDurationUs: Long, outFile: File): Long? {
        val cr = context.contentResolver
        val pfd = cr.openFileDescriptor(uri, "r") ?: return null
        val result = doTransmux(pfd, maxDurationUs, outFile, startFromKeyframe = true)
        if (result != null) return result
        pfd.close()

        val pfd2 = cr.openFileDescriptor(uri, "r") ?: return null
        return try {
            doTransmux(pfd2, maxDurationUs, outFile, startFromKeyframe = false)
        } finally {
            pfd2.close()
        }
    }

    private fun doTransmux(
        pfd: ParcelFileDescriptor,
        maxDurationUs: Long,
        outFile: File,
        startFromKeyframe: Boolean
    ): Long? {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(pfd.fileDescriptor)
        } catch (e: Exception) {
            extractor.release()
            return null
        }

        var videoTrack = -1
        var videoFormat: MediaFormat? = null
        for (i in 0 until extractor.trackCount) {
            val f = extractor.getTrackFormat(i)
            val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("video/")) {
                videoTrack = i
                videoFormat = f
                break
            }
        }
        if (videoTrack < 0 || videoFormat == null) {
            extractor.release()
            return null
        }

        val rotation = if (videoFormat.containsKey(MediaFormat.KEY_ROTATION))
            (videoFormat.getInteger(MediaFormat.KEY_ROTATION) + 360) % 360 else 0

        extractor.selectTrack(videoTrack)

        var firstPts: Long? = null
        if (startFromKeyframe) {
            while (true) {
                val pts = extractor.sampleTime
                if (pts < 0) break
                if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) {
                    firstPts = pts
                    break
                }
                if (!extractor.advance()) break
            }
            if (firstPts == null) {
                extractor.release()
                return null
            }
        } else {
            firstPts = if (extractor.sampleTime >= 0) extractor.sampleTime else 0L
        }

        val muxer = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        muxer.setOrientationHint(rotation)
        val trackIndex = muxer.addTrack(videoFormat)
        muxer.start()

        var buffer = ByteBuffer.allocate(8 * 1024 * 1024) // 8 MB，足够手机视频单帧
        var samplesWritten = 0
        var lastPts = 0L

        while (true) {
            val pts = extractor.sampleTime
            if (pts < 0) break
            if (pts - firstPts > maxDurationUs) break

            // 保证 buffer 足够（API 28+ 可直接取 sampleSize）
            val needed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                extractor.sampleSize.toInt()
            } else 0
            if (needed > buffer.capacity()) {
                buffer = ByteBuffer.allocate(needed)
            }

            buffer.clear()
            val size = extractor.readSampleData(buffer, 0)
            if (size < 0) break

            buffer.position(0)
            buffer.limit(size)

            val flags = extractor.sampleFlags
            val outPts = pts - firstPts
            val info = MediaCodec.BufferInfo().apply {
                set(0, size, outPts, flags)
            }
            muxer.writeSampleData(trackIndex, buffer, info)

            lastPts = outPts
            samplesWritten++

            if (!extractor.advance()) break
        }

        muxer.stop()
        muxer.release()
        extractor.release()

        return if (samplesWritten > 0) lastPts else null
    }

    /**
     * 截取视频中 [startUs, startUs+durationUs) 这一段，封装为 MP4（流拷贝、不重编码）。
     * 先定位到 startUs 之前（或之上）最近的关键帧再开始写，保证开头是关键帧、不乱码。
     * 返回该段实际时长（微秒，相对 0 起算）；失败返回 null。
     */
    fun transmuxVideoSegment(uri: Uri, startUs: Long, durationUs: Long, outFile: File): Long? {
        val cr = context.contentResolver
        val pfd = cr.openFileDescriptor(uri, "r") ?: return null
        return try {
            doTransmuxRange(pfd, startUs, durationUs, outFile)
        } finally {
            pfd.close()
        }
    }

    private fun doTransmuxRange(
        pfd: ParcelFileDescriptor,
        startUs: Long,
        durationUs: Long,
        outFile: File
    ): Long? {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(pfd.fileDescriptor)
        } catch (e: Exception) {
            extractor.release()
            return null
        }

        var videoTrack = -1
        var videoFormat: MediaFormat? = null
        for (i in 0 until extractor.trackCount) {
            val f = extractor.getTrackFormat(i)
            val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("video/")) {
                videoTrack = i
                videoFormat = f
                break
            }
        }
        if (videoTrack < 0 || videoFormat == null) {
            extractor.release()
            return null
        }

        val rotation = if (videoFormat.containsKey(MediaFormat.KEY_ROTATION))
            (videoFormat.getInteger(MediaFormat.KEY_ROTATION) + 360) % 360 else 0

        extractor.selectTrack(videoTrack)
        // 定位到 startUs 之前（或之上）最近的关键帧，作为本段的起点
        extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        val firstPts = extractor.sampleTime
        if (firstPts < 0) {
            extractor.release()
            return null
        }

        val muxer = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        muxer.setOrientationHint(rotation)
        val trackIndex = muxer.addTrack(videoFormat)
        muxer.start()

        var buffer = ByteBuffer.allocate(8 * 1024 * 1024)
        var samplesWritten = 0
        var lastPts = 0L
        // durationUs <= 0 表示“截到视频末尾，不做长度限制”（用于整段直接转换）
        val endUs = if (durationUs > 0) startUs + durationUs else Long.MAX_VALUE

        while (true) {
            val pts = extractor.sampleTime
            if (pts < 0) break
            if (pts > endUs) break

            val needed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                extractor.sampleSize.toInt()
            } else 0
            if (needed > buffer.capacity()) {
                buffer = ByteBuffer.allocate(needed)
            }

            buffer.clear()
            val size = extractor.readSampleData(buffer, 0)
            if (size < 0) break

            buffer.position(0)
            buffer.limit(size)

            val flags = extractor.sampleFlags
            val outPts = pts - firstPts
            val info = MediaCodec.BufferInfo().apply {
                set(0, size, outPts, flags)
            }
            muxer.writeSampleData(trackIndex, buffer, info)

            lastPts = outPts
            samplesWritten++

            if (!extractor.advance()) break
        }

        muxer.stop()
        muxer.release()
        extractor.release()

        return if (samplesWritten > 0) lastPts else null
    }
}
