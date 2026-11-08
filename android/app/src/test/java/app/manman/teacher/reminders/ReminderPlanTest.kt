package app.manman.teacher.reminders

import app.manman.teacher.data.Lesson
import app.manman.teacher.data.TimeSlot
import app.manman.teacher.data.WorkTask
import app.manman.teacher.domain.AppTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

class ReminderPlanTest {
    private fun at(day: String, hour: Int, minute: Int): Instant = LocalDate.parse(day).atTime(hour, minute).atZone(AppTime.zone).toInstant()

    @Test fun completedTasksAndPastRemindersAreNotScheduled() {
        val now = at("2026-11-09", 8, 0)
        val past = WorkTask("past", "过去", null, null, now.minusSeconds(60), false, now)
        val done = WorkTask("done", "完成", null, null, now.plusSeconds(60), true, now)
        val next = WorkTask("next", "待办", null, null, now.plusSeconds(120), false, now)
        assertEquals(next.remindAt, ReminderPlan.next(listOf(past, done, next), emptyList(), emptyList(), null, now))
        assertNull(ReminderPlan.next(listOf(past, done), emptyList(), emptyList(), null, now))
    }

    @Test fun timetableChangesMoveTheNextReminder() {
        val now = at("2026-11-09", 7, 0)
        val lesson = Lesson("lesson", 1, "slot", "语文")
        val slot = TimeSlot("slot", "第一节", LocalTime.of(8, 0), LocalTime.of(8, 40))
        assertEquals(at("2026-11-09", 7, 55), ReminderPlan.next(emptyList(), listOf(lesson), listOf(slot), 5, now))
        val moved = slot.copy(start = LocalTime.of(8, 30))
        assertEquals(at("2026-11-09", 8, 25), ReminderPlan.next(emptyList(), listOf(lesson), listOf(moved), 5, now))
        assertNull(ReminderPlan.next(emptyList(), listOf(lesson), listOf(slot), null, now))
    }

    @Test fun weeklyReminderCanFallOnThePreviousDay() {
        val sunday = at("2026-11-08", 23, 0)
        val lesson = Lesson("lesson", 1, "slot", "语文")
        val slot = TimeSlot("slot", "早课", LocalTime.of(0, 5), LocalTime.of(0, 45))
        val reminder = at("2026-11-08", 23, 55)
        assertEquals(reminder, ReminderPlan.next(emptyList(), listOf(lesson), listOf(slot), 10, sunday))
        assertTrue(ReminderPlan.hasDue(emptyList(), listOf(lesson), listOf(slot), 10, reminder, reminder.plusSeconds(60)))
        assertFalse(ReminderPlan.hasDue(emptyList(), listOf(lesson), listOf(slot), 10, reminder.plusSeconds(1), reminder.plusSeconds(60)))
    }

}
