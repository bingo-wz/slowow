package app.manman.teacher

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.manman.teacher.data.RemoteRecord
import app.manman.teacher.data.ClassRepository
import app.manman.teacher.data.RecordKind
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import org.json.JSONObject

@RunWith(AndroidJUnit4::class)
class LocalDataInstrumentedTest {
    @Test fun offlineEditsAndConflictsDoNotLoseLocalData() = runBlocking {
        val application = ApplicationProvider.getApplicationContext<ManmanApplication>()
        val dao = application.database.records()
        val owner = "test-${UUID.randomUUID()}"
        dao.saveLocal(owner, "student", "one", """{"name":"最初内容"}""")
        val sent = dao.find(owner, "student", "one")!!
        dao.saveLocal(owner, "student", "one", """{"name":"离线新内容"}""")
        dao.acknowledge(owner, sent, 1)
        val afterAck = dao.find(owner, "student", "one")!!
        assertTrue(afterAck.dirty)
        assertEquals(1, afterAck.serverVersion)
        assertTrue(afterAck.payload.contains("离线新内容"))

        dao.applyRemote(owner, listOf(RemoteRecord("student", "one", """{"name":"另一设备内容"}""", false, 2, 2, 1)), 2)
        val conflict = dao.find(owner, "student", "one")!!
        assertTrue(conflict.payload.contains("离线新内容"))
        assertNotNull(conflict.conflictVersion)
        assertTrue(conflict.conflictPayload!!.contains("另一设备内容"))
        assertEquals(null, dao.find("different-$owner", "student", "one"))

        dao.resolveConflict(owner, "student", "one", keepLocal = true)
        val resolved = dao.find(owner, "student", "one")!!
        assertTrue(resolved.dirty)
        assertEquals(2, resolved.serverVersion)
        assertFalse(resolved.operationId == sent.operationId)
        assertEquals(null, resolved.conflictVersion)

        val repository = ClassRepository(application.database)
        repository.saveLessonReminder(owner, 5)
        repository.saveLunchReminder(owner, true)
        val settings = JSONObject(dao.find(owner, RecordKind.SETTINGS, "reminders")!!.payload)
        assertEquals(5, settings.getInt("lessonMinutes"))
        assertTrue(settings.getBoolean("lunchEnabled"))
    }
}
