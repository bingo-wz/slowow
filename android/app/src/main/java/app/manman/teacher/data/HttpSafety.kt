package app.manman.teacher.data

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

fun InputStream.readUtf8Limited(limit: Int): String {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    var total = 0
    while (true) {
        val count = read(buffer)
        if (count < 0) break
        total += count
        if (total > limit) throw IOException("响应内容超出允许大小")
        output.write(buffer, 0, count)
    }
    return output.toString(Charsets.UTF_8.name())
}
