package app.manman.teacher

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.manman.teacher.data.ClassRepository
import app.manman.teacher.data.RecordCodec
import app.manman.teacher.data.RecordKind
import app.manman.teacher.domain.Leave
import app.manman.teacher.domain.LeavePeriod
import app.manman.teacher.recognition.ScheduleLessonSuggestion
import app.manman.teacher.recognition.ScheduleSlotSuggestion
import app.manman.teacher.recognition.ScheduleSuggestion
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ScheduleRepositoryInstrumentedTest {
    @Test fun weekdayTimesMergeSafelyAndFailedReplacementKeepsData() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<ManmanApplication>()
        val repository = ClassRepository(app.database)
        val account = "schedule-test-${UUID.randomUUID()}"
        fun time(s: String) = LocalTime.parse(s)
        val suggestion = ScheduleSuggestion(listOf(
            ScheduleSlotSuggestion(1, "第一节", time("09:05"), time("09:45")),
            ScheduleSlotSuggestion(2, "第一节", time("08:35"), time("09:15")),
        ), listOf(
            ScheduleLessonSuggestion(1, "第一节", "语文"),
            ScheduleLessonSuggestion(2, "第一节", "数学"),
        ), emptyList())
        repository.saveRecognizedSchedule(account, suggestion, false)
        val dao = app.database.records()
        var records = dao.snapshot(account).filterNot { it.deleted }
        val slots = records.filter { it.kind == RecordKind.TIME_SLOT }.map(RecordCodec::timeSlot)
        assertEquals(time("09:05"), slots.single { 1 in it.weekdays }.start)
        assertEquals(time("08:35"), slots.single { 2 in it.weekdays }.start)
        repository.saveRecognizedSchedule(account, ScheduleSuggestion(emptyList(), listOf(ScheduleLessonSuggestion(1, "第一节", "音乐")), emptyList()), false)
        records = dao.snapshot(account).filterNot { it.deleted }
        assertEquals(2, records.count { it.kind == RecordKind.LESSON })
        assertEquals("音乐", records.filter { it.kind == RecordKind.LESSON }.map(RecordCodec::lesson).single { it.weekday == 1 }.subject)
        try {
            repository.saveRecognizedSchedule(account, ScheduleSuggestion(emptyList(), listOf(ScheduleLessonSuggestion(1, "不存在的节次", "语文")), emptyList()), true)
            fail("缺少作息时不能替换")
        } catch (_: IllegalArgumentException) { }
        assertEquals(records.toSet(), dao.snapshot(account).filterNot { it.deleted }.toSet())
        try {
            repository.saveRecognizedSchedule(account, ScheduleSuggestion(listOf(
                ScheduleSlotSuggestion(5, "活动一", time("14:35"), time("17:30")),
                ScheduleSlotSuggestion(5, "活动二", time("15:45"), time("16:25")),
            ), emptyList(), emptyList()), false)
            fail("重叠时段需要核对，不能产生重复提醒")
        } catch (_: IllegalArgumentException) { }
        assertEquals(records.toSet(), dao.snapshot(account).filterNot { it.deleted }.toSet())
        try {
            repository.saveRecognizedSchedule(account, ScheduleSuggestion(listOf(ScheduleSlotSuggestion(1, "第一节", time("08:00"), time("08:40"))), emptyList(), emptyList()), false)
            fail("同名节次时间冲突时不能覆盖")
        } catch (_: IllegalArgumentException) { }
        assertEquals(records.toSet(), dao.snapshot(account).filterNot { it.deleted }.toSet())
    }

    @Test fun onlyCancelledLeaveCanBeDeletedAndTombstoneIsQueued() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<ManmanApplication>()
        val repository = ClassRepository(app.database)
        val account = "leave-test-${UUID.randomUUID()}"
        val today = LocalDate.of(2026, 11, 9)
        val leave = Leave("leave", "fictional", today, today, LeavePeriod.ALL_DAY, Instant.parse("2026-11-09T00:00:00Z"))
        repository.saveLeave(account, leave)
        try { repository.deleteCancelledLeave(account, leave.id); fail("未撤销的请假不能删除") } catch (_: IllegalArgumentException) { }
        assertFalse(app.database.records().find(account, RecordKind.LEAVE, leave.id)!!.deleted)
        repository.saveLeave(account, leave.copy(cancelled = true))
        repository.deleteCancelledLeave(account, leave.id)
        val removed = app.database.records().find(account, RecordKind.LEAVE, leave.id)!!
        assertTrue(removed.deleted && removed.dirty)
        assertTrue(RecordCodec.leave(removed).cancelled)
        assertEquals(null, app.database.records().find("other-$account", RecordKind.LEAVE, leave.id))
    }
}
