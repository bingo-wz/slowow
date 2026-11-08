package app.manman.teacher.recognition

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

data class NoticeSuggestionItem(val title: String, val date: LocalDate?, val time: LocalTime?, val evidence: String, val uncertain: Boolean, val consumed: Boolean = false)
data class NoticeSuggestion(val noticeId: String, val items: List<NoticeSuggestionItem>, val warnings: List<String>)

class RecognitionClient(private val baseUrl: String, private val auth: AuthRepository) {
    suspend fun bind(account: String, code: String) {
        require(code.matches(Regex("[0-9]{8}"))) { "请输入 8 位授权码。" }
        request(account, "claim", JSONObject().put("code", code))
    }

    suspend fun notice(account: String, noticeId: String, text: String, images: List<String>): NoticeSuggestion {
        val result = request(account, "notice", JSONObject().put("confirmed", true).put("imageIds", JSONArray(images))
            .put("text", text))
        val items = result.optJSONArray("items") ?: JSONArray()
        return NoticeSuggestion(noticeId, (0 until items.length()).map { index ->
            val item = items.getJSONObject(index)
            val date = if (item.isNull("date")) null else LocalDate.parse(item.getString("date"))
            val time = if (item.isNull("time")) null else LocalTime.parse(item.getString("time"))
            NoticeSuggestionItem(item.getString("title"), date, time, item.optString("evidence"), item.optBoolean("uncertain", true))
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
