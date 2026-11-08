package app.manman.teacher

import android.content.ClipboardManager
import android.content.Context
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import app.manman.teacher.data.ClassRepository
import app.manman.teacher.data.RecordKind
import app.manman.teacher.data.RecordCodec
import app.manman.teacher.domain.AppTime
import app.manman.teacher.domain.Leave
import app.manman.teacher.domain.LeavePeriod
import app.manman.teacher.domain.Student
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class MealAbsencesWorkflowInstrumentedTest {
    @Test fun listUpdatesFromLeaveAndWeeklyRulesAndCopiesNames() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("mealFixture") == "1")
        val app = ApplicationProvider.getApplicationContext<ManmanApplication>()
        app.auth.restore()
        val account = requireNotNull(app.auth.session.value?.accountId)
        require(account.startsWith("teacher-fixture-"))
        val repository = ClassRepository(app.database)
        val today = LocalDate.now(AppTime.zone)
        val regular = Student("regular", "虚构甲", normallyEatsAtSchool = false, position = 0)
        val weekly = Student("weekly", "虚构乙", position = 1, schoolLunchDays = (1..7).toSet() - today.dayOfWeek.value)
        val morning = Student("morning", "虚构丙", position = 2)
        val afternoon = Student("afternoon", "虚构丁", position = 3)
        listOf(regular, weekly, morning, afternoon).forEach { repository.saveStudent(account, it) }
        val late = today.atTime(18, 0).atZone(AppTime.zone).toInstant()
        val leave = Leave("leave-${UUID.randomUUID()}", morning.id, today, today.plusDays(1), LeavePeriod.MORNING, late)
        repository.saveLeave(account, leave)
        repository.saveLeave(account, Leave("leave-${UUID.randomUUID()}", afternoon.id, today, today, LeavePeriod.AFTERNOON, late))
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val oldScale = device.executeShellCommand("settings get system font_scale").trim()
        device.executeShellCommand("settings put system font_scale 1.0")
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                byText(device, "记录").click()
                byText(device, "不在校用餐名单").click()
                assertTrue(device.wait(Until.hasObject(By.text("共 3 人")), 10_000))
                assertTrue(device.hasObject(By.text("虚构甲")))
                assertTrue(device.hasObject(By.text("虚构乙")))
                assertTrue(device.hasObject(By.text("虚构丙")))
                assertFalse(device.hasObject(By.text("虚构丁")))
                byDescription(device, "复制不在校用餐名单").click()
                val clipboard = app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                assertEquals("虚构甲、虚构乙、虚构丙", clipboard.primaryClip!!.getItemAt(0).coerceToText(app).toString())
                byDescription(device, "后一天").click()
                assertTrue(device.wait(Until.hasObject(By.text("共 2 人")), 10_000))
                assertFalse(device.hasObject(By.text("虚构乙")))
                assertTrue(device.hasObject(By.text("虚构丙")))
                byDescription(device, "前一天").click()
                assertTrue(device.wait(Until.hasObject(By.text("共 3 人")), 10_000))
                repository.saveLeave(account, leave.copy(cancelled = true))
                val afterLeave = device.wait(Until.hasObject(By.text("共 2 人")), 10_000)
                if (!afterLeave) {
                    device.dumpWindowHierarchy(File(app.cacheDir, "meal-test-failed.xml"))
                    device.takeScreenshot(File(app.cacheDir, "meal-test-failed.png"))
                }
                assertTrue(afterLeave)
                assertFalse(device.hasObject(By.text("虚构丙")))
                repository.saveStudent(account, weekly.copy(schoolLunchDays = (1..7).toSet()))
                assertEquals((1..7).toSet(), RecordCodec.student(app.database.records().find(account, RecordKind.STUDENT, weekly.id)!!).schoolLunchDays)
                val updated = device.wait(Until.hasObject(By.text("共 1 人")), 10_000)
                if (!updated) {
                    device.dumpWindowHierarchy(File(app.cacheDir, "meal-test-failed.xml"))
                    device.takeScreenshot(File(app.cacheDir, "meal-test-failed.png"))
                }
                assertTrue(updated)
            }
        } finally {
            if (oldScale == "null" || oldScale.isBlank()) device.executeShellCommand("settings delete system font_scale")
            else device.executeShellCommand("settings put system font_scale $oldScale")
        }
    }

    @Test fun oldLunchReminderSettingsDoNotScheduleAnAlarm() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("mealFixture") == "1")
        val app = ApplicationProvider.getApplicationContext<ManmanApplication>()
        app.auth.restore()
        val account = requireNotNull(app.auth.session.value?.accountId)
        require(account.startsWith("teacher-fixture-"))
        val dao = app.database.records()
        dao.saveLocal(account, RecordKind.SETTINGS, "main", JSONObject().put("lunchCutoff", "12:30").toString())
        dao.saveLocal(account, RecordKind.SETTINGS, "reminders", JSONObject().put("lunchEnabled", true).put("lessonMinutes", -1).toString())
        app.reminders.reschedule()
        assertEquals(0L, app.getSharedPreferences("reminder_plan", Context.MODE_PRIVATE).getLong("next_at", 0))
    }

    private fun byText(device: UiDevice, label: String): UiObject2 =
        device.wait(Until.findObject(By.text(label)), 10_000) ?: throw AssertionError("未找到文字：$label")

    private fun byDescription(device: UiDevice, label: String): UiObject2 =
        device.wait(Until.findObject(By.desc(label)), 10_000) ?: throw AssertionError("未找到图标：$label")
}
