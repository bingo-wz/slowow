package app.manman.teacher.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

class MealAbsenceRulesTest {
    private val day = LocalDate.of(2026, 11, 12)
    private val recordedLate = Instant.parse("2026-11-12T09:00:00Z")
    private val students = listOf(
        Student("regular", "固定校外", normallyEatsAtSchool = false, position = 0),
        Student("weekly", "每周校外", position = 1, schoolLunchDays = setOf(1, 2, 3, 6, 7)),
        Student("morning", "上午请假", position = 2),
        Student("afternoon", "下午请假", position = 3),
        Student("cancelled", "撤销请假", position = 4),
        Student("inactive", "已离班", active = false, normallyEatsAtSchool = false, position = 5),
    )

    private fun leave(id: String, period: LeavePeriod, cancelled: Boolean = false, end: LocalDate = day) =
        Leave("leave-$id-$period", id, day, end, period, recordedLate, cancelled)

    @Test fun combinesRegularAndMorningLeaveWithoutDuplicatesEvenWhenRecordedLate() {
        val leaves = listOf(leave("morning", LeavePeriod.MORNING), leave("morning", LeavePeriod.ALL_DAY),
            leave("weekly", LeavePeriod.ALL_DAY), leave("afternoon", LeavePeriod.AFTERNOON),
            leave("cancelled", LeavePeriod.ALL_DAY, cancelled = true))
        val result = MealAbsenceRules.forDate(day, students, leaves)
        assertEquals(listOf("regular", "weekly", "morning"), result.map(MealAbsence::studentId))
        assertTrue(result[1].regular && result[1].onLeave)
        assertFalse(result[2].regular)
        assertTrue(result[2].onLeave)
    }

    @Test fun recalculatesForEachDateAndAfterLeaveChanges() {
        val multiDay = leave("morning", LeavePeriod.ALL_DAY, end = day.plusDays(2))
        assertTrue(MealAbsenceRules.forDate(day.plusDays(2), students, listOf(multiDay)).any { it.studentId == "morning" })
        assertFalse(MealAbsenceRules.forDate(day.plusDays(3), students, listOf(multiDay)).any { it.studentId == "morning" })
        assertFalse(MealAbsenceRules.forDate(day, students, listOf(multiDay.copy(cancelled = true))).any { it.studentId == "morning" })
        assertTrue(MealAbsenceRules.forDate(day.plusDays(1), students, emptyList()).any { it.studentId == "weekly" })
        assertFalse(MealAbsenceRules.forDate(day.minusDays(1), students, emptyList()).any { it.studentId == "weekly" })
    }
}
