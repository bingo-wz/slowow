package app.manman.teacher.domain

import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LunchRulesTest {
    private val day = LocalDate.of(2026, 11, 9)
    private val zone = ZoneId.of("Asia/Shanghai")
    private val students = listOf(
        Student("a", "小林", position = 0),
        Student("b", "小雨", normallyEatsAtSchool = false, position = 1),
        Student("c", "小安", position = 2),
    )

    private fun leave(student: String, hour: Int, minute: Int, end: LocalDate = day) = Leave(
        id = "$student-$hour-$minute",
        studentId = student,
        start = day,
        end = end,
        period = LeavePeriod.ALL_DAY,
        recordedAt = day.atTime(hour, minute).atZone(zone).toInstant(),
    )

    @Test fun beforeCutoffAndLongTermExclusionAreDeduplicated() {
        val result = LunchRules.calculate(day, students, listOf(
            leave("a", 9, 0), leave("a", 9, 5), leave("b", 8, 0),
        ))
        assertEquals(listOf("a", "b"), result.excluded.map { it.studentId })
        assertEquals(1, result.eating.size)
        assertEquals(ExclusionReason.USUALLY_ELSEWHERE, result.excluded.last().reason)
    }

    @Test fun cutoffUsesRecordingTimeForEachDay() {
        val multiDay = leave("a", 13, 0, end = day.plusDays(2))
        assertEquals(listOf("b"), LunchRules.calculate(day, students, listOf(multiDay)).excluded.map { it.studentId })
        assertEquals(listOf("a", "b"), LunchRules.calculate(day.plusDays(1), students, listOf(multiDay)).excluded.map { it.studentId })
        assertEquals(listOf("b"), LunchRules.calculate(day, students, listOf(leave("a", 12, 30))).excluded.map { it.studentId })
    }

    @Test fun manualOverridesTakePrecedence() {
        val result = LunchRules.calculate(day, students, listOf(leave("a", 9, 0)), mapOf(
            "a" to MealOverride.EAT,
            "b" to MealOverride.EAT,
            "c" to MealOverride.DO_NOT_EAT,
        ))
        assertEquals(listOf("c"), result.excluded.map { it.studentId })
        assertEquals(ExclusionReason.MANUAL, result.excluded.single().reason)
    }

    @Test fun cancelledAfternoonAndInactiveStudentsAreHandled() {
        val cancelled = leave("a", 9, 0).copy(cancelled = true)
        val afternoon = leave("c", 9, 0).copy(period = LeavePeriod.AFTERNOON)
        val result = LunchRules.calculate(day, students + Student("d", "小和", active = false), listOf(cancelled, afternoon))
        assertEquals(3, result.students.size)
        assertEquals(listOf("b"), result.excluded.map { it.studentId })
    }

    @Test fun configurableCutoffAndSnapshotComparison() {
        val original = LunchRules.calculate(day, students, emptyList()).students
        val after = LunchRules.calculate(day, students, listOf(leave("a", 11, 45)), cutoff = LocalTime.NOON).students
        assertTrue(LunchRules.changedSince(original, after))
        assertFalse(LunchRules.changedSince(after, after.map { it.copy(name = "新名字") }))
    }

    @Test fun recurringWeekdaysExcludeOnlyThoseDaysAndRespectManualChanges() {
        val student = Student("weekly", "小禾", schoolLunchDays = setOf(1, 2, 3, 6, 7))
        val thursday = day.plusDays(3)
        val friday = day.plusDays(4)
        assertTrue(LunchRules.calculate(day, listOf(student), emptyList()).eating.isNotEmpty())
        assertTrue(LunchRules.calculate(thursday, listOf(student), emptyList()).eating.isEmpty())
        assertTrue(LunchRules.calculate(friday, listOf(student), emptyList()).eating.isEmpty())
        val overlap = leave("weekly", 9, 0, end = friday)
        assertEquals(1, LunchRules.calculate(thursday, listOf(student), listOf(overlap)).excluded.size)
        assertEquals(ExclusionReason.USUALLY_ELSEWHERE, LunchRules.calculate(thursday, listOf(student), listOf(overlap)).excluded.single().reason)
        assertTrue(LunchRules.calculate(thursday, listOf(student), listOf(overlap), mapOf("weekly" to MealOverride.EAT)).eating.isNotEmpty())
    }

    @Test fun existingAllOrNoLunchDefaultsRemainUnchanged() {
        val always = Student("all", "小禾")
        val never = Student("none", "小木", normallyEatsAtSchool = false)
        for (offset in 0L..6L) {
            val result = LunchRules.calculate(day.plusDays(offset), listOf(always, never), emptyList())
            assertEquals(listOf("all"), result.eating.map { it.studentId })
            assertEquals(listOf("none"), result.excluded.map { it.studentId })
        }
    }
}
