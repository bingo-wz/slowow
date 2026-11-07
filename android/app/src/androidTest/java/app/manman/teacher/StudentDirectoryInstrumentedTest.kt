package app.manman.teacher

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.manman.teacher.domain.Student
import app.manman.teacher.domain.StudentDirectory
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StudentDirectoryInstrumentedTest {
    @Test fun groupsAndSortsChineseNamesByPinyin() {
        val students = listOf(Student("z", "张小禾"), Student("y", "叶小安"), Student("w", "王小木"), Student("l", "李小雨"))
        val sections = StudentDirectory.sections(students)
        assertEquals(listOf("L", "W", "Y", "Z"), sections.map { it.initial })
        assertEquals(listOf("李小雨", "王小木", "叶小安", "张小禾"), sections.flatMap { it.students }.map { it.name })
    }

    @Test fun explicitInitialSupportsPolyphonicSurnames() {
        val sections = StudentDirectory.sections(listOf(Student("one", "单小禾", sortInitial = "S")))
        assertEquals("S", sections.single().initial)
    }
}
