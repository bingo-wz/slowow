package app.manman.teacher

import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import app.manman.teacher.data.ClassRepository
import app.manman.teacher.data.RecordKind
import app.manman.teacher.domain.AppTime
import app.manman.teacher.domain.Leave
import app.manman.teacher.domain.LeavePeriod
import app.manman.teacher.domain.Student
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class LeaveDeletionWorkflowInstrumentedTest {
    @Test fun cancelledLeaveCanBeDeletedFromRecords() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("teacherFixture") == "1")
        val app = ApplicationProvider.getApplicationContext<ManmanApplication>()
        app.auth.restore()
        val account = requireNotNull(app.auth.session.value?.accountId)
        require(account.startsWith("teacher-fixture-"))
        val repository = ClassRepository(app.database)
        val id = UUID.randomUUID().toString()
        val today = LocalDate.now(AppTime.zone)
        repository.saveStudent(account, Student(id, "虚构学生甲"))
        repository.saveLeave(account, Leave("leave-$id", id, today, today, LeavePeriod.MORNING, Instant.now(), cancelled = true))
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        ActivityScenario.launch(MainActivity::class.java).use {
            assertTrue(device.wait(Until.hasObject(By.text("记录")), 15_000))
            device.findObject(By.text("记录")).click()
            assertTrue(device.wait(Until.hasObject(By.text("虚构学生甲")), 10_000))
            device.findObject(By.text("虚构学生甲")).click()
            assertTrue(device.wait(Until.hasObject(By.text("删除记录")), 5_000))
            device.findObject(By.text("删除记录")).click()
            assertTrue(device.wait(Until.hasObject(By.text("删除已撤销的请假？")), 5_000))
            device.findObject(By.text("删除")).click()
            assertTrue(device.wait(Until.gone(By.text("虚构学生甲")), 10_000))
            assertTrue(app.database.records().find(account, RecordKind.LEAVE, "leave-$id")!!.deleted)
        }
    }
}
