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
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.random.Random

/**
 * 媒体处理引擎：对标原 Python 脚本（video_转Live图.py）
 *  - 视频：取首帧做封面；用 MediaExtractor + MediaMuxer 流拷贝为 MP4（与 Python 中
 *    ffmpeg -t 3 -c copy 一致），只保留前 3 秒，并从第一个关键帧开始，避免花屏。
 *  - 图片：生成 3 秒「真实手持晃动」视频（针孔相机三轴微转的透视变换 + 恒定裁切），封装为 H.264 MP4
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
     * 把单张图片生成「真实手持晃动」视频，封装为 H.264 MP4。
     *
     * 前两版为什么看着假：
     *  1) 第一版是「绕画面中心等比放大」（smoothstep 1.0 → 1.035），这是最典型的数字变焦特征；
     *  2) 第二版改成整幅平移 + 微旋转，但为了不出黑边，**裁切量按当帧位移实时计算** ——
     *     位移越大裁得越多，于是整幅画面在 3 秒里被动地"放大 → 复原"，
     *     叠加在真实运动之上，观感依然是"做了一个放大的效果"。
     *
     * 本版两个关键改动：
     *  1) **恒定裁切**：先扫一遍整段运动，取全程所需的**最大** overscan，整段视频共用同一个值。
     *     帧与帧之间不再有任何缩放变化 —— 画面不会呼吸、不会推近拉远。
     *  2) **用透视旋转取代平移**：真手持的画面位移绝大部分来自相机绕三轴的转动，
     *     而不是整张照片在平面上"滑动"。绕轴转动会产生近大远小的梯形畸变，
     *     这正是人眼判断"这是真的在拍"的核心线索。这里按针孔相机模型做单应变换
     *     （[CAMERA_DISTANCE] 取 1.22 倍半宽 ≈ 等效 21mm 广角，与手机主摄一致），
     *     三轴角度都在 1° 上下；平移只保留 0.1% 画幅作为补充。
     *  3) 运动曲线 = 低频趋势（两组不可通约频率正弦叠加）+ 3~6Hz 高频手抖，
     *     再乘 sin(πt) 包络 → 首末帧回到原始构图，播放不跳变、封面与图片一致。
     *
     * 每次调用会从内置的 [MOTION_STYLES] 中**随机挑一种风格**，并随机化运动相位，
     * 因此同一张图重复转换也会得到不同的动效，避免"一看就是同一套模板"的重复感。
     *
     * 注意：视频第一帧是"中心裁切 [HandheldResult.margin] 分之一"的画面，
     * 所以**封面必须用 [cropCover] 做同样的裁切**，否则相册里静图是完整原图、
     * 一播放画面就胀大，这个跳变本身就会被看成"放大"。
     *
     * @param durationSec 动效时长（秒），建议 3.0（与 iPhone 实况图一致）
     * @param fps         帧率，建议 30（运动更细腻，不会一卡一卡）
     * @param intensity   动效强度倍数：0.6 轻柔（最接近真实实况图）/ 1.0 标准 / 1.5 明显
     * @return 本次实际使用的风格名称与恒定裁切系数
     */
    fun makeHandheldVideo(
        src: Bitmap, outFile: File, durationSec: Float, fps: Int, intensity: Float
    ): HandheldResult {
        var width = (src.width / 2) * 2
        var height = (src.height / 2) * 2
        if (width <= 0) width = 2
        if (height <= 0) height = 2

        val totalFrames = max(2, (durationSec * fps).toInt())

        // 随机挑一种内置风格；相位也随机化 → 同风格重复生成也不会一模一样
        val style = MOTION_STYLES[Random.nextInt(MOTION_STYLES.size)]
        val phases = FloatArray(6) { Random.nextFloat() * TAU }
        // 恒定裁切：先扫一遍整段运动，取"刚好不露黑边"的最大值，整段视频共用。
        // 这一步是消除"看着像放大"的关键 —— 帧与帧之间不再存在任何缩放变化。
        val margin = requiredMargin(width, height, totalFrames, style, phases, intensity)
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
        var coverJpeg: ByteArray? = null

        while (!outputDone) {
            if (!inputDone) {
                val inIdx = encoder.dequeueInputBuffer(10_000)
                if (inIdx >= 0) {
                    if (frameIndex < totalFrames) {
                        val motion = handheldMotion(frameIndex, totalFrames, style, phases, intensity)
                        val frame = produceMotionFrame(base, motion, margin)
                        if (frameIndex == 0) coverJpeg = bitmapToJpeg(frame, 92)
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
        // 取兜底封面必须在 recycle(base) 之前，否则会读到已回收的 Bitmap
        val result = HandheldResult(style.name, margin, coverJpeg ?: bitmapToJpeg(base, 92))
        if (base != src) base.recycle()
        return result
    }

    /**
     * [makeHandheldVideo] 的返回值。
     *
     * [coverJpeg] 就是**视频第一帧本身**编码而来 —— 之所以不另外裁切原图当封面，
     * 是因为两条重采样路径之间会有 1 像素级的网格差；直接用首帧才能保证
     * "相册里看到的静图"和"播放出来的第一帧"严丝合缝，播放瞬间不会有任何缩放跳变。
     */
    class HandheldResult(
        val styleName: String, val margin: Float, val coverJpeg: ByteArray
    )

    /**
     * 单帧的相机姿态。
     * 三个角度都是"相机的转动"，画面位移是转动带来的透视偏移；
     * [panX]/[panY] 只是极小的补充平移（占画幅比例），用来交代手臂的轻微摆动。
     */
    private class MotionFrame(
        val yawDeg: Float, val pitchDeg: Float, val rollDeg: Float,
        val panX: Float, val panY: Float
    )

    /**
     * 内置手持运动风格。
     *
     * 每个轴都由「两组频率不可通约的正弦叠加」驱动（频率不成整数比 ⇒ 运动不会周期性重复），
     * 再叠加一路 3~6Hz 的高频微颤 —— 那是人手的生理性抖动，也是"真的在手持拍摄"的签名。
     *
     * @param fYaw1/fYaw2  横向转头（绕纵轴）的两组频率，单位 = 整段时长内的周期数
     * @param fPit1/fPit2  上下点头（绕横轴）的两组频率
     * @param fRol1/fRol2  画面自转（绕光轴）的两组频率
     * @param wYaw/wPitch/wRoll 三轴各自的幅度权重（1.0 = 基准幅度）
     * @param fTremor      高频手抖的频率（周期数 / 整段时长）
     * @param wTremor      高频手抖相对该风格低频幅度的比例
     * @param phaseOffset  俯仰相对偏航的相位偏移；取 0 时两轴同相 ⇒ 画面沿斜向摇过
     */
    private class MotionStyle(
        val name: String,
        val fYaw1: Float, val fYaw2: Float,
        val fPit1: Float, val fPit2: Float,
        val fRol1: Float, val fRol2: Float,
        val wYaw: Float, val wPitch: Float, val wRoll: Float,
        val fTremor: Float, val wTremor: Float,
        val phaseOffset: Float
    )

    /**
     * 计算第 index 帧的相机姿态。
     * 所有分量都乘 sin(πt) 包络 → 首帧、末帧都回到原始构图
     * （封面与图片完全一致，循环播放也不会跳变）。
     */
    private fun handheldMotion(
        index: Int, totalFrames: Int,
        style: MotionStyle, phases: FloatArray, intensity: Float
    ): MotionFrame {
        val t = index.toFloat() / (totalFrames - 1).toFloat()
        val env = sin(PI * t)                                   // 0 → 1 → 0

        val p1 = phases[0]
        val p2 = phases[1]

        // 低频趋势：两组不可通约频率叠加，运动有快有慢、不周期重复
        val yawLow = drift(t, style.fYaw1, style.fYaw2, 0.62f, p1, p2 + 1.7f)
        val pitLow = drift(
            t, style.fPit1, style.fPit2, 0.58f,
            p1 + style.phaseOffset, p2 + style.phaseOffset * 1.6f
        )
        val rolLow = drift(t, style.fRol1, style.fRol2, 0.60f, p1 + 2.3f, p2 + 0.8f)

        // 高频手抖：三轴各给不同相位，避免出现"整幅一起抖"的机械感
        val tr = style.wTremor
        val yawHi = sin(TAU * style.fTremor * t + phases[2])
        val pitHi = sin(TAU * style.fTremor * 1.21f * t + phases[3])
        val rolHi = sin(TAU * style.fTremor * 0.83f * t + phases[4])

        val k = intensity * env
        val yaw = BASE_YAW_DEG * style.wYaw * k * (yawLow + tr * yawHi)
        val pitch = BASE_PITCH_DEG * style.wPitch * k * (pitLow + tr * pitHi)
        val roll = BASE_ROLL_DEG * style.wRoll * k * (rolLow + tr * rolHi)

        // 平移只作极小补充：真实手持里画面位移绝大部分来自转动，而不是整张照片在滑动
        val panX = BASE_PAN_X * k * drift(t, style.fYaw1, style.fYaw2, 0.62f, p1 + 1.1f, p2 + 2.9f)
        val panY = BASE_PAN_Y * k * drift(t, style.fPit1, style.fPit2, 0.58f, p1 + 0.4f, p2 + 1.4f)

        return MotionFrame(yaw, pitch, roll, panX, panY)
    }

    /** 两组"不可通约"频率的正弦叠加：运动有机、不周期性重复，避免机械感 */
    private fun drift(t: Float, f1: Float, f2: Float, w1: Float, p1: Float, p2: Float): Float =
        w1 * sin(TAU * f1 * t + p1) + (1f - w1) * sin(TAU * f2 * t + p2)

    /**
     * 求整段视频所需的**恒定**裁切系数（overscan）。
     *
     * 做法：逐帧把画布四个角**反投影**回图像平面，看它落在哪里；
     * 要让这个角在变换后仍有像素覆盖，源图就必须比画布大出相应倍数。
     * 取全程最大值 + 0.4% 采样余量 —— 全程共用一个值，因此画面不会随运动缩放。
     */
    private fun requiredMargin(
        w: Int, h: Int, totalFrames: Int,
        style: MotionStyle, phases: FloatArray, intensity: Float
    ): Float {
        val halfW = w / 2f
        val aspect = h.toFloat() / w.toFloat()
        val cornerX = floatArrayOf(0f, w.toFloat(), w.toFloat(), 0f)
        val cornerY = floatArrayOf(0f, 0f, h.toFloat(), h.toFloat())

        var need = 1.0f
        for (i in 0 until totalFrames) {
            val m = handheldMotion(i, totalFrames, style, phases, intensity)
            val r = rotationMatrix(m.yawDeg, m.pitchDeg, m.rollDeg)
            for (c in 0 until 4) {
                // 先在像素空间扣掉平移，再换算成以"半宽 = 1"为单位的平面坐标
                val u = (cornerX[c] - m.panX * w) / halfW - 1f
                val v = (cornerY[c] - m.panY * h) / halfW - aspect
                val p = unproject(u, v, r)
                need = max(need, max(abs(p[0]), abs(p[1]) * w / h))
            }
        }
        return need * 1.004f
    }

    /**
     * 相机姿态矩阵（行主序 3×3）：R = Rz(roll) · Ry(yaw) · Rx(pitch)
     * 图像平面在 z = 0，相机位于 (0, 0, +d) 朝 -z 看（见 [project] / [unproject]）。
     */
    private fun rotationMatrix(yawDeg: Float, pitchDeg: Float, rollDeg: Float): FloatArray {
        val yaw = yawDeg * PI / 180f
        val pitch = pitchDeg * PI / 180f
        val roll = rollDeg * PI / 180f
        val cy = cos(yaw); val sy = sin(yaw)
        val cp = cos(pitch); val sp = sin(pitch)
        val cr = cos(roll); val sr = sin(roll)
        return floatArrayOf(
            cr * cy, cr * sy * sp - sr * cp, cr * sy * cp + sr * sp,
            sr * cy, sr * sy * sp + cr * cp, sr * sy * cp - cr * sp,
            -sy, cy * sp, cy * cp
        )
    }

    /**
     * 针孔相机投影：图像平面上以「半宽 = 1」为单位的点 (x, y) → 归一化成像坐标 (u, v)。
     * 相机在 z = +d 处绕原点转动 R，故相机空间向量 V' = R·(x, y, -d)，
     * u = d·V'x / (-V'z)，v = d·V'y / (-V'z)。R 为单位矩阵时 u = x、v = y（无畸变）。
     */
    private fun project(x: Float, y: Float, r: FloatArray): FloatArray {
        val d = CAMERA_DISTANCE
        val vx = r[0] * x + r[1] * y - r[2] * d
        val vy = r[3] * x + r[4] * y - r[5] * d
        val vz = r[6] * x + r[7] * y - r[8] * d
        val denom = -vz
        if (abs(denom) < 1e-4f) return floatArrayOf(x, y)
        return floatArrayOf(d * vx / denom, d * vy / denom)
    }

    /** [project] 的逆运算：由归一化成像坐标 (u, v) 解出图像平面上的点 (x, y) */
    private fun unproject(u: Float, v: Float, r: FloatArray): FloatArray {
        val d = CAMERA_DISTANCE
        val a11 = d * r[0] + u * r[6]
        val a12 = d * r[1] + u * r[7]
        val b1 = d * d * r[2] + u * d * r[8]
        val a21 = d * r[3] + v * r[6]
        val a22 = d * r[4] + v * r[7]
        val b2 = d * d * r[5] + v * d * r[8]
        val det = a11 * a22 - a12 * a21
        if (abs(det) < 1e-6f) return floatArrayOf(0f, 0f)
        return floatArrayOf(
            (b1 * a22 - b2 * a12) / det,
            (a11 * b2 - a21 * b1) / det
        )
    }

    /**
     * 绘制一帧：把原图中心 [margin] 分之一区域的四角，用单应变换映射到"相机转过三轴"后
     * 的投影位置，再整体平移 [MotionFrame.panX]/[MotionFrame.panY]。
     * 因为是透视变换而非仿射变换，画面会自然出现近大远小的梯形畸变 —— 真实手持的关键特征。
     */
    private fun produceMotionFrame(src: Bitmap, m: MotionFrame, margin: Float): Bitmap {
        val w = src.width
        val h = src.height
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)

        val halfW = w / 2f
        val aspect = h.toFloat() / w.toFloat()
        val half = 1f / margin                  // 源矩形半宽（单位：半宽 = 1）
        val sx = halfW * half                   // 源矩形半宽（像素）
        val sy = h / 2f * half                  // 源矩形半高（像素）

        // 源四角：左上、右上、右下、左下
        val srcPts = floatArrayOf(
            halfW - sx, h / 2f - sy,
            halfW + sx, h / 2f - sy,
            halfW + sx, h / 2f + sy,
            halfW - sx, h / 2f + sy
        )

        val r = rotationMatrix(m.yawDeg, m.pitchDeg, m.rollDeg)
        val srcX = floatArrayOf(-half, half, half, -half)
        val srcY = floatArrayOf(-aspect * half, -aspect * half, aspect * half, aspect * half)
        val dstPts = FloatArray(8)
        for (i in 0 until 4) {
            val p = project(srcX[i], srcY[i], r)
            dstPts[i * 2] = p[0] * halfW + halfW + m.panX * w
            dstPts[i * 2 + 1] = p[1] * halfW + h / 2f + m.panY * h
        }

        val matrix = Matrix()
        matrix.setPolyToPoly(srcPts, 0, dstPts, 0, 4)
        canvas.save()
        canvas.concat(matrix)
        canvas.drawBitmap(src, 0f, 0f, FILTER_PAINT)
        canvas.restore()
        return out
    }

    private companion object {
        private const val PI = 3.14159265f
        private const val TAU = 6.2831855f

        /**
         * 基准幅度（标准强度下的峰值，单位：度）。
         * 真手持拍 3 秒，三轴转动峰值通常就在 1° 上下 —— 超过 2° 就开始像"刻意摇晃"了。
         */
        private const val BASE_YAW_DEG = 1.60f
        private const val BASE_PITCH_DEG = 1.05f
        private const val BASE_ROLL_DEG = 1.30f

        /** 补充平移（占画幅比例，0.0010 = 0.1% 画宽）：只用来交代手臂摆动，绝不能大 */
        private const val BASE_PAN_X = 0.0010f
        private const val BASE_PAN_Y = 0.0008f

        /**
         * 相机到图像平面的距离，以画幅半宽为单位。
         * 1.22 ⇒ 水平视场角约 2·atan(1/1.22) ≈ 78°，等效全画幅焦距约 21mm，
         * 与手机主摄（等效 23~26mm）基本一致 —— 所以透视强度就是"手机拍出来的"那种感觉。
         */
        private const val CAMERA_DISTANCE = 1.22f

        private val FILTER_PAINT = Paint(Paint.FILTER_BITMAP_FLAG)

        /**
         * 内置的 5 种手持风格。差异体现在三个维度上：
         * **节奏**（频率高低）、**方向**（横向转头 / 纵向点头 / 自转的权重）、**抖动**（高频手抖占比）。
         *
         * 每次生成随机取一种，并额外随机化运动相位，所以同一张图重复转换也不会一模一样。
         */
        private val MOTION_STYLES = listOf(
            // ① 自然手持：三轴均衡、中速 —— 最通用，随手拍的实况图基本就是这个样子
            MotionStyle(
                "自然手持",
                0.62f, 1.48f, 0.48f, 1.27f, 0.55f, 1.35f,
                1.00f, 0.85f, 0.90f, 4.6f, 0.30f, 1.05f
            ),
            // ② 轻微呼吸：慢、幅度小，几乎只有纵轴在缓缓浮沉
            MotionStyle(
                "轻微呼吸",
                0.32f, 0.83f, 0.28f, 0.95f, 0.22f, 0.71f,
                0.70f, 1.00f, 0.55f, 3.6f, 0.22f, 1.20f
            ),
            // ③ 边走边拍：以横向转头为主、节奏偏慢，画面像被缓缓掠过
            MotionStyle(
                "边走边拍",
                0.38f, 1.02f, 0.30f, 0.88f, 0.42f, 1.15f,
                1.15f, 0.55f, 1.00f, 5.2f, 0.26f, 1.35f
            ),
            // ④ 手不太稳：频率偏高、自转更多，抖动感最明显的一档
            MotionStyle(
                "手不太稳",
                0.95f, 2.35f, 0.85f, 2.05f, 0.78f, 1.95f,
                0.90f, 0.80f, 1.10f, 6.5f, 0.42f, 0.95f
            ),
            // ⑤ 斜向慢摇：俯仰与偏航同频同相（相位偏移 = 0）⇒ 画面沿斜向缓缓摇过
            MotionStyle(
                "斜向慢摇",
                0.45f, 1.25f, 0.45f, 1.25f, 0.38f, 1.08f,
                0.90f, 0.90f, 0.75f, 4.2f, 0.28f, 0.00f
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
