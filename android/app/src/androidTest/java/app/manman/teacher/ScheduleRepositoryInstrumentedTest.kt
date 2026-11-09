package app.manman.teacher

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.manman.teacher.data.ClassRepository
import app.manman.teacher.data.RecordCodec
import app.manman.teacher.data.RecordKind
import app.manman.teacher.data.Lesson
import app.manman.teacher.data.ScheduleKind
import app.manman.teacher.data.ScheduleProfile
import app.manman.teacher.data.TimeSlot
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
    @Test fun metadataAndSpansSurviveSyncEncodingAndRecognitionMerge() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<ManmanApplication>()
        val repository = ClassRepository(app.database)
        val account = "schedule-metadata-${UUID.randomUUID()}"
        val first = TimeSlot("first", "第一节", LocalTime.parse("08:00"), LocalTime.parse("08:40"), setOf(1))
        val last = TimeSlot("last", "第二节", LocalTime.parse("08:50"), LocalTime.parse("09:30"), setOf(1))
        repository.saveTimeSlot(account, first)
        repository.saveTimeSlot(account, last)
        val lesson = Lesson("lesson", 1, first.id, "虚构课程", ScheduleKind.DUTY, "测试老师", "测试班", "测试教室", "协同",
            "测试备注", "测试准备", endSlotId = last.id, reminderMinutes = 10)
        repository.saveLesson(account, lesson)
        val profile = ScheduleProfile("测试学校", "测试学期", "测试班", "测试老师", LocalDate.of(2026, 9, 1), "测试提醒")
        repository.saveScheduleProfile(account, profile)
        val dao = app.database.records()
        assertEquals(lesson, RecordCodec.lesson(dao.find(account, RecordKind.LESSON, lesson.id)!!))
        assertEquals(profile, RecordCodec.scheduleProfile(dao.find(account, RecordKind.SETTINGS, "schedule_profile")!!))
        repository.saveRecognizedSchedule(account, ScheduleSuggestion(emptyList(),
            listOf(ScheduleLessonSuggestion(1, "第一节", "修改名称")), emptyList()), false)
        assertEquals(lesson.copy(subject = "修改名称"), RecordCodec.lesson(dao.find(account, RecordKind.LESSON, lesson.id)!!))
        try {
            repository.saveLesson(account, Lesson("overlap", 1, last.id, "冲突事项"))
            fail("跨节事项内不能重复安排课程")
        } catch (_: IllegalArgumentException) { }
        assertEquals(null, dao.find(account, RecordKind.LESSON, "overlap"))
        try {
            repository.saveTimeSlot(account, last.copy(weekdays = setOf(2)))
            fail("不能将仍被课程引用的结束节次移到其他星期")
        } catch (_: IllegalArgumentException) { }
        assertEquals(last, RecordCodec.timeSlot(dao.find(account, RecordKind.TIME_SLOT, last.id)!!))
    }

    @Test fun anIncompleteOldSpanDoesNotBlockUnrelatedEdits() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<ManmanApplication>()
        val repository = ClassRepository(app.database)
        val account = "schedule-incomplete-${UUID.randomUUID()}"
        val oldSlot = TimeSlot("old", "旧节次", LocalTime.parse("08:00"), LocalTime.parse("08:40"), setOf(1))
        repository.saveTimeSlot(account, oldSlot)
        val old = Lesson("old-lesson", 1, oldSlot.id, "旧事项", endSlotId = "missing")
        app.database.records().saveLocal(account, RecordKind.LESSON, old.id, RecordCodec.lesson(old))
        val newSlot = TimeSlot("new", "新节次", LocalTime.parse("09:00"), LocalTime.parse("09:40"), setOf(2))
        repository.saveTimeSlot(account, newSlot)
        repository.saveLesson(account, Lesson("new-lesson", 2, newSlot.id, "新事项"))
        assertEquals(old, RecordCodec.lesson(app.database.records().find(account, RecordKind.LESSON, old.id)!!))
        assertEquals("新事项", RecordCodec.lesson(app.database.records().find(account, RecordKind.LESSON, "new-lesson")!!).subject)
        try {
            repository.saveLesson(account, old)
            fail("修改失效的跨节事项时需要先选择有效结束节次")
        } catch (_: IllegalArgumentException) { }
    }

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
