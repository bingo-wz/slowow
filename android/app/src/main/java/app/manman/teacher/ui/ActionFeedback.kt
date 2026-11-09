package app.manman.teacher.ui

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalView

interface ActionFeedback {
    fun tap()
    fun toggle(checked: Boolean)
    fun dragStart()
    fun dragStep()
}

private class SystemActionFeedback(private val view: View) : ActionFeedback {
    override fun tap() { view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK) }

    override fun toggle(checked: Boolean) {
        val effect = if (Build.VERSION.SDK_INT >= 34) {
            if (checked) HapticFeedbackConstants.TOGGLE_ON else HapticFeedbackConstants.TOGGLE_OFF
        } else HapticFeedbackConstants.CLOCK_TICK
        view.performHapticFeedback(effect)
    }

    override fun dragStart() {
        val effect = if (Build.VERSION.SDK_INT >= 34) HapticFeedbackConstants.DRAG_START else HapticFeedbackConstants.LONG_PRESS
        view.performHapticFeedback(effect)
    }

    override fun dragStep() {
        val effect = if (Build.VERSION.SDK_INT >= 34) HapticFeedbackConstants.SEGMENT_FREQUENT_TICK else HapticFeedbackConstants.CLOCK_TICK
        view.performHapticFeedback(effect)
    }
}

val LocalActionFeedback = staticCompositionLocalOf<ActionFeedback?> { null }

@Composable
fun rememberActionFeedback(): ActionFeedback {
    val view = LocalView.current
    val system = remember(view) { SystemActionFeedback(view) }
    return LocalActionFeedback.current ?: system
}

@Composable
fun feedbackClick(onClick: () -> Unit): () -> Unit {
    val feedback = rememberActionFeedback()
    return { feedback.tap(); onClick() }
}

@Composable
fun feedbackToggle(onChange: (Boolean) -> Unit): (Boolean) -> Unit {
    val feedback = rememberActionFeedback()
    return { checked -> feedback.toggle(checked); onChange(checked) }
}

@Composable
fun Modifier.feedbackClickable(enabled: Boolean = true, onClick: () -> Unit): Modifier {
    val feedback = rememberActionFeedback()
    return clickable(enabled = enabled) { feedback.tap(); onClick() }
}

@Composable
fun Modifier.feedbackOnFocus(): Modifier {
    val feedback = rememberActionFeedback()
    var focused by remember { mutableStateOf(false) }
    return onFocusChanged {
        if (it.isFocused && !focused) feedback.tap()
        focused = it.isFocused
    }
}

@Composable
fun FeedbackTextButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    TextButton(onClick = feedbackClick(onClick), modifier = modifier, enabled = enabled, content = content)
}
