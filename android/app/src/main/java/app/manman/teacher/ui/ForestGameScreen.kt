package app.manman.teacher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.random.Random

@Composable
internal fun ForestGameScreen(back: () -> Unit) {
    var seconds by remember { mutableIntStateOf(0) }
    var points by remember { mutableIntStateOf(0) }
    var active by remember { mutableIntStateOf(-1) }
    var started by remember { mutableStateOf(false) }
    var running by remember { mutableStateOf(false) }
    LaunchedEffect(running) {
        if (!running) return@LaunchedEffect
        val end = android.os.SystemClock.elapsedRealtime() + 20_000
        while (true) {
            val remaining = end - android.os.SystemClock.elapsedRealtime()
            if (remaining <= 0) break
            seconds = ((remaining + 999) / 1_000).toInt()
            active = (active.coerceAtLeast(0) + Random.nextInt(1, 9)) % 9
            delay(minOf(950, remaining))
        }
        seconds = 0
        active = -1
        running = false
    }
    Column(Modifier.fillMaxSize()) {
        PageHeader("地鼠乐园", back = back)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("小地鼠冒头时，点一下给它一颗星星。", style = MaterialTheme.typography.bodyMedium)
            Text(if (running) "还剩 $seconds 秒 · $points 颗星星" else if (started) "这回合送出了 $points 颗星星" else "每回合 20 秒",
                style = MaterialTheme.typography.titleMedium)
            repeat(3) { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    repeat(3) { column ->
                        val index = row * 3 + column
                        val shown = index == active
                        Box(Modifier.weight(1f).heightIn(min = 64.dp).background(
                            if (shown) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
                            MaterialTheme.shapes.medium).semantics {
                                contentDescription = if (shown) "${index + 1} 号洞，小地鼠出现" else "${index + 1} 号洞"
                            }.feedbackClickable(enabled = shown && running) {
                                if (active == index) { points++; active = -1 }
                            }, contentAlignment = Alignment.Center) {
                            Text(if (shown) "🐹" else "·", style = MaterialTheme.typography.headlineSmall)
                        }
                    }
                }
            }
            MainButton(if (running) "进行中" else if (started) "再玩一回合" else "开始", {
                points = 0
                seconds = 20
                started = true
                running = true
            }, enabled = !running, modifier = Modifier.fillMaxWidth())
        }
    }
}
