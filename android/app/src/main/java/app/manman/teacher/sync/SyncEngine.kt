package app.manman.teacher.sync

import app.manman.teacher.auth.AuthException
import app.manman.teacher.auth.SessionProvider
import app.manman.teacher.data.LocalRecord
import app.manman.teacher.data.RecordDao
import app.manman.teacher.data.RemoteRecord
import app.manman.teacher.data.RecordKind
import app.manman.teacher.data.readUtf8Limited
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

enum class SyncPhase { IDLE, SYNCING, WAITING, SIGN_IN_REQUIRED }
enum class SyncResult { SUCCESS, RETRY, SIGNED_OUT }

class SyncEngine(
    private val transport: SyncTransport,
    private val records: RecordDao,
    private val auth: SessionProvider,
    private val uploadAttachment: suspend (String, LocalRecord, String) -> Unit = { _, _, _ -> throw IOException("图片上传不可用") },
) {
    private val syncMutex = Mutex()
    private val mutablePhase = MutableStateFlow(SyncPhase.IDLE)
    val phase: StateFlow<SyncPhase> = mutablePhase

    suspend fun sync(accountId: String): SyncResult = syncMutex.withLock {
        auth.restore()
        if (auth.session.value?.accountId != accountId) return@withLock SyncResult.SIGNED_OUT
        mutablePhase.value = SyncPhase.SYNCING
        try {
            var processed = 0
            while (processed < 5_000) {
                val pending = records.pending(accountId)
                if (pending.isEmpty()) break
                for (record in pending) {
                    if (auth.session.value?.accountId != accountId) {
                        mutablePhase.value = SyncPhase.IDLE
                        return@withLock SyncResult.SIGNED_OUT
                    }
                    val token = auth.currentAccessToken(accountId)
                    if (record.kind == RecordKind.ATTACHMENT) uploadAttachment(accountId, record, token)
                    val response = transport.request("POST", "/v1/operations", token, operation(record))
                    when (response.code) {
                        in 200..299 -> records.acknowledge(accountId, record, parseRecord(response.json))
                        409 -> {
                            val remote = response.json.optJSONObject("current")
                                ?: throw IOException("同步冲突需要重新核对")
                            records.saveConflict(accountId, record.kind, record.id, parseRemote(remote))
                        }
                        401 -> throw AuthException("unauthenticated", "登录已过期，请重新登录。")
                        else -> throw IOException("云端暂时不可用")
                    }
                    processed++
                }
            }
            var cursor = records.cursor(accountId)?.sequence ?: 0
            while (true) {
                if (auth.session.value?.accountId != accountId) {
                    mutablePhase.value = SyncPhase.IDLE
                    return@withLock SyncResult.SIGNED_OUT
                }
                val response = transport.request("GET", "/v1/records?after=$cursor&limit=200", auth.currentAccessToken(accountId), null)
                if (response.code == 401) throw AuthException("unauthenticated", "登录已过期，请重新登录。")
                if (response.code !in 200..299) throw IOException("云端暂时不可用")
                val items = response.json.getJSONArray("records")
                val page = (0 until items.length()).map { parseRemote(items.getJSONObject(it)) }
                val next = response.json.getLong("nextCursor")
                if (next < cursor || (page.isNotEmpty() && next <= cursor)) throw IOException("同步位置无效")
                records.applyRemote(accountId, page, next)
                cursor = next
                if (page.size < 200) break
            }
            if (records.pending(accountId, 1).isNotEmpty()) {
                mutablePhase.value = SyncPhase.WAITING
                SyncResult.RETRY
            } else {
                mutablePhase.value = SyncPhase.IDLE
                SyncResult.SUCCESS
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: AuthException) {
            if (error.errorCode == "account_changed") {
                mutablePhase.value = SyncPhase.IDLE
                return@withLock SyncResult.SIGNED_OUT
            }
            mutablePhase.value = if (error.errorCode in setOf("unauthenticated", "invalid_grant", "not_signed_in")) SyncPhase.SIGN_IN_REQUIRED else SyncPhase.WAITING
            if (mutablePhase.value == SyncPhase.SIGN_IN_REQUIRED) SyncResult.SIGNED_OUT else SyncResult.RETRY
        } catch (_: Exception) {
            mutablePhase.value = SyncPhase.WAITING
            SyncResult.RETRY
        }
    }

    private fun operation(record: LocalRecord): JSONObject = JSONObject()
        .put("operationId", record.operationId)
        .put("kind", record.kind)
        .put("id", record.id)
        .put("baseVersion", record.serverVersion)
        .put("deleted", record.deleted)
        .put("payload", JSONObject(record.payload))

    private fun parseRecord(json: JSONObject): Long = json.getLong("version").also { require(it > 0) }

    private fun parseRemote(json: JSONObject) = RemoteRecord(
        kind = json.getString("kind"),
        id = json.getString("id"),
        payload = json.getJSONObject("payload").toString(),
        deleted = json.getBoolean("deleted"),
        version = json.getLong("version"),
        sequence = json.getLong("sequence"),
        updatedAt = json.getLong("updatedAt"),
    )

}

data class SyncResponse(val code: Int, val json: JSONObject)

interface SyncTransport {
    suspend fun request(method: String, path: String, token: String, body: JSONObject?): SyncResponse
}

class HttpsSyncTransport(private val baseUrl: String) : SyncTransport {
    override suspend fun request(method: String, path: String, token: String, body: JSONObject?): SyncResponse = withContext(Dispatchers.IO) {
        require(baseUrl.startsWith("https://")) { "同步连接必须使用 HTTPS" }
        val connection = URL(baseUrl.trimEnd('/') + path).openConnection() as HttpURLConnection
        try {
            connection.instanceFollowRedirects = false
            connection.requestMethod = method
            connection.connectTimeout = 12_000
            connection.readTimeout = 20_000
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Authorization", "Bearer $token")
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            val code = connection.responseCode
            val raw = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.use { it.readUtf8Limited(16 * 1024 * 1024) }.orEmpty()
            SyncResponse(code, if (raw.isBlank()) JSONObject() else JSONObject(raw))
        } finally {
            connection.disconnect()
        }
    }
}
