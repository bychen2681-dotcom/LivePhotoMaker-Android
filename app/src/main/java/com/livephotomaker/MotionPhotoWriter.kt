package com.livephotomaker

import java.io.File
import java.io.OutputStream

/**
 * 把「封面 JPEG + MP4」封装成 Google Motion Photo（Live 图）格式。
 * 逻辑 1:1 移植自原 Python 脚本 create_motion_photo()：
 *   - 在 JPEG 的 APP0 段之后插入 APP1（XMP 元数据）
 *   - 在 JPEG 文件末尾追加 MP4 视频数据
 */
object MotionPhotoWriter {

    fun wrap(coverJpeg: ByteArray, videoFile: File, presentationTsUs: Long, out: OutputStream) {
        val videoBytes = videoFile.readBytes()
        val videoSize = videoBytes.size

        val xmp = buildXmp(presentationTsUs, videoSize)
        val ns = "http://ns.adobe.com/xap/1.0/\u0000".toByteArray(Charsets.US_ASCII)
        val xmpData = ns + xmp.toByteArray(Charsets.UTF_8)

        val app1Len = xmpData.size + 2
        val app1 = ByteArray(4)
        app1[0] = 0xFF.toByte()
        app1[1] = 0xE1.toByte()
        app1[2] = (app1Len ushr 8).toByte()
        app1[3] = (app1Len and 0xFF).toByte()

        // 插在 JPEG 的 APP0 段之后（没有 APP0 就插在 SOI 之后）
        var insertPos = 2
        if (coverJpeg.size > 4 && coverJpeg[2] == 0xFF.toByte() && coverJpeg[3] == 0xE0.toByte()) {
            val app0Len = ((coverJpeg[4].toInt() and 0xFF) shl 8) or (coverJpeg[5].toInt() and 0xFF)
            insertPos = 4 + app0Len
        }

        out.write(coverJpeg, 0, insertPos)
        out.write(app1)
        out.write(xmpData)
        out.write(coverJpeg, insertPos, coverJpeg.size - insertPos)
        out.write(videoBytes)
    }

    private fun buildXmp(ts: Long, videoSize: Int): String {
        return XMP_TEMPLATE
            .replace("_TS_", ts.toString())
            .replace("_LEN_", videoSize.toString())
    }

    // 与 Python 脚本完全一致的 XMP 模板（BOM 占位符在末尾替换为真正的 U+FEFF）
    private val XMP_TEMPLATE = """
<?xpacket begin="BOM" id="W5M0MpCehiHzreSzNTczkc9d"?>
<x:xmpmeta xmlns:x="adobe:ns:meta/" x:xmptk="Adobe XMP Core 5.1.0-jc003">
  <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
    <rdf:Description rdf:about=""
        xmlns:GCamera="http://ns.google.com/photos/1.0/camera/"
        xmlns:Container="http://ns.google.com/photos/1.0/container/"
        xmlns:Item="http://ns.google.com/photos/1.0/container/item/"
      GCamera:MotionPhoto="1"
      GCamera:MotionPhotoVersion="1"
      GCamera:MotionPhotoPresentationTimestampUs="_TS_">
      <Container:Directory>
        <rdf:Seq>
          <rdf:li rdf:parseType="Resource">
            <Container:Item
              Item:Mime="image/jpeg"
              Item:Semantic="Primary"/>
          </rdf:li>
          <rdf:li rdf:parseType="Resource">
            <Container:Item
              Item:Mime="video/mp4"
              Item:Semantic="MotionPhoto"
              Item:Length="_LEN_"
              Item:Padding="0"/>
          </rdf:li>
        </rdf:Seq>
      </Container:Directory>
    </rdf:Description>
  </rdf:RDF>
</x:xmpmeta>
<?xpacket end="w"?>
""".trimIndent().replace("BOM", "\uFEFF")
}
