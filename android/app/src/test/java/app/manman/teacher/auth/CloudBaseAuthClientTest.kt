package app.manman.teacher.auth

import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CloudBaseAuthClientTest {
    private class FakeTransport(var existing: Boolean) : JsonTransport {
        val calls = mutableListOf<Pair<String, JSONObject>>()

        override suspend fun post(path: String, body: JSONObject, bearer: String?): JSONObject {
            calls += path to JSONObject(body.toString())
            return when (path) {
                "/auth/v1/verification" -> JSONObject().put("verification_id", "id-1").put("is_user", existing)
                "/auth/v1/verification/verify" -> JSONObject().put("verification_token", "verified")
                "/auth/v1/signin", "/auth/v1/signup", "/auth/v1/token" -> JSONObject()
                    .put("sub", "user-1").put("access_token", "access")
                    .put("refresh_token", "refresh").put("expires_in", 7200)
                else -> JSONObject()
            }
        }
    }

    @Test fun newPhoneUsesRegistrationAfterVerification() = runTest {
        val transport = FakeTransport(existing = false)
        val client = CloudBaseAuthClient(transport)
        val request = client.requestCode("13800138000")
        val session = client.verify(request, "123456", now = 1_000)
        assertEquals(listOf("/auth/v1/verification", "/auth/v1/verification/verify", "/auth/v1/signup"), transport.calls.map { it.first })
        assertEquals("+86 13800138000", transport.calls.last().second.getString("phone_number"))
        assertEquals("user-1", session.accountId)
        assertEquals(7_201_000, session.expiresAtMillis)
    }

    @Test fun existingPhoneUsesLoginAndRefreshUsesRefreshToken() = runTest {
        val transport = FakeTransport(existing = true)
        val client = CloudBaseAuthClient(transport)
        val session = client.verify(client.requestCode("+86 13800138000"), "123456")
        client.refresh(session)
        assertEquals("/auth/v1/signin", transport.calls[2].first)
        assertEquals("refresh_token", transport.calls.last().second.getString("grant_type"))
        assertEquals("refresh", transport.calls.last().second.getString("refresh_token"))
    }

    @Test fun invalidPhoneIsRejectedBeforeNetworkCall() {
        assertThrows(AuthException::class.java) { normalizePhone("123") }
    }
}
