package app.manman.teacher.reminders

import app.manman.teacher.data.Lesson
import app.manman.teacher.data.TimeSlot
import app.manman.teacher.data.WorkTask
import app.manman.teacher.domain.AppTime
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

object ReminderPlan {
    fun next(tasks: List<WorkTask>, lessons: List<Lesson>, slots: List<TimeSlot>, lessonMinutes: Int?, after: Instant): Instant? {
        val taskTimes = tasks.asSequence().filterNot { it.done }.mapNotNull { it.remindAt }.filter { it > after }
        val firstDay = after.atZone(AppTime.zone).toLocalDate().minusDays(1)
        val lessonTimes = (0L..9L).asSequence().flatMap { offset -> dailyTimes(firstDay.plusDays(offset), lessons, slots, lessonMinutes).asSequence() }
            .filter { it > after }
        return (taskTimes + lessonTimes).minOrNull()
    }

    fun hasDue(tasks: List<WorkTask>, lessons: List<Lesson>, slots: List<TimeSlot>, lessonMinutes: Int?, start: Instant, end: Instant): Boolean {
        if (end < start) return false
        if (tasks.any { !it.done && it.remindAt != null && it.remindAt >= start && it.remindAt <= end }) return true
        val boundedStart = maxOf(start, end.minus(Duration.ofDays(14)))
        var day = boundedStart.atZone(AppTime.zone).toLocalDate().minusDays(1)
        val lastDay = end.atZone(AppTime.zone).toLocalDate().plusDays(1)
        while (!day.isAfter(lastDay)) {
            if (dailyTimes(day, lessons, slots, lessonMinutes).any { it >= boundedStart && it <= end }) return true
            day = day.plusDays(1)
        }
        return false
    }

    private fun dailyTimes(day: LocalDate, lessons: List<Lesson>, slots: List<TimeSlot>, minutes: Int?): List<Instant> {
        val times = slots.associateBy { it.id }
        val courses = lessons.filter { it.weekday == day.dayOfWeek.value }.mapNotNull { lesson ->
            val offset = if (lesson.reminderMinutes == -1) null else lesson.reminderMinutes ?: minutes
            if (offset == null || offset !in 0..60) null else times[lesson.slotId]
                ?.takeIf { it.weekdays.isEmpty() || day.dayOfWeek.value in it.weekdays }
                ?.let { slot -> day.atTime(slot.start).atZone(AppTime.zone).toInstant().minusSeconds(offset * 60L) }
        }
        return courses
    }
}
