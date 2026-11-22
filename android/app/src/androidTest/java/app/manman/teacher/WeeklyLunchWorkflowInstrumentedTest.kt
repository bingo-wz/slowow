package app.manman.teacher

import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import app.manman.teacher.data.ClassRepository
import app.manman.teacher.data.RecordCodec
import app.manman.teacher.data.RecordKind
import app.manman.teacher.domain.AppTime
import app.manman.teacher.domain.MealAbsenceRules
import app.manman.teacher.domain.Student
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.io.File

@RunWith(AndroidJUnit4::class)
class WeeklyLunchWorkflowInstrumentedTest {
    @Test fun savesWeeklyExceptionsAndSelectsStudentByInitial() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("teacherFixture") == "1")
        val application = ApplicationProvider.getApplicationContext<ManmanApplication>()
        application.auth.restore()
        val account = requireNotNull(application.auth.session.value?.accountId)
        require(account.startsWith("teacher-fixture-"))
        val repository = ClassRepository(application.database)
        repository.saveStudent(account, Student("fictional-y", "叶小禾", position = 0))
        repository.saveStudent(account, Student("fictional-w", "王小木", normallyEatsAtSchool = false, position = 1))
        repository.saveStudent(account, Student("fictional-z", "张小安", position = 2))
        repository.addStudents(account, (1..49).map { "陈同学%02d".format(it) }, 3)
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            assertTrue(device.wait(Until.hasObject(By.desc("设置")), 20_000))
            device.findObject(By.desc("设置")).click()
            assertTrue(device.wait(Until.hasObject(By.text("班级与学生")), 10_000))
            device.findObject(By.text("班级与学生")).click()
            assertTrue(device.wait(Until.hasObject(By.text("叶小禾")), 10_000))
            device.findObject(By.text("叶小禾")).click()
            scrollTo(device, "周四").click()
            scrollTo(device, "周五").click()
            device.findObject(By.text("保存")).click()
            assertTrue(device.wait(Until.hasObject(By.text("学生名单")), 10_000))
            val record = requireNotNull(application.database.records().find(account, RecordKind.STUDENT, "fictional-y"))
            val student = RecordCodec.student(record)
            assertEquals(setOf(1, 2, 3, 6, 7), student.schoolLunchDays)
            assertTrue(MealAbsenceRules.forDate(LocalDate.of(2026, 11, 12), listOf(student), emptyList()).isNotEmpty())
            assertTrue(MealAbsenceRules.forDate(LocalDate.of(2026, 11, 13), listOf(student), emptyList()).isNotEmpty())
            assertTrue(MealAbsenceRules.forDate(LocalDate.of(2026, 11, 11), listOf(student), emptyList()).isEmpty())

            device.pressBack()
            device.pressBack()
            assertTrue(device.wait(Until.hasObject(By.text("记录")), 10_000))
            device.findObject(By.text("记录")).click()
            assertTrue(device.wait(Until.hasObject(By.desc("记录请假")), 10_000))
            device.findObject(By.desc("记录请假")).click()
            assertTrue(device.wait(Until.hasObject(By.textContains("选择学生")), 10_000))
            device.findObject(By.textContains("选择学生")).click()
            assertTrue(device.wait(Until.hasObject(By.desc("跳到 Y")), 10_000))
            device.takeScreenshot(File(application.cacheDir, "student-picker-preview.png"))
            val jump = device.findObject(By.desc("跳到 Y")).visibleCenter
            device.executeShellCommand("input tap ${jump.x} ${jump.y}")
            assertTrue(device.wait(Until.hasObject(By.text("叶小禾")), 10_000))
            device.findObject(By.text("叶小禾")).click()
            assertTrue(device.wait(Until.hasObject(By.text("保存请假")), 10_000))
        } finally {
            scenario.close()
        }
    }

    private fun scrollTo(device: UiDevice, label: String): UiObject2 {
        repeat(5) {
            device.findObject(By.text(label))?.let { return it }
            device.swipe(device.displayWidth / 2, device.displayHeight * 3 / 4,
                device.displayWidth / 2, device.displayHeight / 3, 20)
        }
        throw AssertionError("未找到控件：$label")
    }
}
