package app.manman.teacher.domain

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

object AppTime {
    val zone: ZoneId = ZoneId.of("Asia/Shanghai")
}

data class Student(
    val id: String,
    val name: String,
    val normallyEatsAtSchool: Boolean = true,
    val active: Boolean = true,
    val position: Int = 0,
    val schoolLunchDays: Set<Int>? = null,
    val sortInitial: String? = null,
) {
    fun eatsAtSchoolOn(day: LocalDate): Boolean = schoolLunchDays?.contains(day.dayOfWeek.value) ?: normallyEatsAtSchool

    fun regularSchoolLunchDays(): Set<Int> = schoolLunchDays ?: if (normallyEatsAtSchool) (1..7).toSet() else emptySet()
}

enum class LeavePeriod { ALL_DAY, MORNING, AFTERNOON }

data class Leave(
    val id: String,
    val studentId: String,
    val start: LocalDate,
    val end: LocalDate,
    val period: LeavePeriod,
    val recordedAt: Instant,
    val cancelled: Boolean = false,
)

data class MealAbsence(val studentId: String, val name: String, val regular: Boolean, val onLeave: Boolean)

object MealAbsenceRules {
    fun forDate(day: LocalDate, students: List<Student>, leaves: List<Leave>): List<MealAbsence> {
        val leaveIds = leaves.asSequence()
            .filter { !it.cancelled && it.period != LeavePeriod.AFTERNOON }
            .filter { !day.isBefore(it.start) && !day.isAfter(it.end) }
            .map(Leave::studentId).toSet()
        return students.asSequence().filter(Student::active).sortedWith(compareBy(Student::position, Student::id))
            .mapNotNull { student ->
                val regular = !student.eatsAtSchoolOn(day)
                val onLeave = student.id in leaveIds
                if (regular || onLeave) MealAbsence(student.id, student.name, regular, onLeave) else null
            }.toList()
    }
}
