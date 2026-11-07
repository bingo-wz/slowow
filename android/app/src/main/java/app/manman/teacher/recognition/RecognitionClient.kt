package app.manman.teacher.recognition

import android.content.Context
import android.net.Uri
import android.util.Base64
import app.manman.teacher.auth.AuthRepository
import app.manman.teacher.data.readUtf8Limited
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.time.LocalTime

data class MenuSuggestionDay(val day: LocalDate, val dishes: List<String>)
data class MenuSuggestion(val start: LocalDate, val days: List<MenuSuggestionDay>, val warnings: List<String>)
data class NoticeSuggestionItem(val title: String, val date: LocalDate?, val time: LocalTime?, val evidence: String, val uncertain: Boolean, val consumed: Boolean = false)
data class NoticeSuggestion(val noticeId: String, val items: List<NoticeSuggestionItem>, val warnings: List<String>)
data class ScheduleSlotSuggestion(val weekday: Int, val title: String, val start: LocalTime?, val end: LocalTime?)
data class ScheduleLessonSuggestion(val weekday: Int, val slot: String, val subject: String)
data class ScheduleSuggestion(val slots: List<ScheduleSlotSuggestion>, val lessons: List<ScheduleLessonSuggestion>, val warnings: List<String>)

class RecognitionClient(private val baseUrl: String, private val auth: AuthRepository) {
    suspend fun bind(account: String, code: String) {
        require(code.matches(Regex("[0-9]{8}"))) { "请输入 8 位授权码。" }
        request(account, "claim", JSONObject().put("code", code))
    }

    suspend fun menu(account: String, start: LocalDate, imageId: String): MenuSuggestion {
        val result = request(account, "menu", JSONObject().put("confirmed", true).put("imageIds", JSONArray(listOf(imageId)))
            .put("text", "").put("weekStart", start.toString()))
        val days = result.optJSONArray("days") ?: JSONArray()
        return MenuSuggestion(start, (0 until days.length()).map { index ->
            val day = days.getJSONObject(index)
            val dishes = day.getJSONArray("dishes")
            MenuSuggestionDay(LocalDate.parse(day.getString("date")), (0 until dishes.length()).map(dishes::getString))
        }, result.strings("warnings"))
    }

    suspend fun notice(account: String, noticeId: String, text: String, images: List<String>): NoticeSuggestion {
        val result = request(account, "notice", JSONObject().put("confirmed", true).put("imageIds", JSONArray(images))
            .put("text", text).put("weekStart", ""))
        val items = result.optJSONArray("items") ?: JSONArray()
        return NoticeSuggestion(noticeId, (0 until items.length()).map { index ->
            val item = items.getJSONObject(index)
            val date = if (item.isNull("date")) null else LocalDate.parse(item.getString("date"))
            val time = if (item.isNull("time")) null else LocalTime.parse(item.getString("time"))
            NoticeSuggestionItem(item.getString("title"), date, time, item.optString("evidence"), item.optBoolean("uncertain", true))
        }, result.strings("warnings"))
    }

    suspend fun schedule(context: Context, account: String, uris: List<Uri>, pdfRotation: Int): ScheduleSuggestion {
        val files = ScheduleDocuments.read(context, uris, pdfRotation)
        val array = JSONArray()
        files.forEach { file -> array.put(JSONObject().put("kind", file.kind).put("data", Base64.encodeToString(file.data, Base64.NO_WRAP))) }
        val result = request(account, "schedule", JSONObject().put("confirmed", true).put("files", array))
        val slots = result.optJSONArray("slots") ?: JSONArray()
        val lessons = result.optJSONArray("lessons") ?: JSONArray()
        return ScheduleSuggestion((0 until slots.length()).map { index ->
            val item = slots.getJSONObject(index)
            ScheduleSlotSuggestion(item.getInt("weekday"), item.getString("title"),
                if (item.isNull("start")) null else LocalTime.parse(item.getString("start")),
                if (item.isNull("end")) null else LocalTime.parse(item.getString("end")))
        }, (0 until lessons.length()).map { index ->
            val item = lessons.getJSONObject(index)
            ScheduleLessonSuggestion(item.getInt("weekday"), item.getString("slot"), item.getString("subject"))
        }, result.strings("warnings"))
    }

    private suspend fun request(account: String, kind: String, body: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        require(baseUrl.startsWith("https://")) { "识别连接必须使用 HTTPS。" }
        val token = auth.currentAccessToken(account)
        val connection = URL("${baseUrl.trimEnd('/')}/v1/recognize/$kind").openConnection() as HttpURLConnection
        try {
            connection.instanceFollowRedirects = false
            connection.requestMethod = "POST"
            connection.connectTimeout = 15_000
            connection.readTimeout = 150_000
            connection.doOutput = true
            connection.setRequestProperty("Authorization", "Bearer $token")
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val raw = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.use { it.readUtf8Limited(512 * 1024) }.orEmpty()
            val json = if (raw.isBlank()) JSONObject() else JSONObject(raw)
            if (code !in 200..299) throw IllegalArgumentException(json.optString("message", "识别暂不可用，请稍后重试。").take(120))
            json
        } catch (error: IOException) {
            throw IllegalArgumentException("识别连接暂时不可用，请检查网络后重试。")
        } finally {
            connection.disconnect()
        }
    }

    private fun JSONObject.strings(key: String): List<String> {
        val values = optJSONArray(key) ?: return emptyList()
        return (0 until values.length()).map(values::getString)
    }
}
