package app.manman.teacher

import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import app.manman.teacher.data.RecordKind
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LegacyClientSafetyInstrumentedTest {
    @Test fun olderClientDoesNotReplaceWeekdaySpecificSchedules() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("versionFixture") == "1")
        val app = ApplicationProvider.getApplicationContext<ManmanApplication>()
        val prefs = app.getSharedPreferences("version-check-fixture", 0)
        fun value(key: String): String = requireNotNull(prefs.getString(key, null))
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        ActivityScenario.launch(MainActivity::class.java).use {
            assertTrue(device.wait(Until.hasObject(By.text("今日待办")), 15_000))
            delay(2500)
            val owner = value("owner")
            val rule = app.database.records().find(owner, RecordKind.SETTINGS, value("ruleId"))!!
            val task = app.database.records().find(owner, RecordKind.TASK, value("taskId"))!!
            val metadata = app.database.records().find(owner, RecordKind.SETTINGS, value("metadataId"))!!
            assertEquals(value("rule"), rule.payload)
            assertEquals(value("task"), task.payload)
            assertEquals(value("metadata"), metadata.payload)
            assertTrue(!rule.deleted && !task.deleted)
        }
    }
}
