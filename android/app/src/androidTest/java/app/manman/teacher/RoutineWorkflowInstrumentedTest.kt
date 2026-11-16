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
import app.manman.teacher.data.ClassRepository
import app.manman.teacher.data.RecordCodec
import app.manman.teacher.data.RecordKind
import app.manman.teacher.data.WorkTask
import app.manman.teacher.domain.AppTime
import app.manman.teacher.domain.Student
import app.manman.teacher.fonts.AppTextSize
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class RoutineWorkflowInstrumentedTest {
    private fun fixture(): Pair<ManmanApplication, String> {
        assumeTrue(InstrumentationRegistry.getArguments().getString("routineFixture") == "1")
        val app = ApplicationProvider.getApplicationContext<ManmanApplication>()
        val account = "routine-ui-${UUID.randomUUID()}"
        SecureSessionStore(app).save(AuthSession(account, "test-token", "test-refresh", System.currentTimeMillis() + 3_600_000))
        return app to account
    }

    @Test fun recurringLunchCanBeCreatedCompletedAndOpenedAtLargeFontSizes() = runBlocking {
        val (app, account) = fixture()
        val repository = ClassRepository(app.database)
        repository.saveStudent(account, Student("outside", "虚构学生", normallyEatsAtSchool = false))
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val oldScale = device.executeShellCommand("settings get system font_scale").trim()
        val oldAppSize = app.textSizes.state.value
        app.textSizes.select(AppTextSize.SYSTEM)
        val today = LocalDate.now(AppTime.zone)
        val weekday = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")[today.dayOfWeek.value - 1]
        try {
            device.executeShellCommand("settings put system font_scale 1.0")
            ActivityScenario.launch(MainActivity::class.java).use {
                byDescription(device, "全部待办").click()
                byDescription(device, "固定安排").click()
                byText(device, "午餐核销").click()
                byText(device, weekday).click()
                scrollTo(device, "设置时间段").click()
                repeat(2) {
                    val confirm = device.wait(Until.findObject(By.res("android", "button1")), 10_000)
                        ?: throw AssertionError("未找到时间选择器的确认按钮")
                    confirm.click()
                    android.os.SystemClock.sleep(500)
                }
                scrollTo(device, "保存固定安排").click()
                assertTrue(device.wait(Until.hasObject(By.text("固定安排")), 10_000))
                withTimeout(10_000) {
                    while (app.database.records().snapshot(account).none {
                        it.kind == RecordKind.SETTINGS && it.id.startsWith(RecordCodec.ROUTINE_PREFIX) && !it.deleted
                    }) delay(100)
                }
                val ruleRecord = app.database.records().snapshot(account).single {
                    it.kind == RecordKind.SETTINGS && it.id.startsWith(RecordCodec.ROUTINE_PREFIX) && !it.deleted
                }
                val rule = RecordCodec.routine(ruleRecord)
                assertEquals(setOf(today.dayOfWeek.value), rule.current.weekdays)
                assertTrue(rule.current.showMealList)
                assertEquals(java.time.LocalTime.of(8, 0), rule.current.start)
                assertEquals(java.time.LocalTime.of(8, 40), rule.current.end)
                device.pressBack()
                scrollTo(device, "午餐核销").click()
                byText(device, "不在校用餐名单").click()
                assertTrue(device.wait(Until.hasObject(By.text("虚构学生")), 10_000))
                device.pressBack()
                byText(device, "完成待办").click()
                if (!device.wait(Until.hasObject(By.text("已完成")), 10_000)) {
                    device.dumpWindowHierarchy(File(app.cacheDir, "routine-completion-failed.xml"))
                    device.takeScreenshot(File(app.cacheDir, "routine-completion-failed.png"))
                    throw AssertionError("详情页未显示完成状态")
                }
                device.pressBack()
                device.pressBack()
                assertTrue(device.wait(Until.hasObject(By.text("今天的待办都完成啦")), 10_000))
                byText(device, "已完成 1 项").click()
                scrollTo(device, "午餐核销")
                assertNotNull(device.wait(Until.findObject(By.desc("取消完成午餐核销")), 10_000))
                byDescription(device, "取消完成午餐核销").click()
                assertNotNull(device.wait(Until.findObject(By.desc("完成午餐核销")), 10_000))
            }
            device.executeShellCommand("settings put system font_scale 2.0")
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                byDescription(device, "全部待办").click()
                byDescription(device, "固定安排").click()
                assertTrue(device.wait(Until.hasObject(By.text("午餐核销")), 10_000))
                assertTrue(device.takeScreenshot(File(app.cacheDir, "routine-list-large.png")))
                byText(device, "午餐核销").click()
                assertTrue(device.wait(Until.hasObject(By.text("每周哪几天")), 10_000))
                scenario.recreate()
                assertTrue(device.wait(Until.hasObject(By.text("每周哪几天")), 10_000))
                assertTrue(device.takeScreenshot(File(app.cacheDir, "routine-editor-large.png")))
                assertNotNull(scrollTo(device, "保存固定安排"))
            }
        } finally {
            app.textSizes.select(oldAppSize)
            if (oldScale == "null" || oldScale.isBlank()) device.executeShellCommand("settings delete system font_scale")
            else device.executeShellCommand("settings put system font_scale $oldScale")
        }
    }

    @Test fun editingDraftSurvivesRecreationAndConflictingRemoteChange() = runBlocking {
        val (app, account) = fixture()
        val repository = ClassRepository(app.database)
        val now = Instant.now()
        val original = WorkTask("editing-conflict", "原有事项", null, null, null, false, now,
            now.atZone(AppTime.zone).toLocalDate())
        repository.saveTask(account, original)
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            byText(device, "原有事项").click()
            byDescription(device, "编辑待办").click()
            val input = device.wait(Until.findObject(By.clazz("android.widget.EditText")), 10_000)
                ?: throw AssertionError("未找到待办输入框")
            input.text = "本机草稿"
            device.pressBack()
            device.wait(Until.findObject(By.text("继续编辑")), 1500)?.click()
            scenario.recreate()
            assertTrue(device.wait(Until.hasObject(By.text("本机草稿")), 10_000))
            repository.saveTask(account, original.copy(title = "另一设备修改"))
            delay(1000)
            scrollTo(device, "保存待办").click()
            assertTrue(device.wait(Until.hasObject(By.textContains("待办在其他设备上有不同修改")), 10_000))
            assertTrue(device.hasObject(By.text("本机草稿")))
            assertEquals("另一设备修改", RecordCodec.task(app.database.records().find(account, RecordKind.TASK, original.id)!!).title)
        }
    }

    @Test fun openingAutomaticallyCompletesOnlyExpiredRangesAndManualUndoPersists() = runBlocking {
        val (app, account) = fixture()
        val repository = ClassRepository(app.database)
        val now = Instant.now()
        val today = now.atZone(AppTime.zone).toLocalDate()
        val yesterday = today.minusDays(1)
        val todayStart = today.atStartOfDay(AppTime.zone).toInstant()
        val todayEnd = todayStart.plusSeconds(30)
        assumeTrue(now.isAfter(todayEnd))
        val previousStart = yesterday.atTime(8, 0).atZone(AppTime.zone).toInstant()
        val tasks = listOf(
            WorkTask("expired-today", "今天的虚构安排", null, todayStart, null, false, now.minusSeconds(3600), today,
                endAt = todayEnd),
            WorkTask("expired-yesterday", "昨天的虚构安排", null, previousStart, null, false, now.minusSeconds(86400), yesterday,
                endAt = previousStart.plusSeconds(600)),
            WorkTask("no-range", "没有结束时间", null, now.minusSeconds(120), null, false, now.minusSeconds(3600), today),
            WorkTask("future-range", "还没结束的安排", null, now.minusSeconds(20), null, false, now.minusSeconds(3600), today,
                endAt = now.plusSeconds(3600)),
        )
        tasks.forEach { repository.saveTask(account, it) }
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            withTimeout(15_000) {
                while (listOf("expired-today", "expired-yesterday").any { id ->
                        app.database.records().find(account, RecordKind.TASK, id)?.let(RecordCodec::task)?.done != true
                    }) delay(100)
            }
            assertFalse(RecordCodec.task(app.database.records().find(account, RecordKind.TASK, "no-range")!!).done)
            assertFalse(RecordCodec.task(app.database.records().find(account, RecordKind.TASK, "future-range")!!).done)
            val metadata = RecordCodec.taskMetadata(app.database.records().find(account, RecordKind.SETTINGS,
                RecordCodec.taskMetadataId("expired-today"))!!)
            assertTrue(metadata.autoCompleted)
            assertTrue(repository.setTaskDone(account, "expired-today", false))
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.STARTED)
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
            delay(1500)
            assertFalse(RecordCodec.task(app.database.records().find(account, RecordKind.TASK, "expired-today")!!).done)
            assertTrue(RecordCodec.taskMetadata(app.database.records().find(account, RecordKind.SETTINGS,
                RecordCodec.taskMetadataId("expired-today"))!!).autoCompletionSuppressed)
            assertTrue(device.wait(Until.hasObject(By.text("今天的虚构安排")), 10_000))
        }
    }

    @Test fun openingShowsCelebrationForTodaysAutomaticCompletion() = runBlocking {
        val (app, account) = fixture()
        val repository = ClassRepository(app.database)
        val now = Instant.now()
        val day = now.atZone(AppTime.zone).toLocalDate()
        val start = day.atStartOfDay(AppTime.zone).toInstant()
        assumeTrue(now.isAfter(start.plusSeconds(30)))
        repository.saveTask(account, WorkTask("celebration-auto", "当日虚构安排", null, start, null, false,
            now.minusSeconds(60), day, endAt = start.plusSeconds(30)))
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val previous = device.executeShellCommand("settings get global animator_duration_scale").trim()
        device.executeShellCommand("settings put global animator_duration_scale 3")
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                assertTrue(device.wait(Until.hasObject(By.text("完成啦")), 10_000))
                assertTrue(device.takeScreenshot(File(app.cacheDir, "automatic-celebration.png")))
                assertTrue(RecordCodec.task(app.database.records().find(account, RecordKind.TASK, "celebration-auto")!!).done)
            }
        } finally {
            if (previous == "null" || previous.isBlank()) device.executeShellCommand("settings delete global animator_duration_scale")
            else device.executeShellCommand("settings put global animator_duration_scale $previous")
        }
    }

    @Test fun choosingATimeRangeStoresItAndCompletesAfterReopening() = runBlocking {
        val (app, account) = fixture()
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val oldScale = device.executeShellCommand("settings get system font_scale").trim()
        val today = LocalDate.now(AppTime.zone)
        assumeTrue(Instant.now().isAfter(today.atTime(9, 0).atZone(AppTime.zone).toInstant()))
        val requestedScale = InstrumentationRegistry.getArguments().getString("timeFixtureScale") ?: "1.0"
        require(requestedScale in setOf("1.0", "2.0"))
        device.executeShellCommand("settings put system font_scale $requestedScale")
        try { ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            byDescription(device, "新建待办").click()
            val input = device.wait(Until.findObject(By.clazz("android.widget.EditText")), 10_000)
                ?: throw AssertionError("未找到待办输入框")
            input.text = "虚构时间段待办"
            device.pressBack()
            device.wait(Until.findObject(By.text("继续编辑")), 1500)?.click()
            scrollTo(device, "设置时间段").click()
            repeat(3) { index ->
                val confirm = device.wait(Until.findObject(By.res("android", "button1")), 10_000)
                    ?: throw AssertionError("未找到日期或时间选择器的确认按钮")
                assertTrue(device.takeScreenshot(File(app.cacheDir, "routine-time-$requestedScale-$index.png")))
                confirm.click()
                android.os.SystemClock.sleep(500)
            }
            assertTrue(device.wait(Until.hasObject(By.text("结束后下次打开应用会自动完成。")), 10_000))
            scrollTo(device, "保存待办").click()
            var id = ""
            withTimeout(10_000) {
                while (id.isEmpty()) {
                    id = app.database.records().snapshot(account).filter { it.kind == RecordKind.TASK && !it.deleted }
                        .map(RecordCodec::task).firstOrNull { it.title == "虚构时间段待办" }?.id.orEmpty()
                    if (id.isEmpty()) delay(100)
                }
            }
            val record = app.database.records().find(account, RecordKind.TASK, id)!!
            val meta = RecordCodec.taskMetadata(app.database.records().find(account, RecordKind.SETTINGS, RecordCodec.taskMetadataId(id))!!)
            assertNotNull(meta.endAt)
            assertFalse(RecordCodec.task(record).done)
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.STARTED)
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
            withTimeout(10_000) {
                while (!RecordCodec.task(app.database.records().find(account, RecordKind.TASK, id)!!).done) delay(100)
            }
            assertTrue(RecordCodec.taskMetadata(app.database.records().find(account, RecordKind.SETTINGS,
                RecordCodec.taskMetadataId(id))!!).autoCompleted)
        } } finally {
            if (oldScale == "null" || oldScale.isBlank()) device.executeShellCommand("settings delete system font_scale")
            else device.executeShellCommand("settings put system font_scale $oldScale")
        }
    }

    private fun byText(device: UiDevice, text: String): UiObject2 =
        device.wait(Until.findObject(By.text(text)), 10_000) ?: throw AssertionError("未找到文字：$text")

    private fun byDescription(device: UiDevice, text: String): UiObject2 =
        device.wait(Until.findObject(By.desc(text)), 10_000) ?: throw AssertionError("未找到操作：$text")

    private fun scrollTo(device: UiDevice, text: String): UiObject2 {
        repeat(10) {
            device.wait(Until.findObject(By.text(text)), 500)?.let { return it }
            device.swipe(device.displayWidth / 2, device.displayHeight * 3 / 4,
                device.displayWidth / 2, device.displayHeight / 3, 45)
        }
        val cache = ApplicationProvider.getApplicationContext<ManmanApplication>().cacheDir
        device.dumpWindowHierarchy(File(cache, "routine-workflow-failed.xml"))
        device.takeScreenshot(File(cache, "routine-workflow-failed.png"))
        throw AssertionError("未找到文字：$text")
    }
}
