package app.manman.teacher

import android.os.Bundle
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.MutableStateFlow
import app.manman.teacher.ui.ManmanApp
import app.manman.teacher.ui.ManmanTheme

class MainActivity : ComponentActivity() {
    private val sharedContent = MutableStateFlow<IncomingShare?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sharedContent.value = if (savedInstanceState != null) {
            val text = savedInstanceState.getString("shared_text")
            val images = savedInstanceState.getStringArrayList("shared_images").orEmpty()
            if (text != null || images.isNotEmpty()) IncomingShare(text, images) else null
        } else readShare(intent)
        enableEdgeToEdge()
        setContent {
            val fontState by (application as ManmanApplication).fonts.state.collectAsStateWithLifecycle()
            ManmanTheme(fontState.family) {
                val model: MainViewModel = viewModel(factory = MainViewModel.factory(application as ManmanApplication))
                val incoming by sharedContent.collectAsStateWithLifecycle()
                ManmanApp(model, incoming) { sharedContent.value = null }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        readShare(intent)?.let { sharedContent.value = it }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("shared_text", sharedContent.value?.text)
        outState.putStringArrayList("shared_images", ArrayList(sharedContent.value?.images.orEmpty()))
        super.onSaveInstanceState(outState)
    }

    @Suppress("DEPRECATION")
    private fun readShare(intent: Intent?): IncomingShare? {
        if (intent?.action !in setOf(Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE)) return null
        val text = intent?.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.takeIf(String::isNotBlank)
        val images = mutableListOf<Uri>()
        if (intent?.type?.startsWith("image/") == true) {
            if (intent.action == Intent.ACTION_SEND) {
                val value = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                    else intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                if (value != null) images.add(value)
            } else {
                val values = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
                    else intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
                images.addAll(values.orEmpty())
            }
            intent.clipData?.let { clip -> for (index in 0 until clip.itemCount) clip.getItemAt(index).uri?.let(images::add) }
        }
        return if (text == null && images.isEmpty()) null else IncomingShare(text, images.distinct().map(Uri::toString))
    }
}

data class IncomingShare(val text: String?, val images: List<String>)
