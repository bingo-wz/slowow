package app.manman.teacher

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.AdaptiveIconDrawable
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class LauncherIconInstrumentedTest {
    @Test fun launcherUsesAnAdaptiveIcon() {
        val context = ApplicationProvider.getApplicationContext<ManmanApplication>()
        val drawable = context.packageManager.getApplicationIcon(context.packageName)
        assertTrue(drawable is AdaptiveIconDrawable)
        val image = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
        drawable.setBounds(0, 0, image.width, image.height)
        drawable.draw(Canvas(image))
        File(context.cacheDir, "launcher-preview.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        image.recycle()
    }
}
