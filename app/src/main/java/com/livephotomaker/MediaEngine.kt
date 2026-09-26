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
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/**
 * 媒体处理引擎：对标原 Python 脚本（video_转Live图.py）
 *  - 视频：取首帧做封面；用 MediaExtractor + MediaMuxer 流拷贝为 MP4（与 Python 中
 *    ffmpeg -t 3 -c copy 一致），只保留前 3 秒，并从第一个关键帧开始，避免花屏。
 *  - 图片：生成 3 秒「真实手持晃动」视频（恒定裁切 + 纯二维仿射漂移，数学上保证零黑边），
 *    封装为 H.264 MP4
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
     * 前三版分别踩了三个坑，这一版是它们的修正结果：
     *  1) 第一版「绕画面中心等比放大」（1.0 → 1.035）—— 最典型的数字变焦特征，一眼假；
     *  2) 第二版改成平移 + 微旋转，但裁切量**按当帧位移实时计算** ——
     *     位移越大裁得越多，画面在 3 秒里被动地"放大 → 复原"，观感还是放大；
     *  3) 第三版改成恒定裁切 + 相机三轴透视，但渲染的映射方向写反了
     *     （把"源图矩形"投到"画布"，而不是把"画布四角"反投到"源图"）——
     *     投影本身带整体偏移，源图边缘投影后盖不满画布，播放时四边会露黑边。
     *
     * 本版模型：**恒定裁切 + 纯二维手持漂移**。
     *  1) **恒定裁切**：先扫一遍整段运动，取全程所需的**最大** overscan，整段共用一个值。
     *     帧与帧之间零缩放变化 —— 画面不会呼吸、不会推近拉远，这是"不像放大"的根本。
     *  2) **只用仿射变换**（缩放 + 旋转 + 平移），不做透视投影：平行线保持平行，
     *     画面不会梯形畸变，也就不会被看成"某一边被拉大 / 画面在变形"。
     *     真手持的位移一阶近似就是整体平移，2% 画幅以内时与透视的差别肉眼不可辨。
     *  3) **零黑边由数学保证**：[requiredMargin] 与 [produceMotionFrame] 严格互逆，
     *     margin 就是按"让画布四角都落在源图范围内"反解出来的，并留 0.4% 采样余量。
     *     自转不改变源图到画布的距离，所以旋转不会额外增加裁切需求 ——
     *     代价只由位移幅度唯一决定，因此裁切可以压到 3% 左右（上一版是 8.6%）。
     *  4) 运动曲线 = 低频趋势（两组不可通约频率正弦叠加）+ 3~6Hz 高频手抖，
     *     再乘 sin(πt)^0.55 包络 → 首末帧精确回到原始构图，中段位移饱满。
     *  5) 封面直接用视频第一帧，两者严丝合缝，播放瞬间没有任何跳变。
     *
     * 每次调用会从内置的 [MOTION_STYLES] 中**随机挑一种风格**，并随机化运动相位，
     * 因此同一张图重复转换也会得到不同的动效，避免"一看就是同一套模板"的重复感。
     *
     * @param durationSec 动效时长（秒），建议 3.0（与 iPhone 实况图一致）
     * @param fps         帧率，建议 30（运动更细腻，不会一卡一卡）
     * @param intensity   动效强度倍数：0.6 轻柔（最接近真实实况图）/ 1.0 标准 / 1.5 明显
     * @return 本次实际使用的风格名称、恒定裁切系数与首帧封面
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
     * 单帧的二维运动量。
     *
     * 位移用「画幅比例」表示（0.01 = 画幅的 1%），与像素尺寸无关 ——
     * 这样同一套参数在任意分辨率的图片上得到完全相同的视觉幅度。
     */
    private class MotionFrame(
        val dxRatio: Float, val dyRatio: Float, val rotDeg: Float
    )

    /**
     * 内置手持运动风格。
     *
     * 三个自由度（横向位移、纵向位移、画面自转）各自由「两组频率不可通约的正弦叠加」驱动：
     * 频率不成整数比 ⇒ 运动不会周期性重复，观感有机、不像机械匀速推拉；
     * 再叠加一路 3~6Hz 的高频微颤 —— 那是人手的生理性抖动，也是"真的在手持拍摄"的签名。
     *
     * @param fX1/fX2      横向位移的两组频率（单位 = 整段时长内的周期数）
     * @param fY1/fY2      纵向位移的两组频率
     * @param fR1/fR2      画面自转的两组频率
     * @param wX/wY/wR     三个自由度各自的幅度权重（1.0 = 基准幅度）
     * @param fTremor      高频手抖频率（周期数 / 整段时长）
     * @param wTremor      高频手抖相对低频幅度的比例
     * @param phaseY       纵向相对横向的相位偏移
     * @param phaseR       自转相对横向的相位偏移；phaseY 取 0 时横纵同相 ⇒ 画面沿斜向平移
     */
    private class MotionStyle(
        val name: String,
        val fX1: Float, val fX2: Float,
        val fY1: Float, val fY2: Float,
        val fR1: Float, val fR2: Float,
        val wX: Float, val wY: Float, val wR: Float,
        val fTremor: Float, val wTremor: Float,
        val phaseY: Float, val phaseR: Float
    )

    /**
     * 计算第 index 帧的二维运动量。
     *
     * 所有分量都乘 sin(πt) 包络 → 首帧、末帧都回到原始构图
     * （封面与图片完全一致，循环播放也不会跳变）。
     *
     * 包络取 0.55 次幂而不是纯 sin：纯 sin 在首尾各 1/4 段几乎不动，
     * 3 秒里只有中间 1.5 秒看得出运动；开方后包络更"方"，全程都有可观位移，
     * 而首末帧仍然精确归零。
     */
    private fun handheldMotion(
        index: Int, totalFrames: Int,
        style: MotionStyle, phases: FloatArray, intensity: Float
    ): MotionFrame {
        // 首末帧直接给 0：sin(π) 在 Float 下只有 ~4.6e-8 的残留，开方放大后仍会留下
        // 千分之几的微小位移，这里显式归零，保证封面与原始图片严格一致。
        if (index == 0 || index == totalFrames - 1) return MotionFrame(0f, 0f, 0f)
        val t = index.toFloat() / (totalFrames - 1).toFloat()
        val env = pow(sin(PI * t), ENV_POWER)                   // 0 → 1 → 0，中段更饱满

        val p1 = phases[0]
        val p2 = phases[1]

        // 低频趋势：两组不可通约频率叠加，运动有快有慢、不周期重复
        val xLow = drift(t, style.fX1, style.fX2, 0.62f, p1, p2 + 1.7f)
        val yLow = drift(
            t, style.fY1, style.fY2, 0.58f,
            p1 + style.phaseY, p2 + style.phaseY * 1.6f
        )
        val rLow = drift(t, style.fR1, style.fR2, 0.60f, p1 + style.phaseR, p2 + 0.8f)

        // 高频手抖：三路各给不同相位，避免出现"整幅一起抖"的机械感
        val tr = style.wTremor
        val xHi = sin(TAU * style.fTremor * t + phases[2])
        val yHi = sin(TAU * style.fTremor * 1.21f * t + phases[3])
        val rHi = sin(TAU * style.fTremor * 0.83f * t + phases[4])

        val k = intensity * env
        return MotionFrame(
            BASE_DX * style.wX * k * (xLow + tr * xHi),
            BASE_DY * style.wY * k * (yLow + tr * yHi),
            BASE_ROT * style.wR * k * (rLow + tr * rHi)
        )
    }

    /** 两组"不可通约"频率的正弦叠加：运动有机、不周期性重复，避免机械感 */
    private fun drift(t: Float, f1: Float, f2: Float, w1: Float, p1: Float, p2: Float): Float =
        w1 * sin(TAU * f1 * t + p1) + (1f - w1) * sin(TAU * f2 * t + p2)

    /**
     * 求整段视频所需的**恒定**裁切系数（overscan）。
     *
     * 渲染式是 `画布点 c = margin · R(θ) · p + d`（p 为源图上相对中心的像素坐标），
     * 反解得 `p = R(-θ)·(c - d) / margin`。要让画布每一个像素都能取到源图内容，
     * 就必须保证这个 p 落在 `[-w/2, w/2] × [-h/2, h/2]` 之内。
     *
     * 于是逐帧对画布四角求 p，取全程最大半径 + 采样余量，整段视频共用这一个值 ——
     * 帧与帧之间不存在任何缩放变化，所以画面不会有"呼吸 / 推近"的观感。
     *
     * 取 `max(|px|/(w/2), |py|/(h/2))` 而不是两个方向各自算，是为了让裁切各向同性，
     * 否则画面会被拉扁。
     *
     * 注意：自转会改变 p 的方向但**不改变它的模长**，所以旋转本身不额外增加裁切需求 ——
     * 代价只由位移幅度唯一决定，这也是这套模型能压到 3% 左右裁切的原因。
     */
    private fun requiredMargin(
        w: Int, h: Int, totalFrames: Int,
        style: MotionStyle, phases: FloatArray, intensity: Float
    ): Float {
        val halfW = w / 2f
        val halfH = h / 2f
        val cx = floatArrayOf(-halfW, halfW, halfW, -halfW)
        val cy = floatArrayOf(-halfH, -halfH, halfH, halfH)

        var need = 1.0f
        for (i in 0 until totalFrames) {
            val m = handheldMotion(i, totalFrames, style, phases, intensity)
            val dx = m.dxRatio * w
            val dy = m.dyRatio * h
            val rad = m.rotDeg * PI / 180f
            val cr = cos(rad)
            val sr = sin(rad)
            for (k in 0 until 4) {
                // q = R(-θ)·(c - d)：把画布角逆旋转回"未自转"的坐标系
                val vx = cx[k] - dx
                val vy = cy[k] - dy
                val qx = vx * cr + vy * sr
                val qy = -vx * sr + vy * cr
                need = max(need, max(abs(qx) / halfW, abs(qy) / halfH))
            }
        }
        return need * (1f + MARGIN_SAFETY)
    }

    /**
     * 绘制一帧：把源图放大 [margin] 倍（等价于"取源图中心 1/margin 的区域铺满画布"），
     * 绕画幅中心自转 [MotionFrame.rotDeg]，再整体平移
     * [MotionFrame.dxRatio]·w / [MotionFrame.dyRatio]·h。
     *
     * 这里刻意只用「缩放 + 旋转 + 平移」这三种**仿射**变换，不做透视投影：
     *  - 仿射变换保持"平行线依旧平行"，画面不会出现梯形畸变，
     *    也就不会被看成"某一边被拉大 / 画面在变形"；
     *  - 位移完全由平移承担，配合全程恒定的 [margin]，人眼看到的就是
     *    **一个静止的画面在轻微晃动**，而不是一次变焦推拉。
     *
     * 变换链 L→R 是"先做什么"：先把源图中心移到原点 → 自转 → 放大 → 平移到目标位置。
     * 与 [requiredMargin] 的 `p = R(-θ)·(c - d) / margin` 严格互逆，所以只要 margin 足够，
     * 画布上不可能出现没有被源图覆盖的像素。
     */
    private fun produceMotionFrame(src: Bitmap, m: MotionFrame, margin: Float): Bitmap {
        val w = src.width
        val h = src.height
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)

        val matrix = Matrix()
        matrix.postTranslate(-w / 2f, -h / 2f)                          // 源图中心 → 原点
        matrix.postRotate(m.rotDeg)                                     // 绕画幅中心自转
        matrix.postScale(margin, margin)                                // 放大（= 裁掉边缘一圈）
        matrix.postTranslate(w / 2f + m.dxRatio * w, h / 2f + m.dyRatio * h)

        canvas.drawBitmap(src, matrix, FILTER_PAINT)
        return out
    }

    private companion object {
        private const val PI = 3.14159265f
        private const val TAU = 6.2831855f

        /**
         * 基准位移幅度（占画幅比例，0.016 = 画幅宽的 1.6%）。
         * 真手持拍 3 秒，画面峰值位移通常就在 1%~2% 画幅之间 ——
         * 再往上就开始像"刻意摇晃"，往下则完全看不出在动。
         */
        private const val BASE_DX = 0.016f
        private const val BASE_DY = 0.013f

        /**
         * 基准自转角（度）。手持实拍时画面自转极小，0.1° 已经足够提供
         * "这张照片是活的"的感觉；再大就会被看成"画面在歪"。
         */
        private const val BASE_ROT = 0.10f

        /** 包络指数：开方后 sin(πt) 的中段更饱满，全程都有可观位移，而首末帧仍精确归零 */
        private const val ENV_POWER = 0.55f

        /**
         * 裁切安全余量（0.010 = 1%）。
         * 理论 margin 已经能保证画布每一点都取到源图内容，留 1% 是为了抵掉
         * 重采样在边缘的半像素误差、以及 Float 三角函数与渲染管线的舍入差 ——
         * 实测把最紧处的余量从 1.5px 提到 6px 左右（1200 图），代价只是多裁 0.6%。
         */
        private const val MARGIN_SAFETY = 0.010f

        private val FILTER_PAINT = Paint(Paint.FILTER_BITMAP_FLAG)

        /**
         * 内置的 5 种手持风格。差异体现在三个维度上：
         * **节奏**（频率高低）、**方向**（横向位移 / 纵向位移 / 自转的权重）、**抖动**（高频手抖占比）。
         *
         * 每次生成随机取一种，并额外随机化运动相位，所以同一张图重复转换也不会一模一样。
         */
        private val MOTION_STYLES = listOf(
            // ① 自然手持：三轴均衡、中速 —— 最通用，随手拍的实况图基本就是这个样子
            MotionStyle(
                "自然手持",
                0.62f, 1.48f, 0.48f, 1.27f, 0.55f, 1.35f,
                1.00f, 0.85f, 0.90f, 4.6f, 0.30f, 1.05f, 2.30f
            ),
            // ② 轻微呼吸：慢、幅度小，几乎只有纵向在缓缓浮沉
            MotionStyle(
                "轻微呼吸",
                0.32f, 0.83f, 0.28f, 0.95f, 0.22f, 0.71f,
                0.70f, 1.00f, 0.55f, 3.6f, 0.22f, 1.20f, 1.90f
            ),
            // ③ 边走边拍：以横移为主、节奏偏慢，画面像被缓缓掠过
            MotionStyle(
                "边走边拍",
                0.38f, 1.02f, 0.30f, 0.88f, 0.42f, 1.15f,
                1.15f, 0.55f, 1.00f, 5.2f, 0.26f, 1.35f, 2.70f
            ),
            // ④ 手不太稳：频率偏高、自转更多，抖动感最明显的一档
            MotionStyle(
                "手不太稳",
                0.95f, 2.35f, 0.85f, 2.05f, 0.78f, 1.95f,
                0.90f, 0.80f, 1.10f, 6.5f, 0.42f, 0.95f, 1.50f
            ),
            // ⑤ 斜向慢摇：横纵两轴同频同相（相位偏移 = 0）⇒ 画面沿斜向缓缓平移
            MotionStyle(
                "斜向慢摇",
                0.45f, 1.25f, 0.45f, 1.25f, 0.38f, 1.08f,
                0.90f, 0.90f, 0.75f, 4.2f, 0.28f, 0.00f, 1.40f
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
