package com.livephotomaker

import android.content.ContentValues
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.livephotomaker.databinding.ActivityMainBinding
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val selectedUris = ArrayList<Uri>()
    private val REQ_PICK = 1001

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.selectBtn.setOnClickListener { openPicker() }
        binding.convertBtn.setOnClickListener { startConvert() }
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
        if (requestCode == REQ_PICK && resultCode == RESULT_OK && data != null) {
            selectedUris.clear()
            val clip = data.clipData
            if (clip != null) {
                for (i in 0 until clip.itemCount) selectedUris.add(clip.getItemAt(i).uri)
            } else {
                data.data?.let { selectedUris.add(it) }
            }
            // 持久化读取权限，方便以后再次访问
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
        val downloadsUri = MediaStore.Downloads.getContentUri("external")

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
                    if (dur == null || !videoTmp.exists() || videoTmp.length() == 0L) {
                        log("  ✗ 视频格式不支持（需 H.264 MP4），跳过")
                        videoTmp?.delete()
                        return@forEachIndexed
                    }
                    presentationTs = dur
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

                // 写入 MediaStore：Download/Live图/xxx.jpg
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, "$baseName.jpg")
                    put(MediaStore.Downloads.MIME_TYPE, "image/jpeg")
                    put(MediaStore.Downloads.RELATIVE_PATH, "Live图")
                }
                val outUri = resolver.insert(downloadsUri, values)
                if (outUri == null) {
                    log("  ✗ 无法创建输出文件（存储权限不足）")
                    return@forEachIndexed
                }
                resolver.openOutputStream(outUri)?.use { os ->
                    MotionPhotoWriter.wrap(cover!!, videoTmp!!, presentationTs, os)
                }
                log("  ✓ 完成，已保存到 Download/Live图/${baseName}.jpg")
                videoTmp?.delete()
            } catch (e: Exception) {
                log("  ✗ 出错：${e.message}")
            }
            runOnUiThread { binding.progressBar.progress = index + 1 }
        }

        log("全部处理完成！文件在：手机存储/Download/Live图")
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

    private fun log(msg: String) = runOnUiThread {
        binding.logText.append("$msg\n")
    }

    private fun toast(msg: String) = runOnUiThread {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}
