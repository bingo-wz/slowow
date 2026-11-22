package app.manman.teacher.domain

import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import java.time.DayOfWeek

enum class ForestGroup(val label: String) {
    CLASS("常规互动"), INVITE("多邀请发言"), COLLABORATE("小组协作"), CHECK_IN("多关照"),
}
enum class ForestAnimal(val label: String, val icon: String) {
    DEER("小鹿", "🦌"), FOX("小狐狸", "🦊"), WOLF("小灰狼", "🐺"), RABBIT("小兔", "🐰"),
    BEAR("小熊", "🐻"), SQUIRREL("小松鼠", "🐿️"), OWL("猫头鹰", "🦉"), HEDGEHOG("小刺猬", "🦔"),
}
enum class ForestSupport(val label: String) {
    NONE("暂未记录"), WIND("风 · 给选择"), FIRE("火 · 先肯定"), EARTH("土 · 明确步骤"), WATER("水 · 先倾听"),
}
data class ForestProfile(val studentId: String, val animal: ForestAnimal? = null, val group: ForestGroup = ForestGroup.CLASS,
    val support: ForestSupport = ForestSupport.NONE, val note: String = "", val watchSince: LocalDate? = null)

data class ForestInteraction(val studentId: String, val day: LocalDate)

enum class ForestPeriod(val label: String) {
    DAY("今天"), WEEK("本周"), MONTH("本月");
    fun start(today: LocalDate): LocalDate = when (this) {
        DAY -> today
        WEEK -> today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        MONTH -> today.withDayOfMonth(1)
    }
}

object ForestAttention {
    fun due(today: LocalDate, students: List<Student>, profiles: Map<String, ForestProfile>,
        interactions: List<ForestInteraction>): List<Student> {
        val latest = interactions.filter { it.day <= today }.groupingBy { it.studentId }
            .fold(null as LocalDate?) { last, item -> if (last == null || item.day > last) item.day else last }
        return students.asSequence().filter(Student::active).mapNotNull { student ->
            val profile = profiles[student.id] ?: return@mapNotNull null
            val since = profile.watchSince ?: return@mapNotNull null
            if (profile.group == ForestGroup.CLASS) return@mapNotNull null
            val last = latest[student.id]?.let { maxOf(it, since) } ?: since
            (student to last).takeIf { last <= today.minusDays(7) }
        }.sortedWith(compareBy({ it.second }, { it.first.position })).map { it.first }.toList()
    }
}
