package app.manman.teacher

import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.uiautomator.StaleObjectException
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
import app.manman.teacher.data.ForestRecords
import app.manman.teacher.data.RecordCodec
import app.manman.teacher.data.RecordKind
import app.manman.teacher.data.WorkTask
import app.manman.teacher.domain.AppTime
import app.manman.teacher.domain.ForestAnimal
import app.manman.teacher.domain.ForestGroup
import app.manman.teacher.domain.Leave
import app.manman.teacher.domain.LeavePeriod
import app.manman.teacher.domain.Student
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class PlannerForestWorkflowInstrumentedTest {
    private val device get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    private suspend fun session(): Pair<ManmanApplication, String> {
        assumeTrue(InstrumentationRegistry.getArguments().getString("plannerFixture") == "1")
        val app = ApplicationProvider.getApplicationContext<ManmanApplication>()
        val owner = "planner-test-${UUID.randomUUID()}"
        SecureSessionStore(app).save(AuthSession(owner, "fixture", "fixture", System.currentTimeMillis() + 3_600_000))
        return app to owner
    }

    @Test fun tomorrowCanBePlannedFromHomeAndHistoryStaysInMonthCalendar() = runBlocking {
        val (app, owner) = session()
        val today = LocalDate.now(AppTime.zone)
        val tomorrow = today.plusDays(1)
        val yesterday = today.minusDays(1)
        ClassRepository(app.database).saveTask(owner, WorkTask("past", "昨日已完成的虚构待办", null, null, null,
            true, Instant.now(), yesterday))
        ActivityScenario.launch(MainActivity::class.java).use {
            find(day(tomorrow)).click()
            find(By.desc("新建待办")).click()
            val field = find(By.clazz("android.widget.EditText"))
            field.text = "明天的课堂计划"
            device.pressBack()
            device.wait(Until.findObject(By.text("继续编辑")), 1_200)?.click()
            visible(By.text("标记课节（选填）")).click()
            find(By.text("第 2 节")).click()
            visible(By.text("保存待办")).click()
            withTimeout(10_000) {
                while (app.database.records().snapshot(owner).none {
                    it.kind == RecordKind.TASK && RecordCodec.task(it).let { task -> task.title == "明天的课堂计划" && task.dueDate == tomorrow }
                }) delay(100)
            }
            visible(By.text("明天的课堂计划"))
            visible(By.text("第 2 节课"))
            visible(By.desc("全部待办")).click()
            find(By.text("全部待办"))
            assertFalse(device.hasObject(By.text("昨日已完成的虚构待办")))
            if (today.month != yesterday.month) find(By.desc("上个月")).click()
            visible(day(yesterday)).click()
            visible(By.text("昨日已完成的虚构待办"))
            find(By.text("返回月历")).click()
            assertTrue(device.wait(Until.gone(By.text("昨日已完成的虚构待办")), 5_000))
        }
    }

    @Test fun reasonPersistsWhenEditingLeave() = runBlocking {
        val (app, owner) = session()
        val today = LocalDate.now(AppTime.zone)
        val repo = ClassRepository(app.database)
        repo.saveStudent(owner, Student("fixture", "虚构请假同学"))
        repo.saveLeave(owner, Leave("reason", "fixture", today, today, LeavePeriod.MORNING, Instant.now()))
        ActivityScenario.launch(MainActivity::class.java).use {
            find(By.text("记录")).click()
            visible(By.text("虚构请假同学")).click()
            find(By.text("修改")).click()
            val input = visible(By.clazz("android.widget.EditText"))
            input.text = "家中有事"
            device.pressBack()
            device.wait(Until.findObject(By.text("继续编辑")), 1_200)?.click()
            visible(By.text("保存请假")).click()
            withTimeout(10_000) {
                while (RecordCodec.leave(app.database.records().find(owner, RecordKind.LEAVE, "reason")!!).reason != "家中有事") delay(100)
            }
            click(By.text("虚构请假同学"))
            find(By.text("请假原因：家中有事"))
        }
        Unit
    }

    @Test fun classroomProfileAndDailyInteractionPersistAndCanBeUndone() = runBlocking {
        val (app, owner) = session()
        val today = LocalDate.now(AppTime.zone)
        ClassRepository(app.database).saveStudent(owner, Student("fixture", "虚构森林同学"))
        ActivityScenario.launch(MainActivity::class.java).use {
            find(By.text("记录")).click()
            visible(By.text("课堂森林")).click()
            visible(By.desc("编辑虚构森林同学的课堂档案")).click()
            visible(By.textContains("小鹿")).click()
            visible(By.text("多邀请发言")).click()
            find(By.text("保存档案")).click()
            withTimeout(10_000) {
                while (app.database.records().find(owner, RecordKind.SETTINGS, ForestRecords.profileId("fixture")) == null) delay(100)
            }
            val profile = ForestRecords.profile(app.database.records().find(owner, RecordKind.SETTINGS, ForestRecords.profileId("fixture"))!!)
            assertEquals(ForestAnimal.DEER, profile.animal)
            assertEquals(ForestGroup.INVITE, profile.group)
            val id = ForestRecords.interactionId("fixture", today)
            visible(By.text("虚构森林同学")).click()
            withTimeout(10_000) { while (app.database.records().find(owner, RecordKind.SETTINGS, id) == null) delay(100) }
            visible(By.text("本周互动 1 天"))
            visible(By.text("虚构森林同学")).click()
            withTimeout(10_000) { while (!app.database.records().find(owner, RecordKind.SETTINGS, id)!!.deleted) delay(100) }
            visible(By.desc("地鼠乐园")).click()
            visible(By.text("开始")).click()
            find(By.textStartsWith("还剩 "))
            val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
            val tapped = withTimeout(5_000) {
                var clicked = false
                while (!clicked) {
                    clicked = hitMole(automation.rootInActiveWindow)
                    if (!clicked) delay(75)
                }
                clicked
            }
            assertTrue(tapped)
            assertFalse(device.hasObject(By.text("虚构森林同学")))
        }
    }

    private fun hitMole(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false
        if (node.contentDescription?.contains("小地鼠出现") == true || node.text?.toString() == "🐹") {
            if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            if (node.parent?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) return true
        }
        return (0 until node.childCount).any { hitMole(node.getChild(it)) }
    }
    private fun click(selector: BySelector) {
        repeat(4) {
            try {
                visible(selector).click()
                return
            } catch (_: StaleObjectException) { SystemClock.sleep(140) }
        }
        throw AssertionError("控件未稳定：$selector")
    }

    private fun day(value: LocalDate) = By.descStartsWith("${value.year}年${value.monthValue}月${value.dayOfMonth}日 ")
    private fun find(selector: BySelector): UiObject2 = device.wait(Until.findObject(selector), 7_000)
        ?: throw AssertionError("找不到控件 $selector")
    private fun visible(selector: BySelector): UiObject2 {
        repeat(8) {
            device.wait(Until.findObject(selector), 550)?.let { return it }
            device.swipe(device.displayWidth / 2, device.displayHeight * 4 / 5, device.displayWidth / 2, device.displayHeight / 3, 30)
            SystemClock.sleep(150)
        }
        return find(selector)
    }
}
