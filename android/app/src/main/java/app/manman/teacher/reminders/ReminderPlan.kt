package app.manman.teacher.reminders

import app.manman.teacher.data.Lesson
import app.manman.teacher.data.TimeSlot
import app.manman.teacher.data.WorkTask
import app.manman.teacher.domain.LunchRules
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

object ReminderPlan {
    fun next(tasks: List<WorkTask>, lessons: List<Lesson>, slots: List<TimeSlot>, lessonMinutes: Int?, after: Instant,
        lunchTime: LocalTime? = null): Instant? {
        val taskTimes = tasks.asSequence().filterNot { it.done }.mapNotNull { it.remindAt }.filter { it > after }
        val firstDay = after.atZone(LunchRules.defaultZone).toLocalDate().minusDays(1)
        val lessonTimes = (0L..9L).asSequence().flatMap { offset -> dailyTimes(firstDay.plusDays(offset), lessons, slots, lessonMinutes, lunchTime).asSequence() }
            .filter { it > after }
        return (taskTimes + lessonTimes).minOrNull()
    }

    fun hasDue(tasks: List<WorkTask>, lessons: List<Lesson>, slots: List<TimeSlot>, lessonMinutes: Int?, start: Instant, end: Instant,
        lunchTime: LocalTime? = null): Boolean {
        if (end < start) return false
        if (tasks.any { !it.done && it.remindAt != null && it.remindAt >= start && it.remindAt <= end }) return true
        val boundedStart = maxOf(start, end.minus(Duration.ofDays(14)))
        var day = boundedStart.atZone(LunchRules.defaultZone).toLocalDate().minusDays(1)
        val lastDay = end.atZone(LunchRules.defaultZone).toLocalDate().plusDays(1)
        while (!day.isAfter(lastDay)) {
            if (dailyTimes(day, lessons, slots, lessonMinutes, lunchTime).any { it >= boundedStart && it <= end }) return true
            day = day.plusDays(1)
        }
        return false
    }

    private fun dailyTimes(day: LocalDate, lessons: List<Lesson>, slots: List<TimeSlot>, minutes: Int?, lunchTime: LocalTime?): List<Instant> {
        val times = slots.associateBy { it.id }
        val courses = if (minutes == null || minutes !in 0..60) emptyList() else lessons.filter { it.weekday == day.dayOfWeek.value }.mapNotNull { lesson ->
            times[lesson.slotId]?.let { slot -> day.atTime(slot.start).atZone(LunchRules.defaultZone).toInstant().minusSeconds(minutes * 60L) }
        }
        val lunch = if (lunchTime != null && day.dayOfWeek.value in 1..5) listOf(day.atTime(lunchTime).atZone(LunchRules.defaultZone).toInstant()) else emptyList()
        return courses + lunch
    }
}
