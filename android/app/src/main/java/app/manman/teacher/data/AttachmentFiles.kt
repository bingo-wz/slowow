package app.manman.teacher.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.UUID

class AttachmentFiles(private val context: Context, private val baseUrl: String) {
    private val maximumSize = 8L * 1024 * 1024
    private val validId = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
    private val validHash = Regex("^[0-9a-f]{64}$")
    private val supported = setOf("image/jpeg", "image/png", "image/webp")
    private val drafts: File get() = File(context.filesDir, "image-drafts").apply { mkdirs() }

    suspend fun stage(uri: Uri): AttachmentMeta = withContext(Dispatchers.IO) {
        val id = UUID.randomUUID().toString()
        val target = File(drafts, id)
        try {
            val hash = MessageDigest.getInstance("SHA-256")
            var size = 0L
            context.contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(8192)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        size += count
                        require(size <= maximumSize) { "每张图片不能超过 8 MB。" }
                        hash.update(buffer, 0, count)
                        output.write(buffer, 0, count)
                    }
                }
            } ?: throw IllegalArgumentException("无法读取图片，请重新选择。")
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(target.absolutePath, options)
            val mime = options.outMimeType
            require(options.outWidth > 0 && options.outHeight > 0 && mime != null && mime in supported) {
                "请选择 PNG、JPEG 或 WebP 图片。"
            }
            AttachmentMeta(id, mime, size, hash.digest().hex())
        } catch (error: Exception) {
            target.delete()
            throw error
        }
    }

    suspend fun discard(meta: AttachmentMeta) = withContext(Dispatchers.IO) {
        if (validId.matches(meta.id)) File(drafts, meta.id).delete()
        Unit
    }

    suspend fun adopt(accountId: String, meta: AttachmentMeta) = withContext(Dispatchers.IO) {
        validate(meta)
        val source = File(drafts, meta.id)
        val target = localFile(accountId, meta.id)
        if (target.exists()) {
            verify(target, meta)
            source.delete()
        } else {
            verify(source, meta)
            if (!source.renameTo(target)) throw IOException("无法保存原图。")
        }
    }

    suspend fun upload(accountId: String, meta: AttachmentMeta, token: String) = withContext(Dispatchers.IO) {
        validate(meta)
        val file = localFile(accountId, meta.id)
        verify(file, meta)
        val connection = connection(meta.id, "PUT", token)
        try {
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", meta.mime)
            connection.setFixedLengthStreamingMode(meta.size)
            connection.outputStream.use { output -> file.inputStream().use { it.copyTo(output) } }
            if (connection.responseCode !in 200..299) throw IOException("图片暂时无法同步。")
        } finally {
            connection.disconnect()
        }
    }

    suspend fun preview(accountId: String, meta: AttachmentMeta, token: suspend () -> String): Bitmap = withContext(Dispatchers.IO) {
        validate(meta)
        val target = localFile(accountId, meta.id)
        if (!target.exists()) download(target, meta, token())
        verify(target, meta)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(target.absolutePath, bounds)
        var sample = 1
        while (bounds.outWidth / sample > 1600 || bounds.outHeight / sample > 1600) sample *= 2
        BitmapFactory.decodeFile(target.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: throw IOException("无法显示原图。")
    }

    private fun download(target: File, meta: AttachmentMeta, token: String) {
        val temp = File(target.parentFile, ".download-${UUID.randomUUID()}")
        val connection = connection(meta.id, "GET", token)
        try {
            if (connection.responseCode != 200) throw IOException("暂时无法下载原图。")
            var size = 0L
            connection.inputStream.use { input -> temp.outputStream().use { output ->
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    size += count
                    if (size > maximumSize) throw IOException("图片超过大小限制。")
                    output.write(buffer, 0, count)
                }
            } }
            verify(temp, meta)
            if (!temp.renameTo(target)) throw IOException("无法保存原图。")
        } finally {
            temp.delete()
            connection.disconnect()
        }
    }

    private fun connection(id: String, method: String, token: String): HttpURLConnection {
        require(baseUrl.startsWith("https://")) { "图片连接必须使用 HTTPS。" }
        return (URL("${baseUrl.trimEnd('/')}/v1/attachments/$id").openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = false
            requestMethod = method
            connectTimeout = 12_000
            readTimeout = 90_000
            setRequestProperty("Authorization", "Bearer $token")
        }
    }

    private fun localFile(accountId: String, id: String): File {
        require(validId.matches(id)) { "图片编号无效。" }
        val owner = MessageDigest.getInstance("SHA-256").digest(accountId.toByteArray(Charsets.UTF_8)).hex()
        val folder = File(context.filesDir, "images/$owner")
        if (!folder.exists() && !folder.mkdirs()) throw IOException("无法创建图片目录。")
        return File(folder, id)
    }

    private fun validate(meta: AttachmentMeta) {
        require(validId.matches(meta.id) && validHash.matches(meta.sha256) && meta.mime in supported && meta.size in 1..maximumSize) {
            "图片信息无效。"
        }
    }

    private fun verify(file: File, meta: AttachmentMeta) {
        if (!file.isFile || file.length() != meta.size) throw IOException("本机原图不可用。")
        val hash = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                hash.update(buffer, 0, count)
            }
        }
        if (hash.digest().hex() != meta.sha256) throw IOException("原图校验失败。")
    }

    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
