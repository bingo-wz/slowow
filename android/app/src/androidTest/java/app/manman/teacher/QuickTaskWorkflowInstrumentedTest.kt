package app.manman.teacher

import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import app.manman.teacher.auth.AuthSession
import app.manman.teacher.auth.SecureSessionStore
import app.manman.teacher.data.RecordCodec
import app.manman.teacher.data.RecordKind
import app.manman.teacher.domain.AppTime
import app.manman.teacher.fonts.AppTextSize
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class QuickTaskWorkflowInstrumentedTest {
    private fun fixture(): Pair<ManmanApplication, String> {
        assumeTrue(InstrumentationRegistry.getArguments().getString("quickFixture") == "1")
        val app = ApplicationProvider.getApplicationContext<ManmanApplication>()
        val account = "quick-ui-${UUID.randomUUID()}"
        SecureSessionStore(app).save(AuthSession(account, "test-token", "test-refresh", System.currentTimeMillis() + 3_600_000))
        return app to account
    }

    @Test fun newTasksDefaultToTodayAndCanSelectTomorrow() = runBlocking {
        val (app, account) = fixture()
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val today = LocalDate.now(AppTime.zone)
        ActivityScenario.launch(MainActivity::class.java).use {
            byDescription(device, "新建待办").click()
            assertTrue(device.wait(Until.hasObject(By.text("今天")), 10_000))
            assertTrue(device.hasObject(By.text("明天")))
            assertTrue(device.hasObject(By.text("选择日期")))
            assertTrue(device.hasObject(By.text("重复设置")))
            assertFalse(device.hasObject(By.text("设置时间段")))
            assertFalse(device.hasObject(By.text("提醒时间")))
            assertTrue(device.takeScreenshot(File(app.cacheDir, "quick-create-default.png")))
            input(device, "今天的虚构待办")
            scrollTo(device, "保存待办").click()
            val first = waitForTask(app, account, "今天的虚构待办")
            assertEquals(today, first.dueDate)
            assertNull(first.dueAt)
            assertNull(first.endAt)
            assertNull(first.remindAt)
            byDescription(device, "新建待办").click()
            input(device, "明天的虚构待办")
            byText(device, "明天").click()
            scrollTo(device, "保存待办").click()
            val second = waitForTask(app, account, "明天的虚构待办")
            assertEquals(today.plusDays(1), second.dueDate)
            assertNull(second.dueAt)
        }
    }

    @Test fun choosingADateDoesNotOpenATimePicker() = runBlocking {
        val (app, account) = fixture()
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val today = LocalDate.now(AppTime.zone)
        val selected = if (today.dayOfMonth <= today.lengthOfMonth() - 2) today.plusDays(2) else today.minusDays(2)
        ActivityScenario.launch(MainActivity::class.java).use {
            byDescription(device, "新建待办").click()
            input(device, "自选日期待办")
            byText(device, "选择日期").click()
            assertNotNull(device.wait(Until.findObject(By.res("android", "button1")), 10_000))
            val month = device.wait(Until.findObject(By.res("android", "month_view")), 10_000)
                ?: throw AssertionError("未找到日期选择器")
            val target = month.findObject(By.text(selected.dayOfMonth.toString()))
                ?: throw AssertionError("未找到目标日期")
            target.click()
            device.wait(Until.findObject(By.res("android", "button1")), 10_000).click()
            assertFalse(device.hasObject(By.res("android", "timePicker")))
            assertTrue(device.wait(Until.hasObject(By.text("保存待办")), 10_000))
            scrollTo(device, "保存待办").click()
            val task = waitForTask(app, account, "自选日期待办")
            assertEquals(selected, task.dueDate)
            assertNull(task.dueAt)
        }
    }

    @Test fun weeklyRepeatCanBeConfiguredWhileCreatingTaskAtLargeFontSize() = runBlocking {
        val (app, account) = fixture()
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val oldScale = device.executeShellCommand("settings get system font_scale").trim()
        val oldSize = app.textSizes.state.value
        app.textSizes.select(AppTextSize.SYSTEM)
        device.executeShellCommand("settings put system font_scale 2.0")
        val today = LocalDate.now(AppTime.zone)
        val tomorrow = today.plusDays(1)
        val names = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")
        val first = names[tomorrow.dayOfWeek.value - 1]
        val second = names[tomorrow.plusDays(1).dayOfWeek.value - 1]
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                byDescription(device, "新建待办").click()
                input(device, "虚构每周待办")
                scrollTo(device, "明天").click()
                scrollToDescription(device, "设置每周重复").click()
                scrollTo(device, first).click()
                scrollTo(device, second).click()
                scrollToDescription(device, "设置${first}时间段").click()
                repeat(2) { index ->
                    val confirm = device.wait(Until.findObject(By.res("android", "button1")), 10_000) ?: run {
                        device.dumpWindowHierarchy(File(app.cacheDir, "quick-picker-failed.xml"))
                        device.takeScreenshot(File(app.cacheDir, "quick-picker-failed.png"))
                        throw AssertionError("未找到时间选择器的确认按钮：$index")
                    }
                    confirm.click()
                    android.os.SystemClock.sleep(500)
                }
                assertNotNull(scrollTo(device, "08:00—08:40"))
                scenario.recreate()
                assertNotNull(scrollTo(device, "08:00—08:40"))
                assertTrue(device.takeScreenshot(File(app.cacheDir, "quick-weekly-large.png")))
                scrollTo(device, "保存待办").click()
                var record: app.manman.teacher.data.LocalRecord? = null
                withTimeout(10_000) {
                    while (record == null) {
                        record = app.database.records().snapshot(account).firstOrNull {
                            it.kind == RecordKind.SETTINGS && it.id.startsWith(RecordCodec.ROUTINE_PREFIX) && !it.deleted
                        }
                        if (record == null) delay(100)
                    }
                }
                val rule = RecordCodec.routine(requireNotNull(record))
                assertEquals(tomorrow, rule.current.effectiveFrom)
                assertEquals(setOf(tomorrow.dayOfWeek.value, tomorrow.plusDays(1).dayOfWeek.value), rule.current.weekdays)
                assertEquals(LocalTime.of(8, 0), rule.current.timeFor(tomorrow.dayOfWeek.value).start)
                assertEquals(LocalTime.of(8, 40), rule.current.timeFor(tomorrow.dayOfWeek.value).end)
                assertNull(rule.current.timeFor(tomorrow.plusDays(1).dayOfWeek.value).start)
                val firstOccurrence = app.database.records().snapshot(account).filter { it.kind == RecordKind.TASK && !it.deleted }
                    .map(RecordCodec::task).firstOrNull { it.title == "虚构每周待办" && it.dueDate == tomorrow }
                assertNotNull(firstOccurrence)
                assertEquals(tomorrow, firstOccurrence!!.dueDate)
            }
        } finally {
            app.textSizes.select(oldSize)
            if (oldScale == "null" || oldScale.isBlank()) device.executeShellCommand("settings delete system font_scale")
            else device.executeShellCommand("settings put system font_scale $oldScale")
        }
    }

    private suspend fun waitForTask(app: ManmanApplication, account: String, title: String): app.manman.teacher.data.WorkTask {
        var task: app.manman.teacher.data.WorkTask? = null
        withTimeout(10_000) {
            while (task == null) {
                task = app.database.records().snapshot(account).filter { it.kind == RecordKind.TASK && !it.deleted }
                    .map(RecordCodec::task).firstOrNull { it.title == title }
                if (task == null) delay(100)
            }
        }
        return requireNotNull(task)
    }

    private fun input(device: UiDevice, value: String) {
        val field = device.wait(Until.findObject(By.clazz("android.widget.EditText")), 10_000)
            ?: throw AssertionError("未找到待办输入框")
        field.text = value
        device.pressBack()
        device.wait(Until.findObject(By.text("继续编辑")), 1500)?.click()
    }

    private fun byText(device: UiDevice, text: String): UiObject2 =
        device.wait(Until.findObject(By.text(text)), 10_000) ?: throw AssertionError("未找到文字：$text")
    private fun byDescription(device: UiDevice, text: String): UiObject2 =
        device.wait(Until.findObject(By.desc(text)), 10_000) ?: throw AssertionError("未找到操作：$text")
    private fun scrollTo(device: UiDevice, text: String): UiObject2 {
        repeat(10) {
            device.wait(Until.findObject(By.text(text)), 500)?.let { return it }
            device.swipe(device.displayWidth / 2, device.displayHeight * 3 / 4,
                device.displayWidth / 2, device.displayHeight / 3, 40)
        }
        throw AssertionError("未找到文字：$text")
    }
    private fun scrollToDescription(device: UiDevice, text: String): UiObject2 {
        repeat(10) {
            device.wait(Until.findObject(By.desc(text)), 500)?.let { return it }
            device.swipe(device.displayWidth / 2, device.displayHeight * 3 / 4,
                device.displayWidth / 2, device.displayHeight / 3, 40)
        }
        throw AssertionError("未找到操作：$text")
    }
}
