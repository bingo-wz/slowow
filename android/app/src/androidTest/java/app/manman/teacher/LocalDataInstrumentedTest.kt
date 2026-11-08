package app.manman.teacher

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.manman.teacher.data.RemoteRecord
import app.manman.teacher.data.ClassRepository
import app.manman.teacher.data.RecordKind
import app.manman.teacher.data.RecordCodec
import app.manman.teacher.data.WorkTask
import app.manman.teacher.data.MarkingRecord
import app.manman.teacher.data.MarkingStudent
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.time.Instant
import java.time.LocalDate
import org.json.JSONObject

@RunWith(AndroidJUnit4::class)
class LocalDataInstrumentedTest {
    @Test fun taskOrderAndMarkingDeletionAreStoredForSync() = runBlocking {
        val application = ApplicationProvider.getApplicationContext<ManmanApplication>()
        val dao = application.database.records()
        val owner = "ordering-test-${UUID.randomUUID()}"
        val repository = ClassRepository(application.database)
        val now = Instant.parse("2026-11-08T00:00:00Z")
        val today = LocalDate.of(2026, 11, 8)
        listOf("a", "b", "c").forEach { id -> repository.saveTask(owner, WorkTask(id, id, null, null, null, false, now, today)) }
        repository.saveTask(owner, WorkTask("tomorrow", "其他日期", null, null, null, false, now, today.plusDays(1)))
        repository.reorderTasks(owner, today, listOf("c", "a", "b"))
        val sorted = listOf("a", "b", "c").map { RecordCodec.task(dao.find(owner, RecordKind.TASK, it)!!) }
            .sortedBy { it.sortOrder }
        assertEquals(listOf("c", "a", "b"), sorted.map { it.id })
        repository.setTaskDone(owner, "a", true)
        val checked = dao.find(owner, RecordKind.TASK, "a")!!
        assertTrue(checked.dirty)
        assertTrue(RecordCodec.task(checked).done)
        assertEquals(1L, RecordCodec.task(checked).sortOrder)
        try {
            repository.reorderTasks(owner, today, listOf("a", "tomorrow"))
            throw AssertionError("不应允许跨日期排序")
        } catch (_: IllegalArgumentException) {
            assertEquals(1L, RecordCodec.task(dao.find(owner, RecordKind.TASK, "a")!!).sortOrder)
        }
        val marking = MarkingRecord("mark", "虚构事项", "有", "没有", now, listOf(MarkingStudent("s", "虚构学生", true)))
        repository.saveMarking(owner, marking)
        repository.deleteMarking(owner, marking)
        val deleted = dao.find(owner, RecordKind.MARKING, "mark")!!
        assertTrue(deleted.deleted)
        assertTrue(deleted.dirty)
        assertEquals(marking, RecordCodec.marking(deleted))
        assertNull(dao.find("different-$owner", RecordKind.MARKING, "mark"))
    }

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
