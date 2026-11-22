package app.manman.teacher

import androidx.activity.compose.setContent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import app.manman.teacher.ui.ManmanTheme
import app.manman.teacher.ui.TaskCelebration
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TaskCelebrationInstrumentedTest {
    @Test fun completionFeedbackAppearsAgainForSeparateActions() {
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val previous = device.executeShellCommand("settings get global animator_duration_scale").trim()
        device.executeShellCommand("settings put global animator_duration_scale 3")
        val sequence = mutableIntStateOf(0)
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    activity.setContent {
                        ManmanTheme {
                            Box(Modifier.fillMaxSize()) {
                                TextButton(onClick = { sequence.intValue++ }, modifier = Modifier.align(Alignment.Center)) { Text("完成测试") }
                                TaskCelebration(sequence.intValue, if (sequence.intValue == 1) 1 else 3, Modifier.align(Alignment.BottomCenter))
                            }
                        }
                    }
                }
                assertTrue(device.wait(Until.hasObject(By.text("完成测试")), 10_000))
                scenario.onActivity { sequence.intValue++ }
                assertTrue(waitForDescription("已完成，礼花庆祝", present = true, timeout = 3_000))
                assertTrue(waitForDescription("已完成，礼花庆祝", present = false, timeout = 8_000))
                scenario.onActivity { sequence.intValue++ }
                assertTrue(waitForDescription("已完成 3 项，礼花庆祝", present = true, timeout = 3_000))
            }
        } finally {
            if (previous == "null" || previous.isBlank()) device.executeShellCommand("settings delete global animator_duration_scale")
            else device.executeShellCommand("settings put global animator_duration_scale $previous")
        }
    }

    private fun waitForDescription(description: String, present: Boolean, timeout: Long): Boolean {
        val end = android.os.SystemClock.elapsedRealtime() + timeout
        while (android.os.SystemClock.elapsedRealtime() < end) {
            val root = InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow
            if (hasDescription(root, description) == present) return true
            android.os.SystemClock.sleep(30)
        }
        return false
    }

    private fun hasDescription(node: AccessibilityNodeInfo?, description: String): Boolean {
        if (node == null) return false
        if (node.contentDescription?.toString() == description) return true
        return (0 until node.childCount).any { hasDescription(node.getChild(it), description) }
    }
}
