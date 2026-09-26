package com.livephotomaker

import android.content.ContentResolver
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Spinner
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
    private var splitMode: Boolean = false
    private var intensity: Float = 1.0f
    private var intensityName: String = "标准"
    private val REQ_PICK = 1001
    private val REQ_PICK_DIR = 1002

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.selectDirBtn.setOnClickListener { openDirPicker() }
        binding.selectBtn.setOnClickListener { openPicker() }
        binding.convertBtn.setOnClickListener { startConvert() }

        val modes = arrayOf("图片或者视频转换", "长视频切割")
        val spinnerAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, modes)
        spinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.modeSpinner.adapter = spinnerAdapter
        binding.modeSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, position: Int, id: Long) {
                splitMode = position == 1
                binding.segContainer.visibility = if (splitMode) View.VISIBLE else View.GONE
                binding.selectBtn.text = if (splitMode) "选择视频（可多选）" else "选择图片 / 视频（可多选）"
            }
            override fun onNothingSelected(parent: AdapterView<*>) {}
        }

        // 图片动效强度：只影响"图片 → Live 图"的晃动幅度，视频是原样截取，不受影响
        val intensityNames = arrayOf("轻柔（最接近真实实况图）", "标准", "明显")
        val intensityValues = floatArrayOf(0.6f, 1.0f, 1.5f)
        val intensityAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, intensityNames)
        intensityAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.motionSpinner.adapter = intensityAdapter
        binding.motionSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, position: Int, id: Long) {
                intensity = intensityValues[position]
                intensityName = intensityNames[position].substringBefore("（")
            }

            override fun onNothingSelected(parent: AdapterView<*>) {}
        }
        binding.motionSpinner.setSelection(1)   // 默认「标准」
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

    private fun readSegSeconds(): Int {
        val txt = binding.segEdit.text.toString().trim()
        val v = txt.toIntOrNull()
        return if (v != null && v in 1..30) v else 3
    }

    private fun runConvert() {
        val engine = MediaEngine(this)
        val parentDir = DocumentFile.fromTreeUri(this, selectedDirUri!!)
            ?: run {
                log("✗ 无法访问选中的保存目录")
                return
            }
        val split = splitMode
        val segUs = if (split) readSegSeconds() * 1_000_000L else 0L

        selectedUris.forEachIndexed { index, uri ->
            val mime = contentResolver.getType(uri) ?: ""
            val displayName = getDisplayName(uri)
            val baseName = displayName.substringBeforeLast(".")
            log("[${(index + 1)}/${selectedUris.size}] 处理：$displayName")
            try {
                when {
                    mime.startsWith("video/") -> handleVideo(engine, parentDir, uri, baseName, segUs, split)
                    mime.startsWith("image/") -> handleImage(engine, parentDir, uri, baseName)
                    else -> log("  ✗ 不支持的文件类型，跳过")
                }
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

    /** 图片：生成 3 秒「手持漂移感」视频（无变焦放大），封装成 1 个 Live 图 */
    private fun handleImage(engine: MediaEngine, parentDir: DocumentFile, uri: Uri, baseName: String) {
        val bmp = engine.decodeImage(uri, 1280)
        if (bmp == null) {
            log("  ✗ 无法解码图片，跳过")
            return
        }
        val cover = engine.bitmapToJpeg(bmp, 92)
        val videoTmp = File(cacheDir, "i_${System.currentTimeMillis()}.mp4")
        log("  正在生成 3 秒真实手持晃动效果（相机三轴微转 + 恒定裁切，每次随机一种风格）...")
        val styleName = engine.makeHandheldVideo(bmp, videoTmp, 3.0f, 30, intensity)
        log("  ✓ 本次动效：$styleName · 强度 $intensityName")
        bmp.recycle()
        if (!videoTmp.exists() || videoTmp.length() == 0L) {
            log("  ✗ 生成视频失败，跳过")
            videoTmp.delete()
            return
        }
        saveLivePhoto(engine, parentDir, cover, videoTmp, 3_000_000L, baseName, "")
        videoTmp.delete()
    }

    /** 视频：按模式处理。split=true 时按设定时长切成多段；否则整段直接转成 1 个 Live 图 */
    private fun handleVideo(
        engine: MediaEngine,
        parentDir: DocumentFile,
        uri: Uri,
        baseName: String,
        segUs: Long,
        split: Boolean
    ) {
        val duration = engine.getVideoDurationUs(uri)
        val durText = if (duration != null) String.format("%.2f", duration / 1_000_000.0) + " 秒" else "未知"
        log("  视频时长：$durText")

        if (!split) {
            // 模式一：整段直接转成 1 个 Live 图（不分段）
            val videoTmp = File(cacheDir, "v_${System.currentTimeMillis()}.mp4")
            log("  整段转换为 1 个 Live 图（不解码、保持原编码）...")
            val dur = engine.transmuxVideoSegment(uri, 0L, 0L, videoTmp)
            if (dur == null || !videoTmp.exists() || videoTmp.length() == 0L) {
                log("  ✗ 视频转换失败，跳过")
                videoTmp.delete()
                return
            }
            val cover = engine.extractCoverFromVideo(uri) ?: engine.extractCoverFromVideo(uri, 0L)
            if (cover == null) {
                log("  ✗ 封面提取失败，跳过")
                videoTmp.delete()
                return
            }
            val saved = saveLivePhoto(engine, parentDir, cover, videoTmp, dur, baseName, "")
            videoTmp.delete()
            log(if (saved) "  ✓ 完成，已生成 1 个 Live 图" else "  ✗ 写入失败")
            return
        }

        // 模式二：长视频切割，每段一个 Live 图
        val segCount = if (duration != null && duration > segUs) {
            ((duration + segUs - 1) / segUs).toInt()
        } else 1
        log("  将切成 $segCount 段（每段约 ${segUs / 1_000_000} 秒）")

        var okCount = 0
        for (s in 0 until segCount) {
            val startUs = s * segUs
            val videoTmp = File(cacheDir, "v_${System.currentTimeMillis()}_$s.mp4")
            log("  正在截取第 ${s + 1}/$segCount 段...")
            val dur = engine.transmuxVideoSegment(uri, startUs, segUs, videoTmp)
            if (dur == null || !videoTmp.exists() || videoTmp.length() == 0L) {
                log("    ✗ 第 ${s + 1} 段截取失败，跳过")
                videoTmp.delete()
                continue
            }
            val cover = engine.extractCoverFromVideo(uri, startUs) ?: engine.extractCoverFromVideo(uri)
            if (cover == null) {
                log("    ✗ 第 ${s + 1} 段封面提取失败，跳过")
                videoTmp.delete()
                continue
            }
            val suffix = if (segCount > 1) "_${s + 1}" else ""
            val saved = saveLivePhoto(engine, parentDir, cover, videoTmp, dur, baseName, suffix)
            videoTmp.delete()
            if (saved) okCount++
        }

        if (okCount == 0) log("  ✗ 所有分段都失败了")
        else log("  ✓ 完成，已生成 $okCount 个 Live 图")
    }

    /** 在“Live图”子目录下写出封面 JPEG + MP4 封装的 Live 图 */
    private fun saveLivePhoto(
        engine: MediaEngine,
        parentDir: DocumentFile,
        cover: ByteArray,
        videoFile: File,
        presentationTs: Long,
        baseName: String,
        suffix: String
    ): Boolean {
        val liveDir = parentDir.findFile("Live图")
            ?: parentDir.createDirectory("Live图")
            ?: parentDir
        val outDoc = liveDir.createFile("image/jpeg", "$baseName$suffix.jpg")
            ?: run {
                log("  ✗ 无法创建输出文件（目录无写入权限）")
                return false
            }
        contentResolver.openOutputStream(outDoc.uri)?.use { os ->
            MotionPhotoWriter.wrap(cover, videoFile, presentationTs, os)
        }
        log("  ✓ 已保存：${selectedDirName}/Live图/${baseName}${suffix}.jpg")
        return true
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
