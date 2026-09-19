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
import android.provider.MediaStore
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.max

/**
 * 媒体处理引擎：对标原 Python 脚本
 *  - 视频：取首帧做封面，截前 3 秒（H.264 流拷贝，不重新编码）
 *  - 图片：生成 2 秒缓慢放大的视频（smoothstep 缓动）
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
     * 把视频截前 maxDurationUs 微秒，流拷贝为 MP4（不重新编码，要求 H.264）。
     * 返回实际时长（微秒）；不支持的格式返回 null。
     */
    fun trimVideoToMp4(uri: Uri, maxDurationUs: Long, outFile: File): Long? {
        val cr = context.contentResolver
        val pfd = cr.openFileDescriptor(uri, "r") ?: return null
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(pfd.fileDescriptor)
        } catch (e: Exception) {
            pfd.close()
            return null
        }

        val muxer = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val numTracks = extractor.trackCount
        val trackMap = IntArray(numTracks) { -1 }
        var videoTrack = -1
        var minStart = Long.MAX_VALUE

        for (i in 0 until numTracks) {
            val fmt = extractor.getTrackFormat(i)
            val mime = fmt.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("video/")) {
                if (mime != "video/avc" && mime != "video/mp4" && mime != "video/mp4v-es") {
                    extractor.release(); muxer.release(); pfd.close(); return null
                }
                videoTrack = i
            }
            extractor.selectTrack(i)
            val st = extractor.sampleTime
            if (st != -1L && st < minStart) minStart = st
            extractor.unselectTrack(i)
            trackMap[i] = muxer.addTrack(fmt)
        }
        if (videoTrack == -1) { extractor.release(); muxer.release(); pfd.close(); return null }
        if (minStart == Long.MAX_VALUE) minStart = 0

        muxer.start()
        val buffer = ByteBuffer.allocate(2 * 1024 * 1024)
        var actualDur = 0L
        for (i in 0 until numTracks) {
            extractor.selectTrack(i)
            val info = MediaCodec.BufferInfo()
            while (true) {
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                val t = extractor.sampleTime
                if (t - minStart > maxDurationUs) break
                val flags = extractor.sampleFlags
                buffer.position(0); buffer.limit(size)
                info.set(0, size, t - minStart, flags)
                muxer.writeSampleData(trackMap[i], buffer, info)
                if (t - minStart > actualDur) actualDur = t - minStart
                extractor.advance()
            }
            extractor.unselectTrack(i)
        }
        muxer.stop(); muxer.release(); extractor.release(); pfd.close()
        return actualDur
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
        val bitrate = (width * height * fps * 0.2).toInt().coerceIn(1_000_000, 8_000_000)
        val colorFormat = pickColorFormat()

        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            setInteger(MediaFormat.KEY_COLOR_FORMAT, colorFormat)
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
                        val nv21 = bitmapToNV21(frame)
                        frame.recycle()
                        val inBuf = encoder.getInputBuffer(inIdx)!!
                        inBuf.clear(); inBuf.put(nv21)
                        encoder.queueInputBuffer(inIdx, 0, nv21.size, pts, 0)
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

    /** Bitmap(ARGB) -> NV21(YUV420SP) 字节数组 */
    private fun bitmapToNV21(bitmap: Bitmap): ByteArray {
        val w = bitmap.width
        val h = bitmap.height
        val argb = IntArray(w * h)
        bitmap.getPixels(argb, 0, w, 0, 0, w, h)
        val yuv = ByteArray(w * h + (w * h) / 2)
        var yI = 0
        var uvI = w * h
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
                    yuv[uvI++] = v.toByte()   // NV21: V 在前
                    yuv[uvI++] = u.toByte()   //           U 在后
                }
            }
        }
        return yuv
    }

    /** 选择编码器支持的 YUV420 颜色格式（优先 Flexible，其次 SemiPlanar） */
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

    /** 检测视频文件的视频轨道 MIME 类型（如 video/hevc、video/avc） */
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
     * 通用视频转码 fallback：把任意 Android 可解码的视频（含 HEVC/H.265）转成 H.264 MP4。
     * 采用 MediaMetadataRetriever 逐帧取图再编码，速度较慢但兼容性好，仅用于前 3 秒。
     */
    fun transcodeVideoToMp4(uri: Uri, maxDurationUs: Long, outFile: File): Long? {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            val rawW = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toInt() ?: 1280
            val rawH = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toInt() ?: 720
            val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toInt() ?: 0
            val durMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong() ?: 0L
            val targetUs = kotlin.math.min(durMs * 1000L, maxDurationUs)
            if (targetUs <= 0) return null

            val srcW = if (rotation % 180 == 0) rawW else rawH
            val srcH = if (rotation % 180 == 0) rawH else rawW

            val maxDim = 1280
            var outW = srcW
            var outH = srcH
            if (kotlin.math.max(srcW, srcH) > maxDim) {
                val scale = maxDim.toFloat() / kotlin.math.max(srcW, srcH)
                outW = (srcW * scale).toInt() / 2 * 2
                outH = (srcH * scale).toInt() / 2 * 2
            }

            val fps = 15
            val totalSeconds = targetUs / 1_000_000L
            val totalFrames = kotlin.math.max(1, (totalSeconds * fps).toInt())
            val frameDurUs = 1_000_000L / fps
            val bitrate = (outW * outH * fps * 0.2).toInt().coerceIn(1_000_000, 8_000_000)

            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, outW, outH).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                setInteger(MediaFormat.KEY_COLOR_FORMAT, pickColorFormat())
            }

            val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()

            val muxer = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            var muxerStarted = false
            var trackIndex = -1
            val bufferInfo = MediaCodec.BufferInfo()

            var frameIndex = 0
            var inputDone = false
            var outputDone = false
            var pts = 0L

            while (!outputDone) {
                if (!inputDone) {
                    val inIdx = encoder.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        if (frameIndex < totalFrames) {
                            val timeUs = frameIndex * frameDurUs
                            val rawBmp = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST)
                            if (rawBmp == null) {
                                encoder.queueInputBuffer(inIdx, 0, 0, 0, 0)
                            } else {
                                val frame = preprocessFrame(rawBmp, rotation, outW, outH)
                                rawBmp.recycle()
                                val nv21 = bitmapToNV21(frame)
                                frame.recycle()
                                val inBuf = encoder.getInputBuffer(inIdx)!!
                                inBuf.clear()
                                inBuf.put(nv21)
                                encoder.queueInputBuffer(inIdx, 0, nv21.size, pts, 0)
                            }
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
                        muxer.start()
                        muxerStarted = true
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

            muxer.stop()
            muxer.release()
            encoder.stop()
            encoder.release()
            return targetUs
        } catch (e: Exception) {
            return null
        } finally {
            retriever.release()
        }
    }

    /** 按目标尺寸和旋转角度预处理一帧 */
    private fun preprocessFrame(bmp: Bitmap, rotation: Int, outW: Int, outH: Int): Bitmap {
        val matrix = Matrix()
        if (rotation != 0) matrix.postRotate(rotation.toFloat())
        if (bmp.width != outW || bmp.height != outH) {
            val scaleX = outW.toFloat() / bmp.width
            val scaleY = outH.toFloat() / bmp.height
            matrix.postScale(scaleX, scaleY)
        }
        return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, true)
    }
}
