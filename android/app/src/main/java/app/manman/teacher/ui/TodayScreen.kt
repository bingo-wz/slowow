package app.manman.teacher.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.manman.teacher.MainUiState
import app.manman.teacher.MainViewModel
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun TodayScreen(state: MainUiState, viewModel: MainViewModel, onSettings: () -> Unit, onMenu: () -> Unit,
    onWork: () -> Unit, onNotices: () -> Unit, onAddTask: () -> Unit, onTask: (String) -> Unit) {
    val today = rememberToday()
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(today.format(DateTimeFormatter.ofPattern("M月d日\nEEEE", Locale.SIMPLIFIED_CHINESE)),
                Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall)
            MenuSprout(onMenu)
            Spacer(Modifier.width(8.dp))
            ActionIcon("notice", "学校通知", onNotices)
            ActionIcon("settings", "设置", onSettings)
        }
        TaskAgenda(state, viewModel, today, onlyToday = true, onTask = onTask, modifier = Modifier.weight(1f), header = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.pendingCount > 0 || state.conflicts.isNotEmpty()) {
                    val status = if (state.conflicts.isNotEmpty()) "${state.conflicts.size} 项同步冲突" else "${state.pendingCount} 项待同步"
                    Surface(onClick = onSettings, color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.small) {
                        Text("$status  ›", Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
                    }
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("今日待办", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    ActionIcon("list", "全部待办", onWork)
                    ActionIcon("plus", "新建待办", onAddTask)
                }
            }
        })
    }
}
