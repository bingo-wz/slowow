package app.manman.teacher

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.manman.teacher.data.RecordCodec
import app.manman.teacher.data.RecordKind
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UpgradePreservationInstrumentedTest {
    @Test fun oldRecordsAndLoginSurviveUpgrade() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("upgradeFixture") == "1")
        val app = ApplicationProvider.getApplicationContext<ManmanApplication>()
        app.auth.restore()
        val account = requireNotNull(app.auth.session.value?.accountId)
        require(account.startsWith("release-fixture-"))
        val records = app.database.records().snapshot(account)
        val students = records.filter { it.kind == RecordKind.STUDENT && !it.deleted }.map(RecordCodec::student)
        assertEquals(52, students.size)
        assertTrue(students.all { it.normallyEatsAtSchool })
        assertTrue(students.all { it.schoolLunchDays == null })
        assertNull(students.first().sortInitial)
        assertTrue(records.count { it.dirty } >= 52)
        assertTrue(records.any { it.kind == RecordKind.MENU && it.id == "legacy-menu" && !it.deleted && it.payload.contains("虚构菜品") })
        val slot = records.single { it.kind == RecordKind.TIME_SLOT && it.id == "legacy-slot" && !it.deleted }
        assertEquals(setOf(1), RecordCodec.timeSlot(slot).weekdays)
        assertEquals("虚构语文", RecordCodec.lesson(records.single { it.kind == RecordKind.LESSON && it.id == "legacy-lesson" }).subject)
        val task = RecordCodec.task(records.single { it.kind == RecordKind.TASK && it.id == "legacy-task" })
        assertEquals("虚构提醒", task.title)
        assertNull(task.sortOrder)
        val marking = RecordCodec.marking(records.single { it.kind == RecordKind.MARKING && it.id == "legacy-marking" })
        assertEquals("虚构学生", marking.students.single().name)
        assertTrue(!marking.students.single().first)
        val week = RecordCodec.menuWeek(records.single { it.kind == RecordKind.MENU_WEEK && !it.deleted })
        val image = RecordCodec.attachment(records.single { it.kind == RecordKind.ATTACHMENT && it.id == week.attachmentId })
        val preview = app.attachments.preview(account, image) { throw AssertionError("本机图片不应联网") }
        assertEquals(24, preview.width)
        assertEquals(32, preview.height)
        preview.recycle()
    }
}
