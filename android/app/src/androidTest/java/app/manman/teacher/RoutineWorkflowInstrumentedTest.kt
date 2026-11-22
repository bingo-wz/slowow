package app.manman.teacher

import android.graphics.Rect
import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import app.manman.teacher.auth.AuthSession
import app.manman.teacher.auth.SecureSessionStore
import app.manman.teacher.data.ClassRepository
import app.manman.teacher.data.RecordCodec
import app.manman.teacher.data.RecordKind
import app.manman.teacher.data.RoutineRule
import app.manman.teacher.data.RoutineVersion
import app.manman.teacher.data.WorkTask
import app.manman.teacher.domain.AppTime
import app.manman.teacher.domain.RoutinePlanner
import app.manman.teacher.domain.Student
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
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.util.regex.Pattern
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
                scrollTo(device, "统一设置时间段").click()
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

    @Test fun selectedWeekdaysCanHaveDifferentTimingAtLargeFontSize() = runBlocking {
        val (app, account) = fixture()
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val oldScale = device.executeShellCommand("settings get system font_scale").trim()
        val oldAppSize = app.textSizes.state.value
        app.textSizes.select(AppTextSize.SYSTEM)
        device.executeShellCommand("settings put system font_scale 2.0")
        val today = LocalDate.now(AppTime.zone)
        val tomorrow = today.plusDays(1)
        val dayAfter = today.plusDays(2)
        val names = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")
        val first = names[today.dayOfWeek.value - 1]
        val second = names[tomorrow.dayOfWeek.value - 1]
        val third = names[dayAfter.dayOfWeek.value - 1]
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                byDescription(device, "全部待办").click()
                byDescription(device, "固定安排").click()
                byDescription(device, "添加固定安排").click()
                val input = device.wait(Until.findObject(By.clazz("android.widget.EditText")), 10_000)
                    ?: throw AssertionError("未找到固定安排输入框")
                input.text = "虚构每周事项"
                device.pressBack()
                device.wait(Until.findObject(By.text("继续编辑")), 1500)?.click()
                byText(device, first).click()
                byText(device, second).click()
                byText(device, third).click()
                scrollToDescription(device, "设置${first}时间段").click()
                assertNotNull(device.wait(Until.findObject(By.res("android", "button1")), 10_000))
                device.dumpWindowHierarchy(File(app.cacheDir, "weekday-picker.xml"))
                repeat(2) {
                    val confirm = device.wait(Until.findObject(By.res("android", "button1")), 10_000)
                        ?: throw AssertionError("未找到时间选择器的确认按钮")
                    confirm.click()
                    android.os.SystemClock.sleep(500)
                }
                assertNotNull(scrollTo(device, "08:00—08:40"))
                scrollToDescription(device, "设置${second}时间段").click()
                val hour9 = device.wait(Until.findObject(By.desc("9")), 10_000)
                    ?: throw AssertionError("未找到开始时间的小时")
                hour9.click()
                android.os.SystemClock.sleep(500)
                byDescription(device, "5").click()
                device.wait(Until.findObject(By.res("android", "button1")), 10_000).click()
                android.os.SystemClock.sleep(500)
                val hour10 = device.wait(Until.findObject(By.desc("10")), 10_000)
                    ?: throw AssertionError("未找到结束时间的小时")
                hour10.click()
                android.os.SystemClock.sleep(500)
                byDescription(device, "50").click()
                device.wait(Until.findObject(By.res("android", "button1")), 10_000).click()
                assertNotNull(scrollTo(device, "09:05—10:50"))
                scenario.recreate()
                assertNotNull(scrollTo(device, "08:00—08:40"))
                assertNotNull(scrollTo(device, "09:05—10:50"))
                assertTrue(device.takeScreenshot(File(app.cacheDir, "weekday-ranges-large.png")))
                scrollTo(device, "保存固定安排").click()
                var ruleRecord: app.manman.teacher.data.LocalRecord? = null
                withTimeout(10_000) {
                    while (ruleRecord == null) {
                        ruleRecord = app.database.records().snapshot(account).firstOrNull {
                            it.kind == RecordKind.SETTINGS && it.id.startsWith(RecordCodec.ROUTINE_PREFIX) && !it.deleted
                        }
                        if (ruleRecord == null) delay(100)
                    }
                }
                val rule = RecordCodec.routine(requireNotNull(ruleRecord))
                assertEquals(setOf(today.dayOfWeek.value, tomorrow.dayOfWeek.value, dayAfter.dayOfWeek.value), rule.current.weekdays)
                assertEquals(java.time.LocalTime.of(8, 0), rule.current.timeFor(today.dayOfWeek.value).start)
                assertEquals(java.time.LocalTime.of(8, 40), rule.current.timeFor(today.dayOfWeek.value).end)
                assertEquals(java.time.LocalTime.of(9, 5), rule.current.timeFor(tomorrow.dayOfWeek.value).start)
                assertEquals(java.time.LocalTime.of(10, 50), rule.current.timeFor(tomorrow.dayOfWeek.value).end)
                assertEquals(null, rule.current.timeFor(dayAfter.dayOfWeek.value).start)
                assertEquals(null, rule.current.timeFor(dayAfter.dayOfWeek.value).end)
                assertEquals("routine_v2", org.json.JSONObject(requireNotNull(ruleRecord).payload).getString("type"))
                assertTrue(device.wait(Until.hasObject(By.text("固定安排")), 10_000))
                scrollTo(device, "虚构每周事项").click()
                assertTrue(device.wait(Until.hasObject(By.text("每周哪几天")), 10_000))
                scenario.recreate()
                assertNotNull(scrollTo(device, "08:00—08:40"))
                assertNotNull(scrollTo(device, "09:05—10:50"))
            }
        } finally {
            app.textSizes.select(oldAppSize)
            if (oldScale == "null" || oldScale.isBlank()) device.executeShellCommand("settings delete system font_scale")
            else device.executeShellCommand("settings put system font_scale $oldScale")
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
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val previous = device.executeShellCommand("settings get global animator_duration_scale").trim()
        device.executeShellCommand("settings put global animator_duration_scale 3")
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                assertTrue(device.wait(Until.hasObject(By.text("今日待办")), 10_000))
                repository.saveTask(account, WorkTask("celebration-auto", "当日虚构安排", null, start, null, false,
                    now.minusSeconds(60), day, endAt = start.plusSeconds(30)))
                assertNotNull(waitForDescription("完成当日虚构安排", 10_000))
                scenario.moveToState(androidx.lifecycle.Lifecycle.State.STARTED)
                delay(1_200)
                assertFalse(RecordCodec.task(app.database.records().find(account, RecordKind.TASK, "celebration-auto")!!).done)
                scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
                if (waitForDescription("完成当日虚构安排", 10_000) == null) {
                    device.dumpWindowHierarchy(File(app.cacheDir, "automatic-pending-failed.xml"))
                    device.takeScreenshot(File(app.cacheDir, "automatic-pending-failed.png"))
                    val task = app.database.records().find(account, RecordKind.TASK, "celebration-auto")?.let(RecordCodec::task)
                    throw AssertionError("未看到待处理事项：状态=${task?.done}")
                }
                val pendingSeenAt = android.os.SystemClock.elapsedRealtime()
                assertNotNull(waitForDescription("取消完成当日虚构安排", 10_000))
                assertTrue(android.os.SystemClock.elapsedRealtime() - pendingSeenAt >= 200)
                val fireworks = waitForDescription("已完成，礼花庆祝", 5_000)
                assertNotNull(fireworks)
                val bounds = Rect()
                fireworks!!.getBoundsInScreen(bounds)
                assertTrue(bounds.width() >= device.displayWidth * .8f)
                assertTrue(bounds.height() >= device.displayHeight * .3f)
                assertTrue(device.takeScreenshot(File(app.cacheDir, "automatic-celebration.png")))
                assertTrue(RecordCodec.task(app.database.records().find(account, RecordKind.TASK, "celebration-auto")!!).done)
            }
        } finally {
            if (previous == "null" || previous.isBlank()) device.executeShellCommand("settings delete global animator_duration_scale")
            else device.executeShellCommand("settings put global animator_duration_scale $previous")
        }
    }

    @Test fun manuallyCompletingAnItemShowsFireworks() = runBlocking {
        val (app, account) = fixture()
        val now = Instant.now()
        val today = now.atZone(AppTime.zone).toLocalDate()
        ClassRepository(app.database).saveTask(account, WorkTask("manual-fireworks", "虚构待办", null, null, null, false, now, today))
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val previous = device.executeShellCommand("settings get global animator_duration_scale").trim()
        device.executeShellCommand("settings put global animator_duration_scale 3")
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                byDescription(device, "完成虚构待办").click()
                assertTrue(device.wait(Until.hasObject(By.desc("已完成，礼花庆祝")), 10_000))
                assertTrue(device.takeScreenshot(File(app.cacheDir, "manual-fireworks.png")))
                assertTrue(RecordCodec.task(app.database.records().find(account, RecordKind.TASK, "manual-fireworks")!!).done)
            }
        } finally {
            if (previous == "null" || previous.isBlank()) device.executeShellCommand("settings delete global animator_duration_scale")
            else device.executeShellCommand("settings put global animator_duration_scale $previous")
        }
    }

    @Test fun timedItemsCanBeDraggedAndKeepTheManualOrder() = runBlocking {
        val (app, account) = fixture()
        val now = Instant.now()
        val today = now.atZone(AppTime.zone).toLocalDate()
        val earlyStart = today.atTime(23, 40).atZone(AppTime.zone).toInstant()
        val lateStart = today.atTime(23, 50).atZone(AppTime.zone).toInstant()
        assumeTrue(now.isBefore(earlyStart))
        val repository = ClassRepository(app.database)
        repository.saveTask(account, WorkTask("early-time", "较早安排", null, earlyStart, null, false, now, today,
            endAt = earlyStart.plusSeconds(60)))
        repository.saveTask(account, WorkTask("late-time", "较晚安排", null, lateStart, null, false, now, today,
            endAt = lateStart.plusSeconds(60)))
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val previousScale = device.executeShellCommand("settings get system font_scale").trim()
        device.executeShellCommand("settings put system font_scale 1.0")
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                val from = byDescription(device, "拖动调整较晚安排的顺序").visibleCenter
                val to = byDescription(device, "拖动调整较早安排的顺序").visibleCenter
                device.drag(from.x, from.y, to.x, to.y, 30)
                withTimeout(10_000) {
                    while (RecordCodec.task(app.database.records().find(account, RecordKind.TASK, "late-time")!!).sortOrder != 0L) delay(100)
                }
                scenario.recreate()
                val later = byText(device, "较晚安排").visibleBounds.top
                val earlier = byText(device, "较早安排").visibleBounds.top
                assertTrue(later < earlier)
                assertEquals(lateStart, RecordCodec.task(app.database.records().find(account, RecordKind.TASK, "late-time")!!).dueAt)
            }
        } finally {
            if (previousScale == "null" || previousScale.isBlank()) device.executeShellCommand("settings delete system font_scale")
            else device.executeShellCommand("settings put system font_scale $previousScale")
        }
    }

    @Test fun editingAnExistingTimeRangePreservesAutomaticCompletion() = runBlocking {
        val (app, account) = fixture()
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val oldScale = device.executeShellCommand("settings get system font_scale").trim()
        val today = LocalDate.now(AppTime.zone)
        assumeTrue(Instant.now().isAfter(today.atTime(9, 0).atZone(AppTime.zone).toInstant()))
        val id = "existing-range-task"
        ClassRepository(app.database).saveTask(account, WorkTask(id, "虚构时间段待办", null, null, null, false, Instant.now(), today))
        val requestedScale = InstrumentationRegistry.getArguments().getString("timeFixtureScale") ?: "1.0"
        require(requestedScale in setOf("1.0", "2.0"))
        device.executeShellCommand("settings put system font_scale $requestedScale")
        try { ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            byText(device, "虚构时间段待办").click()
            byDescription(device, "编辑待办").click()
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
            withTimeout(10_000) { while (app.database.records().find(account, RecordKind.SETTINGS,
                    RecordCodec.taskMetadataId(id)) == null) delay(100) }
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

    @Test fun weeklyAndMonthlyCalendarsShowRepeatsBeyondTheFirstSevenDaysOffline() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("teacherFixture") == "1")
        val app = ApplicationProvider.getApplicationContext<ManmanApplication>()
        app.auth.restore()
        val account = requireNotNull(app.auth.session.value?.accountId)
        require(account.startsWith("teacher-fixture-"))
        val now = Instant.now()
        val today = now.atZone(AppTime.zone).toLocalDate()
        val title = "虚构的每周固定待办"
        val rule = RoutineRule("calendar-window", now, listOf(RoutineVersion(today.plusDays(1), title,
            setOf(today.dayOfWeek.value), LocalTime.of(8, 0), LocalTime.of(8, 30))))
        ClassRepository(app.database).saveRoutine(account, rule, now)
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val weekDate = today.plusWeeks(3)
        val targetMonth = YearMonth.from(today).plusMonths(2)
        val monthDate = generateSequence(targetMonth.atDay(1)) { it.plusDays(1) }
            .first { it.dayOfWeek == today.dayOfWeek }
        fun dayLabel(date: LocalDate) = "${date.year}年${date.monthValue}月${date.dayOfMonth}日 "
        assertNull(app.database.records().find(account, RecordKind.TASK, RoutinePlanner.occurrenceId(rule.id, weekDate)))
        assertNull(app.database.records().find(account, RecordKind.TASK, RoutinePlanner.occurrenceId(rule.id, monthDate)))
        suspend fun findCurrent(selector: BySelector): UiObject2 = withTimeout(10_000) {
            val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
            var match: UiObject2? = null
            while (match == null) {
                // 连续翻页时刷新无障碍树，避免读到前一页的日期。
                if (Build.VERSION.SDK_INT >= 34) automation.clearCache()
                match = device.findObject(selector)
                if (match == null) delay(100)
            }
            match
        }
        ActivityScenario.launch(MainActivity::class.java).use {
            findCurrent(By.text("今日待办"))
            repeat(3) { index ->
                val day = today.plusWeeks(index + 1L)
                findCurrent(By.desc("下一周")).click()
                findCurrent(By.text("${day.monthValue}月${day.dayOfMonth}日待办"))
            }
            findCurrent(By.text(title))
            findCurrent(By.text("08:00—08:30"))
            findCurrent(By.desc("全部待办")).click()
            repeat(2) { index ->
                findCurrent(By.desc("下个月")).click()
                val month = YearMonth.from(today).plusMonths(index + 1L)
                findCurrent(By.text("${month.year}年${month.monthValue}月"))
            }
            val badge = By.desc(Pattern.compile(Pattern.quote(dayLabel(monthDate)) + ".*1项待处理，0项已完成"))
            findCurrent(badge).click()
            findCurrent(By.text(title))
            findCurrent(By.text("08:00—08:30"))
        }
        Unit
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

    private fun scrollToDescription(device: UiDevice, description: String): UiObject2 {
        repeat(10) {
            device.wait(Until.findObject(By.desc(description)), 500)?.let { return it }
            device.swipe(device.displayWidth / 2, device.displayHeight * 3 / 4,
                device.displayWidth / 2, device.displayHeight / 3, 45)
        }
        val cache = ApplicationProvider.getApplicationContext<ManmanApplication>().cacheDir
        device.dumpWindowHierarchy(File(cache, "weekday-ranges-failed.xml"))
        device.takeScreenshot(File(cache, "weekday-ranges-failed.png"))
        throw AssertionError("未找到操作：$description")
    }

    private fun waitForDescription(description: String, timeout: Long): AccessibilityNodeInfo? {
        val end = android.os.SystemClock.elapsedRealtime() + timeout
        while (android.os.SystemClock.elapsedRealtime() < end) {
            findDescription(InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow, description)?.let { return it }
            android.os.SystemClock.sleep(30)
        }
        return null
    }

    private fun findDescription(node: AccessibilityNodeInfo?, description: String): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.contentDescription?.toString() == description) return node
        for (index in 0 until node.childCount) findDescription(node.getChild(index), description)?.let { return it }
        return null
    }
}
