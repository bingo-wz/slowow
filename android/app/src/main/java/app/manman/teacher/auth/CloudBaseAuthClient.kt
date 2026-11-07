package app.manman.teacher.auth

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import app.manman.teacher.data.readUtf8Limited
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

data class VerificationRequest(val id: String, val existingUser: Boolean, val phone: String)

data class AuthSession(
    val accountId: String,
    val accessToken: String,
    val refreshToken: String,
    val expiresAtMillis: Long,
)

class AuthException(val errorCode: String, message: String) : Exception(message)

interface JsonTransport {
    suspend fun post(path: String, body: JSONObject, bearer: String? = null): JSONObject
}

class CloudBaseTransport(
    private val baseUrl: String,
    private val deviceId: String,
) : JsonTransport {
    override suspend fun post(path: String, body: JSONObject, bearer: String?): JSONObject =
        withContext(Dispatchers.IO) {
            val connection = (URL(baseUrl.trimEnd('/') + path).openConnection() as HttpURLConnection)
            try {
                require(baseUrl.startsWith("https://")) { "身份认证必须使用 HTTPS" }
                connection.instanceFollowRedirects = false
                connection.requestMethod = "POST"
                connection.connectTimeout = 15_000
                connection.readTimeout = 20_000
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.setRequestProperty("Accept", "application/json")
                connection.setRequestProperty("x-device-id", deviceId)
                if (bearer != null) connection.setRequestProperty("Authorization", "Bearer $bearer")
                connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                val responseCode = connection.responseCode
                val response = (if (responseCode in 200..299) connection.inputStream else connection.errorStream)
                    ?.use { it.readUtf8Limited(64 * 1024) }.orEmpty()
                val json = if (response.isBlank()) JSONObject() else JSONObject(response)
                if (responseCode !in 200..299) {
                    val code = json.optString("error", "http_$responseCode")
                    throw AuthException(code, errorMessage(code))
                }
                json
            } catch (error: IOException) {
                throw AuthException("network_error", "网络连接暂时不可用，请稍后重试。")
            } finally {
                connection.disconnect()
            }
        }
}

class CloudBaseAuthClient(private val transport: JsonTransport) {
    suspend fun requestCode(phone: String): VerificationRequest {
        val normalized = normalizePhone(phone)
        val result = transport.post("/auth/v1/verification", JSONObject()
            .put("phone_number", normalized)
            .put("target", "ANY"))
        val id = result.optString("verification_id")
        if (id.isBlank()) throw AuthException("invalid_response", "验证码服务返回异常，请稍后重试。")
        return VerificationRequest(id = id, existingUser = result.optBoolean("is_user", false), phone = normalized)
    }

    suspend fun verify(request: VerificationRequest, code: String, now: Long = System.currentTimeMillis()): AuthSession {
        if (!code.matches(Regex("\\d{6}"))) throw AuthException("invalid_code", "请输入 6 位短信验证码。")
        val verification = transport.post("/auth/v1/verification/verify", JSONObject()
            .put("verification_id", request.id)
            .put("verification_code", code))
        val token = verification.optString("verification_token")
        if (token.isBlank()) throw AuthException("invalid_response", "验证码服务返回异常，请重新获取验证码。")
        val endpoint = if (request.existingUser) "/auth/v1/signin" else "/auth/v1/signup"
        val body = JSONObject().put("verification_token", token)
        if (!request.existingUser) body.put("phone_number", request.phone)
        return parseSession(transport.post(endpoint, body), now)
    }

    suspend fun refresh(session: AuthSession, now: Long = System.currentTimeMillis()): AuthSession =
        parseSession(transport.post("/auth/v1/token", JSONObject()
            .put("grant_type", "refresh_token")
            .put("refresh_token", session.refreshToken)), now)

    suspend fun signOut(session: AuthSession) {
        transport.post("/auth/v1/user/signout", JSONObject(), session.accessToken)
    }

    private fun parseSession(json: JSONObject, now: Long): AuthSession {
        val accountId = json.optString("sub")
        val accessToken = json.optString("access_token")
        val refreshToken = json.optString("refresh_token")
        val expires = json.optLong("expires_in", 0)
        if (accountId.isBlank() || accessToken.isBlank() || refreshToken.isBlank() || expires <= 0) {
            throw AuthException("invalid_response", "登录服务返回异常，请稍后重试。")
        }
        return AuthSession(accountId, accessToken, refreshToken, now + expires * 1_000)
    }
}

fun normalizePhone(input: String): String {
    val digits = input.filterNot { it == ' ' || it == '-' }
    val national = when {
        digits.startsWith("+86") -> digits.removePrefix("+86")
        else -> digits
    }
    if (!national.matches(Regex("1\\d{10}"))) throw AuthException("invalid_phone", "请输入正确的 11 位手机号。")
    return "+86 $national"
}

private fun errorMessage(code: String): String = when (code) {
    "captcha_required" -> "需要额外的人机验证，请稍后重试。"
    "invalid_verification_code", "invalid_argument" -> "验证码不正确或已过期，请重新输入。"
    "resource_exhausted", "too_many_requests" -> "验证码请求过于频繁，请稍后再试。"
    "unauthenticated" -> "登录已过期，请重新登录。"
    "unimplemented", "login_type_disabled" -> "短信登录暂不可用，请联系管理员检查配置。"
    else -> "操作暂未完成，请稍后重试。"
}
