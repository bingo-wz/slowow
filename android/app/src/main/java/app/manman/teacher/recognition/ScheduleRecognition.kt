package app.manman.teacher.recognition

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import app.manman.teacher.auth.SessionProvider
import app.manman.teacher.data.readUtf8Limited
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalTime
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class ScheduleSlotSuggestion(val weekday: Int, val title: String, val start: LocalTime?, val end: LocalTime?)
data class ScheduleLessonSuggestion(val weekday: Int, val slot: String, val subject: String)
data class ScheduleSuggestion(val slots: List<ScheduleSlotSuggestion>, val lessons: List<ScheduleLessonSuggestion>, val warnings: List<String>)

enum class SchedulePhase { PREPARING, CONNECTING, RECONNECTING, UPLOADING, WAITING, RECEIVING }
data class ScheduleProgressUpdate(val phase: SchedulePhase, val current: Int = 0, val total: Int = 0)

class ScheduleRecognition(private val baseUrl: String, private val auth: SessionProvider,
    private val openConnection: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection }) {
    suspend fun recognize(context: Context, account: String, uris: List<Uri>, onProgress: (ScheduleProgressUpdate) -> Unit): ScheduleSuggestion {
        require(baseUrl.startsWith("https://")) { "识别连接必须使用 HTTPS。" }
        require(uris.size in 1..2 && uris.distinct().size == uris.size) { "请选择一至两张不同的图片。" }
        val payload = withContext(Dispatchers.IO) {
            val files = JSONArray()
            var total = 0
            uris.forEachIndexed { index, uri ->
                coroutineContext.ensureActive()
                onProgress(ScheduleProgressUpdate(SchedulePhase.PREPARING, index + 1, uris.size))
                val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(32 * 1024)
                    while (true) {
                        coroutineContext.ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        require(output.size() + count <= 8 * 1024 * 1024) { "每张图片不能超过 8 MB。" }
                        output.write(buffer, 0, count)
                    }
                    output.toByteArray()
                } ?: throw IllegalArgumentException("无法读取图片，请重新选择。")
                total += bytes.size
                require(bytes.isNotEmpty() && total <= 12 * 1024 * 1024) { "图片合计不能超过 12 MB。" }
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                val mime = options.outMimeType
                require(options.outWidth > 0 && options.outHeight > 0 && mime in setOf("image/jpeg", "image/png", "image/webp")) {
                    "请选择 JPEG、PNG 或 WebP 图片。"
                }
                files.put(JSONObject().put("kind", mime).put("data", Base64.encodeToString(bytes, Base64.NO_WRAP)))
            }
            JSONObject().put("confirmed", true).put("files", files).toString().toByteArray(Charsets.UTF_8)
        }
        onProgress(ScheduleProgressUpdate(SchedulePhase.CONNECTING))
        val token = auth.currentAccessToken(account)
        return send(payload, token, onProgress)
    }

    private suspend fun send(payload: ByteArray, token: String, onProgress: (ScheduleProgressUpdate) -> Unit): ScheduleSuggestion =
        suspendCancellableCoroutine { continuation ->
            val active = AtomicReference<HttpURLConnection?>(null)
            val task = CoroutineScope(Dispatchers.IO).launch {
                try {
                    var retries = 0
                    while (true) {
                        ensureActive()
                        val connection = openConnection(URL("${baseUrl.trimEnd('/')}/v1/recognize/schedule"))
                        active.set(connection)
                        try {
                            ensureActive()
                            connection.instanceFollowRedirects = false
                            connection.requestMethod = "POST"
                            connection.connectTimeout = 15_000
                            connection.readTimeout = 30_000
                            connection.doOutput = true
                            connection.setFixedLengthStreamingMode(payload.size)
                            connection.setRequestProperty("Authorization", "Bearer $token")
                            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                            connection.setRequestProperty("Accept", "text/event-stream")
                            val stream = try { connection.outputStream } catch (error: IOException) {
                                if (retries >= 2) throw error
                                retries++
                                connection.disconnect()
                                if (continuation.isActive) onProgress(ScheduleProgressUpdate(SchedulePhase.RECONNECTING, retries, 2))
                                delay(400L * retries)
                                continue
                            }
                            stream.use { output ->
                                var written = 0
                                var reported = -1
                                while (written < payload.size) {
                                    ensureActive()
                                    val count = minOf(64 * 1024, payload.size - written)
                                    output.write(payload, written, count)
                                    written += count
                                    val percentage = (written.toLong() * 100 / payload.size).toInt()
                                    if (percentage >= reported + 2 || percentage == 100) {
                                        reported = percentage
                                        if (continuation.isActive) onProgress(ScheduleProgressUpdate(SchedulePhase.UPLOADING, percentage, 100))
                                    }
                                }
                            }
                            if (continuation.isActive) onProgress(ScheduleProgressUpdate(SchedulePhase.WAITING))
                            val code = connection.responseCode
                            if (code !in 200..299) {
                                val raw = connection.errorStream?.use { it.readUtf8Limited(512 * 1024) }.orEmpty()
                                val message = try { JSONObject(raw).optString("message") } catch (_: Exception) { "" }
                                throw IllegalArgumentException(message.ifBlank { "识别暂不可用，请稍后重试。" }.take(120))
                            }
                            require(connection.contentType?.startsWith("text/event-stream") == true) { "识别服务需要更新，请稍后重试。" }
                            val result = connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                                readScheduleEvents(reader) { phase ->
                                    if (continuation.isActive) onProgress(ScheduleProgressUpdate(phase))
                                }
                            }
                            if (continuation.isActive) continuation.resume(result)
                            break
                        } finally {
                            active.compareAndSet(connection, null)
                            connection.disconnect()
                        }
                    }
                } catch (error: CancellationException) {
                    if (continuation.isActive) continuation.cancel(error)
                } catch (error: Exception) {
                    val safe = if (error is IOException) IllegalArgumentException("识别连接暂时不可用，请检查网络后重试。") else error
                    if (continuation.isActive) continuation.resumeWithException(safe)
                }
            }
            continuation.invokeOnCancellation {
                task.cancel()
                val connection = active.getAndSet(null)
                if (connection != null) CoroutineScope(Dispatchers.IO).launch { connection.disconnect() }
            }
        }
}

internal fun readScheduleEvents(reader: BufferedReader, onPhase: (SchedulePhase) -> Unit): ScheduleSuggestion {
    var total = 0
    while (true) {
        val line = reader.readLine() ?: break
        total += line.length
        require(line.length <= 512 * 1024 && total <= 2 * 1024 * 1024) { "识别结果过大，请重试。" }
        if (!line.startsWith("data:")) continue
        val event = try { JSONObject(line.removePrefix("data:").trim()) } catch (_: Exception) {
            throw IllegalArgumentException("识别结果格式异常，请重试。")
        }
        when (event.optString("type")) {
            "phase" -> when (event.optString("phase")) {
                "received" -> onPhase(SchedulePhase.WAITING)
                "generating" -> onPhase(SchedulePhase.RECEIVING)
            }
            "error" -> throw IllegalArgumentException(event.optString("message", "识别暂不可用，请稍后重试。").take(120))
            "result" -> return parseScheduleSuggestion(event.getJSONObject("result"))
        }
    }
    throw IllegalArgumentException("识别连接中断，请重新识别。")
}

internal fun parseScheduleSuggestion(result: JSONObject): ScheduleSuggestion {
    val slots = result.optJSONArray("slots") ?: JSONArray()
    val lessons = result.optJSONArray("lessons") ?: JSONArray()
    val warnings = result.optJSONArray("warnings") ?: JSONArray()
    require(slots.length() <= 200 && lessons.length() <= 200 && warnings.length() <= 41) { "识别结果过多，请重试。" }
    fun time(value: JSONObject, key: String): LocalTime? = try {
        if (value.isNull(key)) null else LocalTime.parse(value.getString(key))
    } catch (_: Exception) { null }
    return ScheduleSuggestion((0 until slots.length()).map { index ->
        val item = slots.getJSONObject(index)
        ScheduleSlotSuggestion(item.optInt("weekday", 0), item.optString("title", ""), time(item, "start"), time(item, "end"))
    }, (0 until lessons.length()).map { index ->
        val item = lessons.getJSONObject(index)
        ScheduleLessonSuggestion(item.optInt("weekday", 0), item.optString("slot", ""), item.optString("subject", ""))
    }, (0 until warnings.length()).map { warnings.optString(it).take(300) })
}
