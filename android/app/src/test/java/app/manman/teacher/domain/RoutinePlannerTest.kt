package app.manman.teacher.domain

import app.manman.teacher.data.RecordCodec
import app.manman.teacher.data.LocalRecord
import app.manman.teacher.data.RecordKind
import app.manman.teacher.data.RoutineRule
import app.manman.teacher.data.RoutineTime
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
import org.json.JSONObject

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
        val untimed = WorkTask("a", "无时间", null, null, null, false, created, monday)
        val late = untimed.copy(id = "b", title = "晚", dueAt = created.plusSeconds(3600))
        val early = untimed.copy(id = "c", title = "早", dueAt = created)
        assertEquals(listOf("c", "b", "a"), TaskOrdering.sorted(listOf(untimed, late, early)).map { it.id })
        val manuallyLateFirst = late.copy(sortOrder = 0)
        val manuallyEarlySecond = early.copy(sortOrder = 1)
        val manuallyUntimedLast = untimed.copy(sortOrder = 2)
        assertEquals(listOf("b", "c", "a"), TaskOrdering.sorted(listOf(manuallyEarlySecond, manuallyUntimedLast, manuallyLateFirst)).map { it.id })
    }

    @Test fun weekdaysCanUseDifferentRangesOrNoRange() {
        val times = mapOf(
            1 to RoutineTime(LocalTime.of(8, 15), LocalTime.of(8, 45)),
            2 to RoutineTime(LocalTime.of(9, 5), LocalTime.of(9, 50)),
            3 to RoutineTime(),
        )
        val version = RoutineVersion(monday, "每周事项", setOf(1, 2, 3), weekdayTimes = times)
        val rule = RoutineRule("weekday-rule", created, listOf(version))
        val occurrences = RoutinePlanner.occurrences(rule, monday, monday.plusDays(2))
        assertEquals(Instant.parse("2026-11-02T00:15:00Z"), occurrences[0].start)
        assertEquals(Instant.parse("2026-11-02T00:45:00Z"), occurrences[0].end)
        assertEquals(Instant.parse("2026-11-03T01:05:00Z"), occurrences[1].start)
        assertEquals(Instant.parse("2026-11-03T01:50:00Z"), occurrences[1].end)
        assertNull(occurrences[2].start)
        assertNull(occurrences[2].end)
        val payload = RecordCodec.routine(rule)
        assertEquals("routine_v2", JSONObject(payload).getString("type"))
        val decoded = RecordCodec.routine(LocalRecord("", RecordKind.SETTINGS, "", payload, updatedAt = 0))
        assertEquals(rule, decoded)
    }

    @Test fun previousSharedTimeRemainsReadableAndUsesVersionOne() {
        val rule = RoutineRule("old-rule", created, listOf(base))
        val payload = RecordCodec.routine(rule)
        assertEquals("routine_v1", JSONObject(payload).getString("type"))
        val decoded = RecordCodec.routine(LocalRecord("", RecordKind.SETTINGS, "", payload, updatedAt = 0))
        assertEquals(RoutineTime(LocalTime.of(8, 0), LocalTime.of(8, 30)), decoded.current.timeFor(1))
        assertEquals(decoded.current.timeFor(1), decoded.current.timeFor(3))
    }
}
