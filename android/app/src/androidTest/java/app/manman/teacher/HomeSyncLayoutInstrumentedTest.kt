package app.manman.teacher

import androidx.activity.compose.setContent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.runtime.mutableStateOf
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import app.manman.teacher.data.WorkTask
import app.manman.teacher.domain.AppTime
import app.manman.teacher.sync.SyncPhase
import app.manman.teacher.ui.ManmanTheme
import app.manman.teacher.ui.TodayScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant

@RunWith(AndroidJUnit4::class)
class HomeSyncLayoutInstrumentedTest {
    @Test fun syncingDoesNotMoveTodayTasksAndProblemsHaveASmallIndicator() {
        val app = ApplicationProvider.getApplicationContext<ManmanApplication>()
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val now = Instant.now()
        val task = WorkTask("fictional-sync-task", "虚构待办", null, null, null, false, now,
            now.atZone(AppTime.zone).toLocalDate())
        val displayed = mutableStateOf(MainUiState(accountId = "fictional", tasks = listOf(task)))
        val model = MainViewModel(app)
        val oldScale = device.executeShellCommand("settings get system font_scale").trim()
        device.executeShellCommand("settings put system font_scale 1.0")
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    activity.setContent {
                        ManmanTheme {
                            TodayScreen(displayed.value, model, onSettings = {}, onMenu = {}, onWork = {},
                                onNotices = {}, onAddTask = {}, onTask = {})
                        }
                    }
                }
                assertTrue(device.wait(Until.hasObject(By.text("虚构待办")), 10_000))
                val titleTop = device.findObject(By.text("今日待办")).visibleBounds.top
                val taskTop = device.findObject(By.text("虚构待办")).visibleBounds.top
                scenario.onActivity { displayed.value = displayed.value.copy(pendingCount = 1, syncPhase = SyncPhase.SYNCING) }
                device.waitForIdle()
                assertFalse(device.hasObject(By.textContains("项待同步")))
                assertEquals(titleTop, device.findObject(By.text("今日待办")).visibleBounds.top)
                assertEquals(taskTop, device.findObject(By.text("虚构待办")).visibleBounds.top)
                scenario.onActivity { displayed.value = displayed.value.copy(syncPhase = SyncPhase.WAITING) }
                var status: CharSequence? = null
                repeat(50) {
                    if (status == null) {
                        status = findSettings(InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow)?.stateDescription
                        if (status == null) android.os.SystemClock.sleep(100)
                    }
                }
                assertEquals("同步需要留意，可在设置中查看", status?.toString())
                assertEquals(titleTop, device.findObject(By.text("今日待办")).visibleBounds.top)
                assertEquals(taskTop, device.findObject(By.text("虚构待办")).visibleBounds.top)
                assertFalse(device.hasObject(By.textContains("项待同步")))
            }
        } finally {
            if (oldScale == "null" || oldScale.isBlank()) device.executeShellCommand("settings delete system font_scale")
            else device.executeShellCommand("settings put system font_scale $oldScale")
        }
    }

    private fun findSettings(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.contentDescription?.toString() == "设置") return node
        for (index in 0 until node.childCount) findSettings(node.getChild(index))?.let { return it }
        return null
    }
}
