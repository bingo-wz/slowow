package app.manman.teacher.domain

import app.manman.teacher.data.Lesson
import app.manman.teacher.data.ScheduleKind
import app.manman.teacher.data.TimeSlot
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

data class TimetableItem(
    val date: LocalDate,
    val slot: TimeSlot,
    val lesson: Lesson?,
    val end: LocalTime,
    val spanIncomplete: Boolean = false,
    val coveredBy: String? = null,
) {
    val start: LocalTime get() = slot.start
    val kind: ScheduleKind? get() = lesson?.kind
    val minutes: Long get() = Duration.between(start, end).toMinutes().coerceAtLeast(0)
}

data class TimetableSummary(val weeklyLessons: Int, val todayLessons: Int, val todayDuty: Int, val freeMinutes: Long, val unclassified: Int)

object TeacherTimetable {
    fun weekStart(date: LocalDate): LocalDate = date.with(TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY))

    fun schoolWeek(date: LocalDate, termStart: LocalDate?): Long? {
        if (termStart == null) return null
        val diff = ChronoUnit.WEEKS.between(weekStart(termStart), weekStart(date))
        return if (diff < 0) null else diff + 1
    }

    private fun splitTeachers(value: String): List<String> = value.split('、', '，', ',', '；', ';', '+', '＋', '/', '／')
        .map(String::trim).filter(String::isNotEmpty)

    fun teacherNames(lessons: List<Lesson>): List<String> = lessons.asSequence()
        .flatMap { splitTeachers(it.teachers).asSequence() }.distinct().sorted().toList()

    fun matchesTeacher(lesson: Lesson?, teacher: String?): Boolean = teacher == null ||
        lesson?.let { teacher in splitTeachers(it.teachers) } == true

    fun build(week: LocalDate, slots: List<TimeSlot>, lessons: List<Lesson>): List<TimetableItem> {
        val monday = weekStart(week)
        return (0..6).flatMap { index ->
            val day = monday.plusDays(index.toLong())
            val weekday = day.dayOfWeek.value
            val daySlots = slots.filter { it.weekdays.isEmpty() || weekday in it.weekdays }.sortedWith(compareBy(TimeSlot::start, TimeSlot::end, TimeSlot::id))
            val byId = daySlots.associateBy(TimeSlot::id)
            val dayLessons = lessons.filter { it.weekday == weekday && it.slotId in byId }
            val bySlot = dayLessons.groupBy(Lesson::slotId)
            val spans = dayLessons.mapNotNull { lesson ->
                val first = byId[lesson.slotId] ?: return@mapNotNull null
                val last = lesson.endSlotId?.let(byId::get) ?: return@mapNotNull null
                if (last.end < first.end) null else Triple(lesson.id, first.start, last.end)
            }
            daySlots.flatMap { slot ->
                val scheduled = bySlot[slot.id].orEmpty()
                if (scheduled.isEmpty()) {
                    val span = spans.firstOrNull { (_, start, end) -> slot.start >= start && slot.end <= end && slot.start != start }
                    listOf(TimetableItem(day, slot, null, slot.end, coveredBy = span?.first))
                } else scheduled.map { lesson ->
                    val last = lesson.endSlotId?.let(byId::get)
                    val valid = last != null && last.end >= slot.end
                    TimetableItem(day, slot, lesson, if (valid) last!!.end else slot.end,
                        spanIncomplete = lesson.endSlotId != null && !valid)
                }
            }
        }.sortedWith(compareBy(TimetableItem::date, TimetableItem::start, TimetableItem::end, { it.slot.id }))
    }

    fun summary(items: List<TimetableItem>, today: LocalDate, teacher: String?): TimetableSummary {
        val selected = items.filter { it.lesson != null && matchesTeacher(it.lesson, teacher) }
        return TimetableSummary(
            weeklyLessons = selected.count { it.kind == ScheduleKind.TEACHING },
            todayLessons = selected.count { it.date == today && it.kind == ScheduleKind.TEACHING },
            todayDuty = selected.count { it.date == today && it.kind == ScheduleKind.DUTY },
            freeMinutes = selected.filter { it.kind == ScheduleKind.FREE }.sumOf(TimetableItem::minutes),
            unclassified = selected.count { it.kind == null },
        )
    }

    fun currentAndNext(items: List<TimetableItem>, now: LocalDateTime, teacher: String?): Pair<TimetableItem?, TimetableItem?> {
        val relevant = items.filter { it.date == now.toLocalDate() && it.lesson != null && matchesTeacher(it.lesson, teacher) }
        val current = relevant.firstOrNull { now.toLocalTime() >= it.start && now.toLocalTime() < it.end }
        val next = relevant.firstOrNull { it.start > now.toLocalTime() }
        return current to next
    }
}
