package app.manman.teacher.data

import app.manman.teacher.domain.Student
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StudentCodecTest {
    private fun record(payload: String) = LocalRecord("owner", RecordKind.STUDENT, "one", payload, updatedAt = 0)

    @Test fun oldStudentRecordsKeepTheirOriginalLunchDefaults() {
        val old = RecordCodec.student(record("""{"name":"小禾","normallyEatsAtSchool":false}"""))
        assertNull(old.schoolLunchDays)
        assertEquals(emptySet<Int>(), old.regularSchoolLunchDays())
    }

    @Test fun recurringDaysSurviveSerializationAndEmptyIsDistinctFromMissing() {
        val weekly = Student("one", "小禾", schoolLunchDays = setOf(1, 2, 3, 6, 7), sortInitial = "S")
        assertEquals(weekly.schoolLunchDays, RecordCodec.student(record(RecordCodec.student(weekly))).schoolLunchDays)
        assertEquals("S", RecordCodec.student(record(RecordCodec.student(weekly))).sortInitial)
        val noLunch = weekly.copy(normallyEatsAtSchool = false, schoolLunchDays = emptySet())
        assertEquals(emptySet<Int>(), RecordCodec.student(record(RecordCodec.student(noLunch))).schoolLunchDays)
    }
}
