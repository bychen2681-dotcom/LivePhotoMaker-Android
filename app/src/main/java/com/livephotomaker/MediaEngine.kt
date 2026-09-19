package com.livephotomaker

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.max

/**
 * 媒体处理引擎：对标原 Python 脚本（video_转Live图.py）
 *  - 视频：取首帧做封面；整段视频解码后逐帧重编码为 H.264 MP4（与原脚本"逐帧重编码"一致，不乱码）
 *  - 图片：生成 2 秒缓慢放大的视频（smoothstep 缓动），封装为 H.264 MP4
 *
 * 色彩关键修正：
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
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            val bmp = retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: return null
            val data = bitmapToJpeg(bmp, 92)
            bmp.recycle()
            data
        } catch (e: Exception) {
            null
        } finally {
            retriever.release()
        }
    }

    /**
     * 把单张图片生成「缓慢放大」视频（smoothstep 缓动），封装为 H.264 MP4。
     * 参数对标原脚本：durationSec=2.0, fps=15, maxZoom=0.035
     */
    fun makeZoomVideo(src: Bitmap, outFile: File, durationSec: Float, fps: Int, maxZoom: Float) {
        var width = (src.width / 2) * 2
        var height = (src.height / 2) * 2
        if (width <= 0) width = 2
        if (height <= 0) height = 2

        val totalFrames = max(2, (durationSec * fps).toInt())
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
                        val t = frameIndex.toFloat() / (totalFrames - 1)
                        val ease = t * t * (3 - 2 * t)            // smoothstep
                        val scale = 1.0 + maxZoom * ease
                        val frame = produceZoomFrame(base, scale, width, height)
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
    }

    /** 按 scale 以中心为锚点放大并从中心裁剪回原尺寸（对应 Python generate_zoom_frame） */
    private fun produceZoomFrame(src: Bitmap, scale: Double, w: Int, h: Int): Bitmap {
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val matrix = Matrix()
        matrix.setScale(scale.toFloat(), scale.toFloat(), w / 2f, h / 2f)
        canvas.drawBitmap(src, matrix, Paint(Paint.FILTER_BITMAP_FLAG))
        return out
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
        // 极少数设备只有 Planar，需要 YV12 布局（本类未实现，优先前面两种）
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
     * 把任意 Android 可解码的视频（H.264 或 HEVC/H.265）整段解码、逐帧重编码为 H.264 MP4。
     * 与原 Python 脚本"逐帧重新编码"思路一致：保证开头是关键帧、无 B 帧错乱，播放不乱码。
     * 最多保留前 maxDurationUs 微秒。返回实际时长（微秒）；失败返回 null。
     */
    fun reencodeVideoToMp4(uri: Uri, maxDurationUs: Long, outFile: File): Long? {
        val cr = context.contentResolver
        val pfd = cr.openFileDescriptor(uri, "r") ?: return null
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(pfd.fileDescriptor)
        } catch (e: Exception) {
            pfd.close()
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
            extractor.release(); pfd.close(); return null
        }

        val srcW = videoFormat.getInteger(MediaFormat.KEY_WIDTH)
        val srcH = videoFormat.getInteger(MediaFormat.KEY_HEIGHT)
        val rotation = if (videoFormat.containsKey(MediaFormat.KEY_ROTATION))
            (videoFormat.getInteger(MediaFormat.KEY_ROTATION) + 360) % 360 else 0
        val outW = if (rotation == 90 || rotation == 270) srcH else srcW
        val outH = if (rotation == 90 || rotation == 270) srcW else srcH

        val fps = if (videoFormat.containsKey(MediaFormat.KEY_FRAME_RATE)) {
            videoFormat.getInteger(MediaFormat.KEY_FRAME_RATE).coerceIn(1, 60)
        } else 30
        val bitrate = (outW * outH * fps * 0.25).toInt().coerceIn(1_000_000, 12_000_000)

        val colorFormat = pickColorFormat()
        val encFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, outW, outH).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            setInteger(MediaFormat.KEY_COLOR_FORMAT, colorFormat)
            setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
            setColorStandard(this)
        }

        val decoder = MediaCodec.createDecoderByType(videoFormat.getString(MediaFormat.KEY_MIME)!!)
        var decoderStarted = false
        try {
            decoder.configure(videoFormat, null, null, 0)
            decoder.start()
            decoderStarted = true
        } catch (e: Exception) {
            decoder.release(); extractor.release(); pfd.close(); return null
        }

        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        encoder.configure(encFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        encoder.start()

        val muxer = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var muxerStarted = false
        var trackIndex = -1
        val eInfo = MediaCodec.BufferInfo()
        val dInfo = MediaCodec.BufferInfo()

        extractor.selectTrack(videoTrack)

        var decoderEos = false
        var encoderEos = false
        var extractorDone = false
        var maxPtsFed = 0L
        var guard = 0

        while (!encoderEos && guard < 200000) {
            guard++

            // 1) 喂解码器：从 extractor 取样本
            if (!decoderEos && !extractorDone) {
                val inIdx = decoder.dequeueInputBuffer(10_000)
                if (inIdx >= 0) {
                    val buf = decoder.getInputBuffer(inIdx)!!
                    buf.position(0)
                    val size = extractor.readSampleData(buf, 0)
                    if (size < 0) {
                        decoder.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        decoderEos = true
                    } else {
                        val pts = extractor.sampleTime
                        val flags = extractor.sampleFlags
                        if (pts > maxDurationUs) {
                            decoder.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            decoderEos = true
                            extractorDone = true
                        } else {
                            decoder.queueInputBuffer(inIdx, 0, size, pts, flags)
                            extractor.advance()
                        }
                    }
                }
            }

            // 2) 排空解码器 -> 编码（带旋转与色彩转换）
            val dOut = decoder.dequeueOutputBuffer(dInfo, 10_000)
            when {
                dOut == MediaCodec.INFO_TRY_AGAIN_LATER -> { /* 继续 */ }
                dOut == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> { /* 忽略，用 getOutputImage 即可 */ }
                dOut >= 0 -> {
                    val img = decoder.getOutputImage(dOut)
                    val pts = dInfo.presentationTimeUs
                    if (img != null && pts <= maxDurationUs) {
                        val nv12 = imageToNV12(img, rotation, outW, outH)
                        val eIn = encoder.dequeueInputBuffer(10_000)
                        if (eIn >= 0) {
                            val eb = encoder.getInputBuffer(eIn)!!
                            eb.clear(); eb.put(nv12)
                            val feedPts = if (pts < 0) 0L else pts
                            encoder.queueInputBuffer(eIn, 0, nv12.size, feedPts, 0)
                            if (feedPts > maxPtsFed) maxPtsFed = feedPts
                        }
                    }
                    decoder.releaseOutputBuffer(dOut, false)
                    if (dInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        val eIn = encoder.dequeueInputBuffer(10_000)
                        if (eIn >= 0) encoder.queueInputBuffer(eIn, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    }
                }
            }

            // 3) 排空编码器 -> muxer
            val eOut = encoder.dequeueOutputBuffer(eInfo, 10_000)
            when {
                eOut == MediaCodec.INFO_TRY_AGAIN_LATER -> { /* 继续 */ }
                eOut == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    trackIndex = muxer.addTrack(encoder.outputFormat)
                    muxer.start(); muxerStarted = true
                }
                eOut >= 0 -> {
                    val ob = encoder.getOutputBuffer(eOut)!!
                    if (eInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && muxerStarted) {
                        muxer.writeSampleData(trackIndex, ob, eInfo)
                    }
                    encoder.releaseOutputBuffer(eOut, false)
                    if (eInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) encoderEos = true
                }
            }
        }

        val ok = muxerStarted && outFile.exists() && outFile.length() > 0 &&
                (maxPtsFed > 0 || maxPtsFed == 0L)
        muxer.stop(); muxer.release()
        encoder.stop(); encoder.release()
        decoder.stop(); decoder.release()
        extractor.release(); pfd.close()

        return if (outFile.exists() && outFile.length() > 0) maxPtsFed else null
    }

    /**
     * Image(YUV_420_888) -> NV12(含旋转) 字节数组。
     * 先把 Image 拆成 Y/U/V 三个平面，再按 rotation 旋转后交错成 NV12（U 在前 V 在后）。
     */
    private fun imageToNV12(image: Image, rotation: Int, outW: Int, outH: Int): ByteArray {
        val w = image.width
        val h = image.height
        val (yIn, uIn, vIn) = imageToPlanar(image, w, h)

        val yOut = ByteArray(outW * outH)
        val cw = outW / 2
        val ch = outH / 2
        val uOut = ByteArray(cw * ch)
        val vOut = ByteArray(cw * ch)

        val rot = (rotation % 360 + 360) % 360
        for (oy in 0 until outH) {
            for (ox in 0 until outW) {
                val (sx, sy) = when (rot) {
                    90 -> Pair(oy, h - 1 - ox)
                    180 -> Pair(w - 1 - ox, h - 1 - oy)
                    270 -> Pair(w - 1 - oy, ox)
                    else -> Pair(ox, oy)
                }
                yOut[oy * outW + ox] = yIn[sy * w + sx]
                if (oy % 2 == 0 && ox % 2 == 0) {
                    val su = sx / 2
                    val sv = sy / 2
                    val cu = oy / 2
                    val cv = ox / 2
                    uOut[cu * cw + cv] = uIn[sv * (w / 2) + su]
                    vOut[cu * cw + cv] = vIn[sv * (w / 2) + su]
                }
            }
        }

        val nv12 = ByteArray(outW * outH + cw * ch * 2)
        System.arraycopy(yOut, 0, nv12, 0, yOut.size)
        var p = yOut.size
        for (i in 0 until cw * ch) {
            nv12[p++] = uOut[i]
            nv12[p++] = vOut[i]
        }
        return nv12
    }

    /** 把 YUV_420_888 的 Image 拆成三个平面（Y / U / V）的紧凑数组 */
    private fun imageToPlanar(image: Image, w: Int, h: Int): Triple<ByteArray, ByteArray, ByteArray> {
        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]
        val yb = yPlane.buffer
        val ub = uPlane.buffer
        val vb = vPlane.buffer
        val ys = yPlane.rowStride
        val yp = yPlane.pixelStride
        val us = uPlane.rowStride
        val up = uPlane.pixelStride
        val vs = vPlane.rowStride
        val vp = vPlane.pixelStride

        val yOut = ByteArray(w * h)
        val uOut = ByteArray(w * h / 4)
        val vOut = ByteArray(w * h / 4)

        var yi = 0
        var ui = 0
        var vi = 0
        for (j in 0 until h) {
            val yRow = j * ys
            for (i in 0 until w) {
                yOut[yi++] = yb.get(yRow + i * yp)
            }
            if (j % 2 == 0) {
                val uRow = (j / 2) * us
                val vRow = (j / 2) * vs
                for (i in 0 until w step 2) {
                    uOut[ui++] = ub.get(uRow + i * up)
                    vOut[vi++] = vb.get(vRow + i * vp)
                }
            }
        }
        return Triple(yOut, uOut, vOut)
    }
}
