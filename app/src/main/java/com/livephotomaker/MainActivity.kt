package com.livephotomaker

import android.content.ContentResolver
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import com.livephotomaker.databinding.ActivityMainBinding
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val selectedUris = ArrayList<Uri>()
    private var selectedDirUri: Uri? = null
    private var selectedDirName: String = "未选择"
    private val REQ_PICK = 1001
    private val REQ_PICK_DIR = 1002

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.selectDirBtn.setOnClickListener { openDirPicker() }
        binding.selectBtn.setOnClickListener { openPicker() }
        binding.convertBtn.setOnClickListener { startConvert() }
    }

    private fun openDirPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
        startActivityForResult(intent, REQ_PICK_DIR)
    }

    private fun openPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/*", "video/*"))
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        startActivityForResult(intent, REQ_PICK)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data == null) return

        if (requestCode == REQ_PICK_DIR) {
            data.data?.let { uri ->
                try {
                    contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    )
                } catch (_: Exception) { /* 部分提供方可能不支持，继续用一次性权限 */ }
                selectedDirUri = uri
                selectedDirName = getDirDisplayName(uri) ?: uri.toString()
                binding.dirText.text = "保存到：$selectedDirName"
            }
            return
        }

        if (requestCode == REQ_PICK) {
            selectedUris.clear()
            val clip = data.clipData
            if (clip != null) {
                for (i in 0 until clip.itemCount) selectedUris.add(clip.getItemAt(i).uri)
            } else {
                data.data?.let { selectedUris.add(it) }
            }
            selectedUris.forEach { uri ->
                try {
                    contentResolver.takePersistableUriPermission(
                        uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (_: Exception) { /* 部分提供方不支持，忽略 */ }
            }
            binding.selectedText.text = "已选：${selectedUris.size} 个文件"
        }
    }

    private fun startConvert() {
        if (selectedDirUri == null) {
            toast("请先选择保存目录")
            return
        }
        if (selectedUris.isEmpty()) {
            toast("请先选择图片或视频")
            return
        }
        binding.convertBtn.isEnabled = false
        binding.progressBar.max = selectedUris.size
        binding.progressBar.progress = 0
        Thread { runConvert() }.start()
    }

    private fun runConvert() {
        val engine = MediaEngine(this)
        val resolver = contentResolver
        val parentDir = DocumentFile.fromTreeUri(this, selectedDirUri!!)
            ?: run {
                log("✗ 无法访问选中的保存目录")
                return
            }

        selectedUris.forEachIndexed { index, uri ->
            val mime = resolver.getType(uri) ?: ""
            val displayName = getDisplayName(uri)
            val baseName = displayName.substringBeforeLast(".")
            log("[${(index + 1)}/${selectedUris.size}] 处理：$displayName")
            try {
                var cover: ByteArray? = null
                var videoTmp: File? = null
                var presentationTs = 0L

                if (mime.startsWith("video/")) {
                    cover = engine.extractCoverFromVideo(uri)
                    if (cover == null) {
                        log("  ✗ 无法提取封面，跳过")
                        return@forEachIndexed
                    }
                    videoTmp = File(cacheDir, "v_$index.mp4")
                    val dur = engine.trimVideoToMp4(uri, 3_000_000L, videoTmp)
                    if (dur != null && videoTmp.exists() && videoTmp.length() > 0L) {
                        presentationTs = dur
                    } else {
                        videoTmp.delete()
                        log("  非 H.264 视频，尝试自动转码为 H.264...")
                        val transDur = engine.transcodeVideoToMp4(uri, 3_000_000L, videoTmp)
                        if (transDur == null || !videoTmp.exists() || videoTmp.length() == 0L) {
                            val detected = engine.detectVideoMime(uri) ?: "未知"
                            log("  ✗ 视频处理失败（检测到 $detected）。手机录的视频若开了 HEVC/H.265，请在相机设置里关闭\"高效视频编码\"后重录。")
                            videoTmp.delete()
                            return@forEachIndexed
                        }
                        presentationTs = transDur
                    }
                } else if (mime.startsWith("image/")) {
                    val bmp = engine.decodeImage(uri, 1280)
                    if (bmp == null) {
                        log("  ✗ 无法解码图片，跳过")
                        return@forEachIndexed
                    }
                    cover = engine.bitmapToJpeg(bmp, 92)
                    videoTmp = File(cacheDir, "i_$index.mp4")
                    engine.makeZoomVideo(bmp, videoTmp, 2.0f, 15, 0.035f)
                    bmp.recycle()
                    if (!videoTmp.exists() || videoTmp.length() == 0L) {
                        log("  ✗ 生成视频失败，跳过")
                        return@forEachIndexed
                    }
                    presentationTs = 2_000_000L
                } else {
                    log("  ✗ 不支持的文件类型，跳过")
                    return@forEachIndexed
                }

                // 在选中的目录下创建/查找 Live图 子目录
                val liveDir = parentDir.findFile("Live图")
                    ?: parentDir.createDirectory("Live图")
                    ?: parentDir
                val outDoc = liveDir.createFile("image/jpeg", "$baseName.jpg")
                    ?: run {
                        log("  ✗ 无法创建输出文件（目录无写入权限）")
                        return@forEachIndexed
                    }
                resolver.openOutputStream(outDoc.uri)?.use { os ->
                    MotionPhotoWriter.wrap(cover!!, videoTmp!!, presentationTs, os)
                }
                log("  ✓ 完成，已保存到 ${selectedDirName}/Live图/${baseName}.jpg")
                videoTmp?.delete()
            } catch (e: Exception) {
                log("  ✗ 出错：${e.message}")
            }
            runOnUiThread { binding.progressBar.progress = index + 1 }
        }

        log("全部处理完成！文件在：${selectedDirName}/Live图")
        runOnUiThread {
            binding.convertBtn.isEnabled = true
            toast("转换完成")
        }
    }

    private fun getDisplayName(uri: Uri): String {
        var name = "file"
        var cursor: Cursor? = null
        try {
            cursor = contentResolver.query(
                uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null
            )
            if (cursor != null && cursor.moveToFirst()) {
                name = cursor.getString(0) ?: "file"
            }
        } catch (_: Exception) {
        } finally {
            cursor?.close()
        }
        return name
    }

    private fun getDirDisplayName(uri: Uri): String? {
        return try {
            DocumentFile.fromTreeUri(this, uri)?.name ?: uri.lastPathSegment
        } catch (_: Exception) {
            uri.lastPathSegment
        }
    }

    private fun log(msg: String) = runOnUiThread {
        binding.logText.append("$msg\n")
    }

    private fun toast(msg: String) = runOnUiThread {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}
