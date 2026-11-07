package app.manman.teacher

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.manman.teacher.fonts.AppFont
import app.manman.teacher.fonts.AppFonts
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class AppFontsInstrumentedTest {
    @Test fun eachFontDownloadsVerifiesAndCanBeReusedOffline() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("testFonts") == "1")
        val context = ApplicationProvider.getApplicationContext<ManmanApplication>()
        context.getSharedPreferences("manman_font", 0).edit().putString("selected", AppFont.SYSTEM.name).commit()
        val cache = File(context.filesDir, "fonts").apply { mkdirs() }
        File(cache, "cute.ttf").writeBytes(byteArrayOf(0, 1, 2))
        val fonts = AppFonts(context, BuildConfig.SYNC_BASE_URL)
        for (font in listOf(AppFont.KAI, AppFont.CUTE, AppFont.HANDWRITTEN)) {
            fonts.select(font)
            val state = withTimeout(90_000) { fonts.state.first { it.selected == font || it.error != null } }
            assertNull(state.error)
            assertNotNull(state.family)
        }
        fonts.select(AppFont.SYSTEM)
        withTimeout(5_000) { fonts.state.first { it.selected == AppFont.SYSTEM } }
        val offline = AppFonts(context, "https://127.0.0.1:1")
        offline.select(AppFont.CUTE)
        val cached = withTimeout(10_000) { offline.state.first { it.selected == AppFont.CUTE || it.error != null } }
        assertNull(cached.error)
        assertTrue(cached.family != null)
        val reopened = AppFonts(context, "https://127.0.0.1:1")
        val restored = withTimeout(10_000) { reopened.state.first { it.selected == AppFont.CUTE } }
        assertNotNull(restored.family)
        File(cache, "kai.ttf").writeBytes(byteArrayOf(0, 1, 2))
        reopened.select(AppFont.KAI)
        val failure = withTimeout(10_000) { reopened.state.first { it.error != null } }
        assertEquals(AppFont.CUTE, failure.selected)
        assertNotNull(failure.family)
        reopened.select(AppFont.SYSTEM)
        withTimeout(5_000) { reopened.state.first { it.selected == AppFont.SYSTEM } }
        Unit
    }
}
