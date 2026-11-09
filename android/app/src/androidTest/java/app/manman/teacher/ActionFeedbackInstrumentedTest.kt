package app.manman.teacher

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import app.manman.teacher.ui.ActionFeedback
import app.manman.teacher.ui.ActionIcon
import app.manman.teacher.ui.Block
import app.manman.teacher.ui.FeedbackTextButton
import app.manman.teacher.ui.LocalActionFeedback
import app.manman.teacher.ui.ManmanTheme
import app.manman.teacher.ui.QuietTextField
import app.manman.teacher.ui.TaskAgenda
import app.manman.teacher.ui.feedbackToggle
import app.manman.teacher.data.WorkTask
import app.manman.teacher.domain.AppTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger
import java.time.Instant
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class ActionFeedbackInstrumentedTest {
    @Test fun buttonsSelectionsAndDialogsProvideOneFeedbackPerAction() {
        val taps = AtomicInteger()
        val toggles = AtomicInteger()
        val feedback = object : ActionFeedback {
            override fun tap() { taps.incrementAndGet() }
            override fun toggle(checked: Boolean) { toggles.incrementAndGet() }
            override fun dragStart() = Unit
            override fun dragStep() = Unit
        }
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    var checked by androidx.compose.runtime.remember { mutableStateOf(false) }
                    var dialog by androidx.compose.runtime.remember { mutableStateOf(false) }
                    var input by androidx.compose.runtime.remember { mutableStateOf("") }
                    ManmanTheme {
                        CompositionLocalProvider(LocalActionFeedback provides feedback) {
                            Column(Modifier.systemBarsPadding()) {
                                FeedbackTextButton(onClick = { dialog = true }) { Text("打开测试窗口") }
                                FeedbackTextButton(onClick = {}, enabled = false) { Text("停用测试按钮") }
                                Block(onClick = {}) { Text("测试卡片") }
                                ActionIcon("plus", "测试图标", {})
                                Checkbox(checked = checked, onCheckedChange = feedbackToggle { checked = it })
                                QuietTextField(input, { input = it }, "测试输入")
                            }
                            if (dialog) AlertDialog(onDismissRequest = { dialog = false }, title = { Text("测试窗口") },
                                confirmButton = { FeedbackTextButton(onClick = { dialog = false }) { Text("确认测试") } })
                        }
                    }
                }
            }
            assertTrue(device.wait(Until.hasObject(By.text("打开测试窗口")), 10_000))
            device.findObject(By.text("停用测试按钮")).click()
            assertEquals(0, taps.get())
            device.findObject(By.text("测试卡片")).click()
            device.findObject(By.desc("测试图标")).click()
            assertEquals(2, taps.get())
            device.findObject(By.clazz("android.widget.CheckBox")).click()
            assertEquals(1, toggles.get())
            device.findObject(By.text("打开测试窗口")).click()
            assertTrue(device.wait(Until.hasObject(By.text("确认测试")), 10_000))
            device.findObject(By.text("确认测试")).click()
            assertEquals(4, taps.get())
            device.findObject(By.clazz("android.widget.EditText")).click()
            assertEquals(5, taps.get())
            device.findObject(By.clazz("android.widget.EditText")).text = "abc"
            assertEquals("输入文字时不重复触发应用触感", 5, taps.get())
        }
    }

    @Test fun draggingProvidesFeedbackWhenPickedUpAndMoved() {
        val starts = AtomicInteger()
        val steps = AtomicInteger()
        val feedback = object : ActionFeedback {
            override fun tap() = Unit
            override fun toggle(checked: Boolean) = Unit
            override fun dragStart() { starts.incrementAndGet() }
            override fun dragStep() { steps.incrementAndGet() }
        }
        val app = ApplicationProvider.getApplicationContext<ManmanApplication>()
        val today = LocalDate.now(AppTime.zone)
        val now = Instant.now()
        val tasks = listOf(
            WorkTask("feedback-a", "测试拖动甲", null, null, null, false, now, today, 0),
            WorkTask("feedback-b", "测试拖动乙", null, null, null, false, now, today, 1),
        )
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    val viewModel = androidx.compose.runtime.remember(app) { MainViewModel(app) }
                    ManmanTheme {
                        CompositionLocalProvider(LocalActionFeedback provides feedback) {
                            TaskAgenda(MainUiState(tasks = tasks), viewModel, today, onlyToday = true,
                                onTask = {}, modifier = Modifier.systemBarsPadding())
                        }
                    }
                }
            }
            val from = device.wait(Until.findObject(By.desc("拖动调整测试拖动乙的顺序")), 10_000).visibleCenter
            val to = device.wait(Until.findObject(By.desc("拖动调整测试拖动甲的顺序")), 10_000).visibleCenter
            device.drag(from.x, from.y, to.x, to.y, 60)
            device.waitForIdle()
            assertEquals(1, starts.get())
            assertEquals(1, steps.get())
        }
    }
}
