package app.manman.teacher

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
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
import java.io.File

@RunWith(AndroidJUnit4::class)
class TaskCelebrationInstrumentedTest {
    @Test fun completionFeedbackAppearsAgainForSeparateActions() {
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val app = ApplicationProvider.getApplicationContext<ManmanApplication>()
        val previous = device.executeShellCommand("settings get global animator_duration_scale").trim()
        device.executeShellCommand("settings put global animator_duration_scale 3")
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    activity.setContent {
                        var sequence by remember { mutableIntStateOf(0) }
                        ManmanTheme {
                            Box(Modifier.fillMaxSize()) {
                                TextButton(onClick = { sequence++ }, modifier = Modifier.align(Alignment.Center)) { Text("完成测试") }
                                TaskCelebration(sequence, if (sequence == 1) 1 else 3, Modifier.align(Alignment.BottomCenter))
                            }
                        }
                    }
                }
                assertTrue(device.wait(Until.hasObject(By.text("完成测试")), 10_000))
                device.findObject(By.text("完成测试")).click()
                assertTrue(device.wait(Until.hasObject(By.text("完成啦")), 3_000))
                assertTrue(device.takeScreenshot(File(app.cacheDir, "task-celebration.png")))
                assertTrue(device.wait(Until.gone(By.text("完成啦")), 8_000))
                device.findObject(By.text("完成测试")).click()
                assertTrue(device.wait(Until.hasObject(By.text("完成啦 · 3 项")), 3_000))
            }
        } finally {
            if (previous == "null" || previous.isBlank()) device.executeShellCommand("settings delete global animator_duration_scale")
            else device.executeShellCommand("settings put global animator_duration_scale $previous")
        }
    }
}
