package app.manman.teacher

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.manman.teacher.auth.AuthSession
import app.manman.teacher.auth.SessionProvider
import app.manman.teacher.data.RecordCodec
import app.manman.teacher.data.RecordKind
import app.manman.teacher.domain.Student
import app.manman.teacher.sync.SyncEngine
import app.manman.teacher.sync.SyncResponse
import app.manman.teacher.sync.SyncResult
import app.manman.teacher.sync.SyncTransport
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class SyncEngineInstrumentedTest {
    private class FakeSession(account: String) : SessionProvider {
        val current = MutableStateFlow<AuthSession?>(AuthSession(account, "fake", "fake", Long.MAX_VALUE))
        override val session: StateFlow<AuthSession?> = current
        override suspend fun restore() = Unit
        override suspend fun currentAccessToken(accountId: String): String {
            check(session.value?.accountId == accountId)
            return "fake"
        }
    }

    private class FakeServer : SyncTransport {
        var record: JSONObject? = null
        var operationId: String? = null
        var loseFirstResponse = true
        var calls = 0

        override suspend fun request(method: String, path: String, token: String, body: JSONObject?): SyncResponse {
            calls++
            if (method == "POST") {
                val operation = requireNotNull(body)
                val id = operation.getString("operationId")
                if (id == operationId) return SyncResponse(200, JSONObject(record.toString()))
                val existing = record
                if (existing != null && operation.getLong("baseVersion") != existing.getLong("version")) {
                    return SyncResponse(409, JSONObject().put("current", JSONObject(existing.toString())))
                }
                val next = (existing?.getLong("version") ?: 0L) + 1L
                record = JSONObject().put("kind", operation.getString("kind")).put("id", operation.getString("id"))
                    .put("payload", operation.getJSONObject("payload")).put("deleted", operation.getBoolean("deleted"))
                    .put("version", next).put("sequence", next).put("updatedAt", 1L)
                operationId = id
                if (loseFirstResponse) {
                    loseFirstResponse = false
                    throw IOException("模拟请求成功后连接中断")
                }
                return SyncResponse(200, JSONObject(record.toString()))
            }
            val after = path.substringAfter("after=").substringBefore('&').toLong()
            val currentRecord = record
            val list = JSONArray()
            if (currentRecord != null && currentRecord.getLong("sequence") > after) list.put(JSONObject(currentRecord.toString()))
            val next = if (list.length() == 0) after else list.getJSONObject(0).getLong("sequence")
            return SyncResponse(200, JSONObject().put("records", list).put("nextCursor", next))
        }
    }

    @Test fun failedResponseRetriesSafelyAndConflictKeepsBothCopies() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<ManmanApplication>()
        val dao = app.database.records()
        val owner = "sync-test-${UUID.randomUUID()}"
        val session = FakeSession(owner)
        val server = FakeServer()
        val engine = SyncEngine(server, dao, session)
        dao.saveLocal(owner, "student", "one", """{"name":"本机姓名"}""")
        assertEquals(SyncResult.RETRY, engine.sync(owner))
        assertTrue(dao.find(owner, "student", "one")!!.dirty)
        assertEquals(SyncResult.SUCCESS, engine.sync(owner))
        assertFalse(dao.find(owner, "student", "one")!!.dirty)

        dao.saveLocal(owner, "student", "one", """{"name":"离线修改"}""")
        server.record = JSONObject(server.record.toString()).put("version", 2).put("sequence", 2)
            .put("payload", JSONObject().put("name", "另一设备修改"))
        assertEquals(SyncResult.SUCCESS, engine.sync(owner))
        val conflict = dao.find(owner, "student", "one")!!
        assertTrue(conflict.payload.contains("离线修改"))
        assertTrue(conflict.conflictPayload!!.contains("另一设备修改"))
        assertNotNull(conflict.conflictVersion)

        val callsBefore = server.calls
        session.current.value = AuthSession("other-account", "fake", "fake", Long.MAX_VALUE)
        assertEquals(SyncResult.SIGNED_OUT, engine.sync(owner))
        assertEquals(callsBefore, server.calls)
    }

    @Test fun recurringLunchDaysSurviveUploadAndDownload() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<ManmanApplication>()
        val dao = app.database.records()
        val owner = "weekly-sync-test-${UUID.randomUUID()}"
        val server = FakeServer().apply { loseFirstResponse = false }
        val engine = SyncEngine(server, dao, FakeSession(owner))
        val weekly = Student("one", "小禾", schoolLunchDays = setOf(1, 2, 3, 6, 7))
        dao.saveLocal(owner, RecordKind.STUDENT, weekly.id, RecordCodec.student(weekly))
        assertEquals(SyncResult.SUCCESS, engine.sync(owner))
        val saved = requireNotNull(dao.find(owner, RecordKind.STUDENT, weekly.id))
        assertFalse(saved.dirty)
        assertEquals(weekly.schoolLunchDays, RecordCodec.student(saved).schoolLunchDays)
        val remoteDays = requireNotNull(server.record).getJSONObject("payload").getJSONArray("schoolLunchDays")
        assertEquals(listOf(1, 2, 3, 6, 7), (0 until remoteDays.length()).map(remoteDays::getInt))
    }
}
