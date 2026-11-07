package app.manman.teacher.domain

import android.icu.text.AlphabeticIndex
import android.icu.text.Collator
import java.util.Locale

data class StudentSection(val initial: String, val students: List<Student>)

object StudentDirectory {
    fun sections(students: List<Student>): List<StudentSection> {
        val index = AlphabeticIndex<String>(Locale.SIMPLIFIED_CHINESE).apply { addLabels(Locale.ENGLISH) }.buildImmutableIndex()
        val collator = Collator.getInstance(Locale.SIMPLIFIED_CHINESE)
        val groups = students.groupBy { student ->
            val label = index.getBucket(index.getBucketIndex(student.name.trim()))?.label.orEmpty().uppercase(Locale.ROOT)
            student.sortInitial ?: label.takeIf { it.length == 1 && it[0] in 'A'..'Z' } ?: "#"
        }
        return groups.entries.sortedWith(compareBy { if (it.key == "#") "ZZZ" else it.key }).map { (initial, members) ->
            StudentSection(initial, members.sortedWith { left, right ->
                val nameOrder = collator.compare(left.name, right.name)
                if (nameOrder != 0) nameOrder else compareValuesBy(left, right, Student::position, Student::id)
            })
        }
    }
}
