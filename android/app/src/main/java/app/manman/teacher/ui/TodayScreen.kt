package app.manman.teacher.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import app.manman.teacher.domain.TaskOrdering
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun TodayScreen(state: MainUiState, viewModel: MainViewModel, onSettings: () -> Unit, onMenu: () -> Unit,
    onWork: () -> Unit, onNotices: () -> Unit, onAddTask: () -> Unit, onTask: (String) -> Unit) {
    val today = rememberToday()
    val taskCount = state.tasks.count { TaskOrdering.date(it) == today }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(today.format(DateTimeFormatter.ofPattern("M月d日", Locale.SIMPLIFIED_CHINESE)),
                    style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(4.dp))
                Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.small) {
                    Text(today.format(DateTimeFormatter.ofPattern("EEEE", Locale.SIMPLIFIED_CHINESE)),
                        Modifier.padding(horizontal = 8.dp, vertical = 4.dp), style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }
            MenuSprout(onMenu)
            Spacer(Modifier.width(8.dp))
            Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.medium) {
                ActionIcon("notice", "学校通知", onNotices)
            }
            Spacer(Modifier.width(8.dp))
            Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.medium) {
                ActionIcon("settings", "设置", onSettings)
            }
        }
        Surface(modifier = Modifier.weight(1f).fillMaxWidth().padding(start = 8.dp, end = 8.dp, bottom = 8.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLowest, shape = MaterialTheme.shapes.large) {
            TaskAgenda(state, viewModel, today, onlyToday = true, onTask = onTask, header = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (state.pendingCount > 0 || state.conflicts.isNotEmpty()) {
                        val status = if (state.conflicts.isNotEmpty()) "${state.conflicts.size} 项同步冲突" else "${state.pendingCount} 项待同步"
                        Surface(onClick = feedbackClick(onSettings), color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.small) {
                            Text("$status  ›", Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
                        }
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        LineIcon("todo", color = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(8.dp))
                        FlowRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                            Text("今日待办", style = MaterialTheme.typography.titleMedium)
                            Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.small) {
                                Text("$taskCount 项", Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    color = MaterialTheme.colorScheme.onPrimaryContainer, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                        ActionIcon("list", "全部待办", onWork)
                        Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.medium) {
                            ActionIcon("plus", "新建待办", onAddTask)
                        }
                    }
                }
            })
        }
    }
}
