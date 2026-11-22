package app.manman.teacher.domain

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

class ForestAttentionTest {
    @Test fun remindersRespectRecentInteractionAndDoNotSelectInactiveStudents() {
        val today = LocalDate.of(2026, 11, 22)
        val students = listOf(Student("a", "虚构甲"), Student("b", "虚构乙"),
            Student("c", "虚构丙", active = false), Student("d", "虚构丁"), Student("e", "虚构戊"))
        val profiles = students.filterNot { it.id == "e" }.associate {
            it.id to ForestProfile(it.id, group = ForestGroup.INVITE,
                watchSince = today.minusDays(if (it.id == "d") 2 else 10))
        }
        val interactions = listOf(ForestInteraction("a", today.minusDays(7)), ForestInteraction("b", today.minusDays(6)),
            ForestInteraction("a", today.plusDays(1)))
        assertEquals(listOf("a"), ForestAttention.due(today, students, profiles, interactions).map(Student::id))
        assertEquals(LocalDate.of(2026, 11, 16), ForestPeriod.WEEK.start(today))
        assertEquals(LocalDate.of(2026, 11, 1), ForestPeriod.MONTH.start(today))
    }
}
