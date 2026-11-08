package app.manman.teacher

import android.content.ClipboardManager
import android.content.Context
import android.graphics.Rect
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.StaleObjectException
import androidx.test.uiautomator.Until
import app.manman.teacher.data.ClassRepository
import app.manman.teacher.data.MarkingRecord
import app.manman.teacher.data.MarkingStudent
import app.manman.teacher.data.Notice
import app.manman.teacher.data.RecordCodec
import app.manman.teacher.data.RecordKind
import app.manman.teacher.data.WorkTask
import app.manman.teacher.domain.AppTime
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class AgendaWorkflowInstrumentedTest {
    private suspend fun account(app: ManmanApplication): String {
        assumeTrue(InstrumentationRegistry.getArguments().getString("agendaFixture") == "1")
        app.auth.restore()
        return requireNotNull(app.auth.session.value?.accountId).also { require(it.startsWith("teacher-fixture-")) }
    }

    @Test fun todayTasksCanBeCheckedGroupedAndReordered() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<ManmanApplication>()
        val account = account(app)
        val repository = ClassRepository(app.database)
        val today = LocalDate.now(AppTime.zone)
        val now = Instant.now()
        val ids = (0..2).map { "agenda-${UUID.randomUUID()}" }
        val titles = listOf("准备作业", "检查作业", "布置作业")
        ids.forEachIndexed { index, id ->
            repository.saveTask(account, WorkTask(id, titles[index], null, null, null, false, now, today, index.toLong()))
        }
        repository.saveTask(account, WorkTask("later-${UUID.randomUUID()}", "明天的虚构待办", null, null, null, false, now, today.plusDays(1)))
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val oldScale = device.executeShellCommand("settings get system font_scale").trim()
        device.executeShellCommand("settings put system font_scale 1.0")
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                assertTrue(device.wait(Until.hasObject(By.text("今日待办")), 20_000))
                assertTrue(device.wait(Until.hasObject(By.text("布置作业")), 10_000))
                assertFalse(device.hasObject(By.text("明天的虚构待办")))
                byDescription(device, "完成准备作业").click()
                assertTrue(device.wait(Until.hasObject(By.desc("取消完成准备作业")), 10_000))
                assertTrue(RecordCodec.task(app.database.records().find(account, RecordKind.TASK, ids[0])!!).done)
                val from = byDescription(device, "拖动调整布置作业的顺序").visibleCenter
                val to = byDescription(device, "拖动调整准备作业的顺序").visibleCenter
                device.drag(from.x, from.y, to.x, to.y, 30)
                withTimeout(10_000) {
                    while (true) {
                        val task = RecordCodec.task(app.database.records().find(account, RecordKind.TASK, ids[2])!!)
                        if (task.sortOrder == 0L) break
                        delay(100)
                    }
                }
                val label = today.format(DateTimeFormatter.ofPattern("M月d日")) + " · 今天"
                assertFalse(device.hasObject(By.text(label)))
                byDescription(device, "全部待办").click()
                assertTrue(device.wait(Until.hasObject(By.text("全部待办")), 10_000))
                byText(device, label).click()
                assertTrue(device.wait(Until.gone(By.text("布置作业")), 5_000))
                byText(device, label).click()
                assertTrue(device.wait(Until.hasObject(By.text("布置作业")), 5_000))
                scrollTo(device, "明天的虚构待办")
                device.takeScreenshot(File(app.cacheDir, "agenda-workflow.png"))
                device.pressBack()
                tapDescription(device, "新建待办")
                assertTrue(device.wait(Until.hasObject(By.text("新建待办")), 10_000))
                val field = device.wait(Until.findObject(By.clazz("android.widget.EditText")), 10_000)
                    ?: throw AssertionError("未找到待办输入框")
                field.text = "新增测试待办"
                byText(device, "保存待办").click()
                assertTrue(device.wait(Until.hasObject(By.text("今日待办")), 10_000))
                withTimeout(10_000) {
                    while (app.database.records().snapshot(account).filter { it.kind == RecordKind.TASK && !it.deleted }
                            .map(RecordCodec::task).none { it.title == "新增测试待办" && it.dueDate == today }) delay(100)
                }
            }
        } finally {
            restoreScale(device, oldScale)
        }
        Unit
    }

    @Test fun markingSnapshotCanCopyEditAndDelete() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<ManmanApplication>()
        val account = account(app)
        val repository = ClassRepository(app.database)
        val id = "marking-${UUID.randomUUID()}"
        val savedAt = Instant.parse("2026-10-05T02:00:00Z")
        val original = MarkingRecord(id, "虚构班级事项", "有", "没有", savedAt, listOf(
            MarkingStudent("a", "虚构甲", true), MarkingStudent("b", "虚构乙", false), MarkingStudent("c", "虚构丙", true)))
        repository.saveMarking(account, original)
        repository.saveMarking(account, original.copy(id = "marking-${UUID.randomUUID()}", title = "同日另一事项", createdAt = savedAt.plusSeconds(60)))
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val oldScale = device.executeShellCommand("settings get system font_scale").trim()
        device.executeShellCommand("settings put system font_scale 1.0")
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                byText(device, "记录").click()
                scrollTo(device, "2026年10月5日").click()
                assertTrue(device.wait(Until.gone(By.text("虚构班级事项")), 5_000))
                byText(device, "2026年10月5日").click()
                scrollTo(device, "虚构班级事项").click()
                assertTrue(device.wait(Until.hasObject(By.text("2026年10月5日 10:00")), 10_000))
                val clipboard = app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                byDescription(device, "复制有名单").click()
                assertEquals("虚构甲、虚构丙", clipboard.primaryClip!!.getItemAt(0).coerceToText(app).toString())
                byDescription(device, "复制没有名单").click()
                assertEquals("虚构乙", clipboard.primaryClip!!.getItemAt(0).coerceToText(app).toString())
                byDescription(device, "编辑班级标记").click()
                val student = device.wait(Until.findObject(By.descContains("虚构乙，没有")), 10_000)
                assertTrue(student != null)
                student.click()
                byText(device, "保存记录").click()
                assertTrue(device.wait(Until.hasObject(By.desc("删除班级标记")), 10_000))
                val updated = RecordCodec.marking(app.database.records().find(account, RecordKind.MARKING, id)!!)
                assertEquals(savedAt, updated.createdAt)
                assertTrue(updated.students.all { it.first })
                tapDescription(device, "删除班级标记")
                assertTrue(device.wait(Until.hasObject(By.text("删除这条班级标记？")), 10_000))
                byText(device, "删除").click()
                withTimeout(10_000) {
                    while (app.database.records().find(account, RecordKind.MARKING, id)?.deleted != true) delay(100)
                }
            }
        } finally {
            restoreScale(device, oldScale)
        }
    }

    @Test fun homeActionsRemainUsableWithLargeSystemText() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<ManmanApplication>()
        val owner = account(app)
        val repository = ClassRepository(app.database)
        val now = Instant.now()
        val today = LocalDate.now(AppTime.zone)
        val tasks = listOf(
            WorkTask("large-${UUID.randomUUID()}", "准备明天的语文作业", null, null, null, false, now, today, -2),
            WorkTask("large-${UUID.randomUUID()}", "整理班级通知", null, null, null, true, now, today, -1),
        )
        tasks.forEach { repository.saveTask(owner, it) }
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val oldScale = device.executeShellCommand("settings get system font_scale").trim()
        device.executeShellCommand("settings put system font_scale 2.0")
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                assertTrue(device.wait(Until.hasObject(By.text("今日待办")), 20_000))
                val menu = byDescription(device, "本周菜单").visibleBounds
                val notice = byDescription(device, "学校通知").visibleBounds
                val settings = byDescription(device, "设置").visibleBounds
                val all = byDescription(device, "全部待办").visibleBounds
                val add = byDescription(device, "新建待办").visibleBounds
                assertFalse(Rect.intersects(menu, notice))
                assertFalse(Rect.intersects(notice, settings))
                assertFalse(Rect.intersects(all, add))
                assertTrue(device.wait(Until.hasObject(By.text("准备明天的语文作业")), 10_000))
                assertTrue(device.wait(Until.hasObject(By.text("整理班级通知")), 10_000))
                assertTrue(device.takeScreenshot(File(app.cacheDir, "agenda-large-font.png")))
                val center = byDescription(device, "设置").visibleCenter
                device.executeShellCommand("input swipe ${center.x} ${center.y} ${center.x} ${center.y} 1000")
                assertTrue(device.wait(Until.hasObject(By.text("设置")), 5_000))
            }
        } finally {
            tasks.forEach { repository.deleteTask(owner, it) }
            restoreScale(device, oldScale)
        }
    }

    @Test fun editingKeepsTheCorrectNoticeAndOrder() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<ManmanApplication>()
        val owner = account(app)
        val repository = ClassRepository(app.database)
        val now = Instant.now()
        val today = LocalDate.now(AppTime.zone)
        val notice = Notice("notice-${UUID.randomUUID()}", "虚构通知", "虚构通知原文", now)
        val independent = WorkTask("task-${UUID.randomUUID()}", "独立待办", null, null, null, false, now, today, -10)
        val linked = WorkTask("task-${UUID.randomUUID()}", "关联待办", notice.id, null, null, false, now, today, -9)
        repository.saveNotice(owner, notice)
        repository.saveTask(owner, independent)
        repository.saveTask(owner, linked)
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val oldScale = device.executeShellCommand("settings get system font_scale").trim()
        device.executeShellCommand("settings put system font_scale 1.0")
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                tapDescription(device, "学校通知")
                byText(device, "虚构通知").click()
                assertTrue(device.wait(Until.hasObject(By.text("虚构通知原文")), 10_000))
                device.pressBack()
                device.pressBack()
                byText(device, "独立待办").click()
                tapDescription(device, "编辑待办")
                val field = device.wait(Until.findObject(By.clazz("android.widget.EditText")), 10_000)
                    ?: throw AssertionError("未找到待办输入框")
                field.text = "修改后的独立待办"
                byText(device, "保存待办").click()
                assertTrue(device.wait(Until.hasObject(By.text("待办详情")), 10_000))
                val updated = RecordCodec.task(app.database.records().find(owner, RecordKind.TASK, independent.id)!!)
                assertEquals(null, updated.noticeId)
                assertEquals(-10L, updated.sortOrder)
                device.pressBack()
                byText(device, "关联待办").click()
                byText(device, "查看原文  ›").click()
                assertTrue(device.wait(Until.hasObject(By.text("虚构通知原文")), 10_000))
            }
        } finally {
            repository.deleteTask(owner, independent)
            repository.deleteTask(owner, linked)
            restoreScale(device, oldScale)
        }
    }

    private fun byText(device: UiDevice, label: String): UiObject2 =
        device.wait(Until.findObject(By.text(label)), 10_000) ?: throw AssertionError("未找到文字：$label")

    private fun byDescription(device: UiDevice, label: String): UiObject2 =
        device.wait(Until.findObject(By.desc(label)), 10_000) ?: throw AssertionError("未找到图标：$label")

    private fun tapDescription(device: UiDevice, label: String) {
        repeat(5) {
            try {
                byDescription(device, label).click()
                return
            } catch (_: StaleObjectException) {
                device.waitForIdle()
            }
        }
        throw AssertionError("无法点击图标：$label")
    }

    private fun scrollTo(device: UiDevice, label: String): UiObject2 {
        repeat(6) {
            device.findObject(By.text(label))?.let { return it }
            device.swipe(device.displayWidth / 2, device.displayHeight * 3 / 4,
                device.displayWidth / 2, device.displayHeight / 3, 20)
        }
        throw AssertionError("未找到文字：$label")
    }

    private fun restoreScale(device: UiDevice, scale: String) {
        if (scale == "null" || scale.isBlank()) device.executeShellCommand("settings delete system font_scale")
        else device.executeShellCommand("settings put system font_scale $scale")
    }
}
