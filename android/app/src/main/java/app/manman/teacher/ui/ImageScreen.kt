package app.manman.teacher.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.manman.teacher.MainUiState
import app.manman.teacher.ManmanApplication
import kotlinx.coroutines.CancellationException

@Composable
fun ImageScreen(state: MainUiState, imageId: String, back: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        PageHeader("查看原图", "双指缩放", back = back)
        AttachmentImage(state, imageId, Modifier.weight(1f))
    }
}

@Composable
fun AttachmentImage(state: MainUiState, imageId: String, modifier: Modifier = Modifier) {
    val application = LocalContext.current.applicationContext as ManmanApplication
    val image = state.attachments.firstOrNull { it.id == imageId }
    val account = state.accountId
    var bitmap by remember(imageId) { mutableStateOf<android.graphics.Bitmap?>(null) }
    var failed by remember(imageId) { mutableStateOf(false) }
    var retry by remember(imageId) { mutableIntStateOf(0) }
    var scale by remember(imageId) { mutableStateOf(1f) }
    var offset by remember(imageId) { mutableStateOf(Offset.Zero) }
    LaunchedEffect(image, account, retry) {
        if (image != null && account != null) {
            failed = false
            try {
                bitmap = application.attachments.preview(account, image) { application.auth.currentAccessToken(account) }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                failed = true
            }
        }
    }
    Box(modifier.fillMaxWidth().clipToBounds(), contentAlignment = Alignment.Center) {
        when {
            bitmap != null -> Image(bitmap!!.asImageBitmap(), contentDescription = "原图", contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().pointerInput(imageId) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        scale = (scale * zoom).coerceIn(1f, 4f)
                        offset = if (scale == 1f) Offset.Zero else offset + pan
                    }
                }.graphicsLayer(scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y))
            failed || image == null -> Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("暂时无法查看原图，请检查网络和同步状态。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = { retry++ }) { Text("重试") }
            }
            else -> CircularProgressIndicator()
        }
    }
}
