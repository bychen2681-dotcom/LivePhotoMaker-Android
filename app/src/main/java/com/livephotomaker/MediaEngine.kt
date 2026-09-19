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
import kotlin.math.max

/**
 * 媒体处理引擎：对标原 Python 脚本（video_转Live图.py）
 *  - 视频：取首帧做封面；用 MediaExtractor + MediaMuxer 流拷贝为 MP4（与 Python 中
 *    ffmpeg -t 3 -c copy 一致），只保留前 3 秒，并从第一个关键帧开始，避免花屏。
 *  - 图片：生成 2 秒缓慢放大的视频（smoothstep 缓动），封装为 H.264 MP4
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
        val endUs = startUs + durationUs

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
