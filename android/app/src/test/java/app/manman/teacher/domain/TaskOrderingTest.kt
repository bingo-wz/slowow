package app.manman.teacher.domain

import app.manman.teacher.data.LocalRecord
import app.manman.teacher.data.RecordCodec
import app.manman.teacher.data.RecordKind
import app.manman.teacher.data.WorkTask
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

class TaskOrderingTest {
    private val now = Instant.parse("2026-11-08T00:00:00Z")

    @Test fun olderTaskWithoutSortOrderRemainsReadable() {
        val record = LocalRecord("test", RecordKind.TASK, "old", """{"title":"旧待办","done":false,"createdAt":"2026-11-08T00:00:00Z"}""", updatedAt = 0)
        val task = RecordCodec.task(record)
        assertNull(task.sortOrder)
        assertNull(TaskOrdering.date(task))
        assertEquals(task, RecordCodec.task(record.copy(payload = RecordCodec.task(task))))
    }

    @Test fun datesAndManualOrderAreIndependentOfCompletion() {
        val today = LocalDate.of(2026, 11, 8)
        val a = WorkTask("a", "甲", null, null, null, false, now, today, 1)
        val b = WorkTask("b", "乙", null, null, null, true, now, today, 0)
        val newTask = WorkTask("c", "丙", null, null, null, false, now, today)
        assertEquals(listOf("b", "a", "c"), TaskOrdering.sorted(listOf(a, newTask, b)).map(WorkTask::id))
        val late = a.copy(dueAt = Instant.parse("2026-11-08T16:00:00Z"))
        assertEquals(today.plusDays(1), TaskOrdering.date(late))
    }
}
