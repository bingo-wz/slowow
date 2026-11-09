package app.manman.teacher.domain

import app.manman.teacher.data.Lesson
import app.manman.teacher.data.ScheduleKind
import app.manman.teacher.data.TimeSlot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

class TeacherTimetableTest {
    private val monday = LocalDate.of(2026, 11, 9)
    private fun slot(id: String, start: String, end: String, vararg days: Int) =
        TimeSlot(id, id, LocalTime.parse(start), LocalTime.parse(end), days.toSet())

    @Test fun weekdayDifferencesAndUnassignedSlotsRemainDistinct() {
        val slots = listOf(slot("主题", "08:35", "09:05", 1), slot("第一节一", "09:05", "09:45", 1),
            slot("第一节二", "08:35", "09:15", 2), slot("活动", "09:15", "09:55", 2))
        val lessons = listOf(Lesson("monday", 1, "第一节一", "语文", ScheduleKind.TEACHING),
            Lesson("tuesday", 2, "第一节二", "语文", ScheduleKind.TEACHING))
        val items = TeacherTimetable.build(monday, slots, lessons)
        assertEquals(LocalTime.parse("09:05"), items.single { it.lesson?.id == "monday" }.start)
        assertEquals(LocalTime.parse("08:35"), items.single { it.lesson?.id == "tuesday" }.start)
        assertNull(items.single { it.slot.id == "主题" }.lesson)
        assertNull(items.single { it.slot.id == "活动" }.kind)
    }

    @Test fun spanningItemCoversEmptySlotsAndUsesRealEndForCurrentAndNext() {
        val slots = listOf(slot("六", "14:50", "15:30", 5), slot("七", "15:45", "16:25", 5),
            slot("八", "16:40", "17:30", 5), slot("九", "17:35", "18:00", 5))
        val club = Lesson("club", 5, "六", "社团", ScheduleKind.DUTY, endSlotId = "八")
        val next = Lesson("later", 5, "九", "整理", ScheduleKind.DUTY)
        val items = TeacherTimetable.build(monday, slots, listOf(club, next))
        assertEquals(listOf("六", "九"), items.filter { it.coveredBy == null }.map { it.slot.id })
        assertEquals(listOf("七", "八"), items.filter { it.coveredBy == "club" }.map { it.slot.id })
        assertEquals(LocalTime.parse("17:30"), items.first().end)
        val (current, coming) = TeacherTimetable.currentAndNext(items, LocalDateTime.of(2026, 11, 13, 16, 0), null)
        assertEquals("club", current?.lesson?.id)
        assertEquals("later", coming?.lesson?.id)
    }

    @Test fun noTeacherOrKindIsInferredAndWeekNumbersAreOptional() {
        val slots = listOf(slot("一", "08:00", "08:40", 1), slot("二", "08:50", "09:30", 1))
        val lessons = listOf(Lesson("old", 1, "一", "原有课程"),
            Lesson("known", 1, "二", "阅读", ScheduleKind.TEACHING, teachers = "测试甲、测试乙"))
        val items = TeacherTimetable.build(monday, slots, lessons)
        val all = TeacherTimetable.summary(items, monday, null)
        assertEquals(1, all.weeklyLessons)
        assertEquals(1, all.unclassified)
        assertEquals(0, all.freeMinutes)
        assertEquals(listOf("测试乙", "测试甲").sorted(), TeacherTimetable.teacherNames(lessons))
        assertTrue(TeacherTimetable.matchesTeacher(lessons[1], "测试乙"))
        assertTrue(TeacherTimetable.matchesTeacher(lessons[1].copy(teachers = "测试甲 + 测试乙"), "测试乙"))
        assertEquals(1, TeacherTimetable.summary(items, monday, "测试乙").weeklyLessons)
        assertNull(TeacherTimetable.schoolWeek(monday, null))
        assertEquals(2L, TeacherTimetable.schoolWeek(monday, monday.minusWeeks(1)))
        assertNull(TeacherTimetable.schoolWeek(monday, monday.plusWeeks(1)))
    }

    @Test fun missingEndSlotDoesNotHideOtherItems() {
        val slots = listOf(slot("一", "08:00", "08:40", 1), slot("二", "08:50", "09:30", 1))
        val items = TeacherTimetable.build(monday, slots, listOf(Lesson("a", 1, "一", "活动", endSlotId = "removed")))
        assertEquals(2, items.size)
        assertTrue(items.first().spanIncomplete)
        assertEquals(LocalTime.parse("08:40"), items.first().end)
    }
}
