package app.manman.teacher.domain

import app.manman.teacher.data.RecordCodec
import app.manman.teacher.data.RoutineRule
import app.manman.teacher.data.RoutineVersion
import app.manman.teacher.data.WorkTask
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

class RoutinePlannerTest {
    private val monday = LocalDate.of(2026, 11, 2)
    private val created = Instant.parse("2026-11-02T00:00:00Z")
    private val base = RoutineVersion(monday, "晨检", setOf(1, 3), LocalTime.of(8, 0), LocalTime.of(8, 30))

    @Test fun weekdaysAndEffectiveVersionsPreserveEarlierOccurrences() {
        val paused = base.copy(effectiveFrom = monday.plusWeeks(1), enabled = false)
        val resumed = base.copy(effectiveFrom = monday.plusWeeks(2), title = "早读晨检", weekdays = setOf(2))
        val rule = RoutineRule("r", created, listOf(base, paused, resumed))
        val occurrences = RoutinePlanner.occurrences(rule, monday, monday.plusWeeks(2).plusDays(2))
        assertEquals(listOf(monday, monday.plusDays(2), monday.plusWeeks(2).plusDays(1)), occurrences.map { it.date })
        assertEquals(listOf("晨检", "晨检", "早读晨检"), occurrences.map { it.version.title })
        assertEquals(RoutinePlanner.occurrenceId("r", monday), occurrences.first().id)
        assertEquals(Instant.parse("2026-11-02T00:00:00Z"), occurrences.first().start)
        assertEquals(Instant.parse("2026-11-02T00:30:00Z"), occurrences.first().end)
    }

    @Test fun onlyExpiredCompleteTimeRangesAreAutomaticallyCompleted() {
        val now = Instant.parse("2026-11-02T01:00:00Z")
        val task = WorkTask("a", "晨检", null, created, null, false, created, monday, endAt = created.plusSeconds(1800))
        assertTrue(RoutinePlanner.shouldAutoComplete(task, now))
        assertFalse(RoutinePlanner.shouldAutoComplete(task.copy(endAt = null), now))
        assertFalse(RoutinePlanner.shouldAutoComplete(task.copy(endAt = created), now))
        assertFalse(RoutinePlanner.shouldAutoComplete(task.copy(done = true), now))
        assertFalse(RoutinePlanner.shouldAutoComplete(task.copy(autoCompletionSuppressed = true), now))
        assertFalse(RoutinePlanner.shouldAutoComplete(task.copy(timingNeedsReview = true), now))
        assertFalse(RoutinePlanner.shouldAutoComplete(task.copy(endAt = now.plusSeconds(1)), now))
    }

    @Test fun olderClientChangesCannotSilentlyUseAnOutdatedTimeRange() {
        val original = WorkTask("a", "晨检", null, created, null, true, created, monday,
            endAt = created.plusSeconds(1800), routineId = "r", routineDate = monday, autoCompleted = true)
        val metadata = RecordCodec.metadataFor(original)
        val changedByOldClient = original.copy(title = "改过的晨检", dueAt = created.plusSeconds(3600), done = false,
            endAt = null, routineId = null, routineDate = null, autoCompleted = false)
        val merged = RecordCodec.withMetadata(changedByOldClient, metadata)
        assertTrue(merged.timingNeedsReview)
        assertTrue(merged.routineException)
        assertTrue(merged.autoCompletionSuppressed)
        assertNull(merged.endAt)
        assertFalse(RoutinePlanner.shouldAutoComplete(merged, created.plusSeconds(7200)))
    }

    @Test fun timedTasksAreSortedBeforeUntimedTasks() {
        val untimed = WorkTask("a", "无时间", null, null, null, false, created, monday, 0)
        val late = untimed.copy(id = "b", title = "晚", dueAt = created.plusSeconds(3600), sortOrder = -2)
        val early = untimed.copy(id = "c", title = "早", dueAt = created, sortOrder = 4)
        assertEquals(listOf("c", "b", "a"), TaskOrdering.sorted(listOf(untimed, late, early)).map { it.id })
    }
}
