package app.manman.teacher.recognition

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

internal data class ScheduleFile(val kind: String, val data: ByteArray)

internal object ScheduleDocuments {
    suspend fun read(context: Context, uris: List<Uri>, pdfRotation: Int = 0): List<ScheduleFile> = withContext(Dispatchers.IO) {
        require(uris.isNotEmpty() && uris.size <= 7) { "请选择最多 7 个文件。" }
        require(pdfRotation in setOf(0, 90, 270)) { "PDF 方向无效。" }
        val files = mutableListOf<ScheduleFile>()
        for (uri in uris) {
            val data = context.contentResolver.openInputStream(uri)?.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                var total = 0
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    total += n
                    require(total <= 8 * 1024 * 1024) { "每个文件不能超过 8 MB。" }
                    output.write(buffer, 0, n)
                }
                output.toByteArray()
            } ?: throw IllegalArgumentException("无法读取文件，请重新选择。")
            val prefix = data.take(8).map(Byte::toInt).map { it and 255 }
            when {
                data.size >= 5 && String(data, 0, 5, Charsets.US_ASCII) == "%PDF-" -> files.addAll(renderPdf(context, data, pdfRotation))
                prefix.size >= 4 && prefix.take(4) == listOf(0x50, 0x4b, 0x03, 0x04) -> files.add(ScheduleFile("xlsx", data))
                prefix == listOf(0xd0, 0xcf, 0x11, 0xe0, 0xa1, 0xb1, 0x1a, 0xe1) -> files.add(ScheduleFile("xls", data))
                else -> {
                    val info = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(data, 0, data.size, info)
                    require(info.outWidth > 0 && info.outHeight > 0 && info.outMimeType in setOf("image/jpeg", "image/png", "image/webp")) {
                        "请选择图片、未加密 PDF 或 Excel 文件。"
                    }
                    files.add(ScheduleFile(info.outMimeType, data))
                }
            }
            require(files.count { it.kind.startsWith("image/") } <= 5 && files.size <= 7) { "一次最多识别 5 张图片或 PDF 页面。" }
            require(files.sumOf { it.data.size.toLong() } <= 12L * 1024 * 1024) { "文件合计不能超过 12 MB。" }
        }
        files
    }

    private fun renderPdf(context: Context, data: ByteArray, rotation: Int): List<ScheduleFile> {
        val temporary = File(context.cacheDir, "schedule-${UUID.randomUUID()}.pdf")
        try {
            temporary.writeBytes(data)
            val descriptor = ParcelFileDescriptor.open(temporary, ParcelFileDescriptor.MODE_READ_ONLY)
            val renderer = try { PdfRenderer(descriptor) } catch (error: Exception) { descriptor.close(); throw error }
            return renderer.use { pdf ->
                require(pdf.pageCount in 1..5) { "PDF 最多支持 5 页，请选择需要的页面。" }
                (0 until pdf.pageCount).map { number ->
                    pdf.openPage(number).use { page ->
                        val scale = minOf(2000f / page.width, 2400f / page.height)
                        val bitmap = Bitmap.createBitmap((page.width * scale).toInt().coerceAtLeast(1),
                            (page.height * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                        try {
                            Canvas(bitmap).drawColor(Color.WHITE)
                            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            val image = if (rotation == 0) bitmap else Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height,
                                Matrix().apply { postRotate(rotation.toFloat()) }, false)
                            try {
                                val output = ByteArrayOutputStream()
                                require(image.compress(Bitmap.CompressFormat.JPEG, 88, output)) { "PDF 页面无法转换。" }
                                ScheduleFile("image/jpeg", output.toByteArray())
                            } finally { if (image !== bitmap) image.recycle() }
                        } finally { bitmap.recycle() }
                    }
                }
            }
        } catch (error: IllegalArgumentException) {
            throw error
        } catch (error: Exception) {
            throw IllegalArgumentException("PDF 无法读取，请确认文件没有加密或损坏。")
        } finally { temporary.delete() }
    }
}
