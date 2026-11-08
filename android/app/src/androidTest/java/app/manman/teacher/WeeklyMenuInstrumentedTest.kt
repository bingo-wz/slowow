package app.manman.teacher

import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import app.manman.teacher.data.ClassRepository
import app.manman.teacher.data.RecordCodec
import app.manman.teacher.data.RecordKind
import app.manman.teacher.domain.LunchRules
import app.manman.teacher.ui.ManmanTheme
import app.manman.teacher.ui.MenuScreen
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class WeeklyMenuInstrumentedTest {
    @Test fun weeklyImageCanBeSavedAndViewedWithoutRecognition() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<ManmanApplication>()
        val account = "weekly-menu-test-${UUID.randomUUID()}"
        val start = LocalDate.now(LunchRules.defaultZone).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val source = File(app.cacheDir, "weekly-menu-${UUID.randomUUID()}.png")
        val bitmap = Bitmap.createBitmap(32, 48, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GREEN) }
        source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val image = app.attachments.stage(Uri.fromFile(source))
        app.attachments.adopt(account, image)
        ClassRepository(app.database).saveMenuWeek(account, start, image)
        val records = app.database.records().snapshot(account).filterNot { it.deleted }
        val savedWeek = RecordCodec.menuWeek(records.single { it.kind == RecordKind.MENU_WEEK })
        assertEquals(start, savedWeek.start)
        assertEquals(image.id, savedWeek.attachmentId)
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val oldScale = device.executeShellCommand("settings get system font_scale").trim()
        device.executeShellCommand("settings put system font_scale 2.0")
        try {
            val screenState = mutableStateOf(MainUiState(restoring = false, accountId = account))
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    val model = MainViewModel(app)
                    activity.setContent { ManmanTheme { Scaffold { padding ->
                        Box(Modifier.fillMaxSize().padding(padding)) { MenuScreen(screenState.value, model) {} }
                    } } }
                }
                assertTrue(device.wait(Until.hasObject(By.text("本周还没有菜单")), 10_000))
                assertTrue(device.hasObject(By.text("添加图片")))
                assertTrue(!device.hasObject(By.textContains("识别")))
                scenario.onActivity { screenState.value = screenState.value.copy(menuWeeks = listOf(savedWeek), attachments = listOf(image)) }
                assertTrue(device.wait(Until.hasObject(By.desc("原图")), 10_000))
                assertTrue(device.hasObject(By.text("更换图片")))
                assertTrue(!device.hasObject(By.text("今天吃什么")))
                assertTrue(device.takeScreenshot(File(app.cacheDir, "weekly-menu-preview.png")))
            }
        } finally {
            if (oldScale == "null" || oldScale.isBlank()) device.executeShellCommand("settings delete system font_scale")
            else device.executeShellCommand("settings put system font_scale $oldScale")
            source.delete()
            bitmap.recycle()
        }
    }

    @Test fun selectedImageIsSavedThroughViewModel() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("menuFixture") == "1")
        val app = ApplicationProvider.getApplicationContext<ManmanApplication>()
        app.auth.restore()
        val account = requireNotNull(app.auth.session.value?.accountId)
        require(account.startsWith("release-fixture-"))
        val model = MainViewModel(app)
        val initial = withTimeout(10_000) { model.state.first { it.accountId == account && it.menuWeeks.isNotEmpty() } }
        val start = LocalDate.now(LunchRules.defaultZone).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val oldImage = initial.menuWeeks.single { it.start == start }.attachmentId
        val source = File(app.cacheDir, "selected-menu-${UUID.randomUUID()}.png")
        val bitmap = Bitmap.createBitmap(20, 30, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        try {
            source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            model.saveMenuImage(start, Uri.fromFile(source))
            val updated = withTimeout(10_000) {
                model.state.first { current -> current.menuWeeks.any { it.start == start && it.attachmentId != oldImage } }
            }
            val imageId = updated.menuWeeks.single { it.start == start }.attachmentId
            val meta = updated.attachments.single { it.id == imageId }
            val preview = app.attachments.preview(account, meta) { throw AssertionError("本机图片不应联网") }
            assertEquals(20, preview.width)
            assertEquals(30, preview.height)
            preview.recycle()
        } finally {
            source.delete()
            bitmap.recycle()
        }
    }
}
