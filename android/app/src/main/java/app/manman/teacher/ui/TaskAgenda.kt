package app.manman.teacher.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import app.manman.teacher.MainUiState
import app.manman.teacher.MainViewModel
import app.manman.teacher.data.WorkTask
import app.manman.teacher.domain.AppTime
import app.manman.teacher.domain.TaskOrdering
import kotlinx.coroutines.delay
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import java.time.Duration
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

@Composable
fun rememberToday(): LocalDate {
    var today by remember { mutableStateOf(LocalDate.now(AppTime.zone)) }
    LaunchedEffect(Unit) {
        while (true) {
            val now = ZonedDateTime.now(AppTime.zone)
            today = now.toLocalDate()
            val next = today.plusDays(1).atStartOfDay(AppTime.zone)
            delay(Duration.between(now, next).toMillis().coerceAtLeast(1_000))
        }
    }
    return today
}

private fun dateKey(day: LocalDate?) = day?.toString() ?: "unscheduled"

@Composable
fun TaskAgenda(
    state: MainUiState,
    viewModel: MainViewModel,
    today: LocalDate,
    onlyToday: Boolean,
    onTask: (String) -> Unit,
    modifier: Modifier = Modifier,
    header: @Composable () -> Unit = {},
    footer: LazyListScope.() -> Unit = {},
) {
    val grouped = state.tasks.groupBy(TaskOrdering::date)
    val days: List<LocalDate?> = if (onlyToday) listOf(today) else
        (grouped.keys.filterNotNull() + today).distinct().sorted() + if (null in grouped) listOf(null) else emptyList()
    var collapsed by rememberSaveable { mutableStateOf(arrayListOf<String>()) }
    var draggedOrder by remember { mutableStateOf<Map<String, List<String>>>(emptyMap()) }
    var dragging by remember { mutableStateOf(false) }
    LaunchedEffect(state.tasks) { if (!dragging) draggedOrder = emptyMap() }
    LaunchedEffect(state.error) { if (state.error != null && !dragging) draggedOrder = emptyMap() }

    fun tasksFor(day: LocalDate?): List<WorkTask> {
        val tasks = TaskOrdering.sorted(grouped[day].orEmpty())
        val order = draggedOrder[dateKey(day)] ?: return tasks
        val byId = tasks.associateBy(WorkTask::id)
        return order.mapNotNull(byId::get) + tasks.filterNot { it.id in order }
    }

    val listState = rememberLazyListState()
    val reorderState = rememberReorderableLazyListState(listState) { from, to ->
        val fromId = (from.key as? String)?.takeIf { it.startsWith("task:") }?.removePrefix("task:")
        val toId = (to.key as? String)?.takeIf { it.startsWith("task:") }?.removePrefix("task:")
        val source = state.tasks.firstOrNull { it.id == fromId }
        val target = state.tasks.firstOrNull { it.id == toId }
        if (source != null && target != null && TaskOrdering.date(source) == TaskOrdering.date(target)) {
            val day = TaskOrdering.date(source)
            val ids = tasksFor(day).map(WorkTask::id).toMutableList()
            val sourceIndex = ids.indexOf(source.id)
            val targetIndex = ids.indexOf(target.id)
            if (sourceIndex >= 0 && targetIndex >= 0 && sourceIndex != targetIndex) {
                ids.add(targetIndex, ids.removeAt(sourceIndex))
                draggedOrder = draggedOrder + (dateKey(day) to ids)
            }
        }
    }
    LazyColumn(modifier = modifier.fillMaxSize(), state = listState,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item(key = "agenda-header") { header() }
        days.forEach { day ->
            val key = dateKey(day)
            val tasks = tasksFor(day)
            val isCollapsed = !onlyToday && key in collapsed
            if (!onlyToday) item(key = "day:$key") {
                val label = when {
                    day == null -> "未安排日期"
                    day == today -> "${day.format(DateTimeFormatter.ofPattern("M月d日"))} · 今天"
                    day.isBefore(today) && tasks.any { !it.done } -> "${day.format(DateTimeFormatter.ofPattern("yyyy年M月d日"))} · 已过期"
                    else -> day.format(DateTimeFormatter.ofPattern("yyyy年M月d日"))
                }
                Row(Modifier.fillMaxWidth().semantics { stateDescription = if (isCollapsed) "已折叠" else "已展开" }.clickable {
                    collapsed = ArrayList(if (isCollapsed) collapsed - key else collapsed + key)
                }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(label, style = MaterialTheme.typography.titleMedium)
                        if (tasks.isNotEmpty()) Text("${tasks.count { !it.done }} 项待处理", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    LineIcon(if (isCollapsed) "down" else "up")
                }
            }
            if (!isCollapsed) {
                if (tasks.isEmpty() && day == today) item(key = "empty-today") {
                    Text("今天还没有待办", Modifier.padding(vertical = 12.dp), color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium)
                }
                items(tasks, key = { "task:${it.id}" }) { task ->
                    ReorderableItem(reorderState, key = "task:${task.id}") { _ ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = task.done, enabled = !state.busy,
                                onCheckedChange = { checked -> viewModel.setTaskDone(task.id, checked) },
                                modifier = Modifier.semantics { contentDescription = if (task.done) "取消完成${task.title}" else "完成${task.title}" })
                            Column(Modifier.weight(1f).clickable { onTask(task.id) }.padding(vertical = 8.dp)) {
                                Text(task.title, style = MaterialTheme.typography.bodyLarge,
                                    color = if (task.done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                                    textDecoration = if (task.done) TextDecoration.LineThrough else TextDecoration.None)
                                task.dueAt?.let {
                                    Text(it.atZone(AppTime.zone).format(DateTimeFormatter.ofPattern("HH:mm")),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                            Box(Modifier.size(48.dp).draggableHandle(
                                enabled = !state.busy,
                                onDragStarted = { dragging = true },
                                onDragStopped = {
                                    dragging = false
                                    draggedOrder[dateKey(TaskOrdering.date(task))]?.let { ids ->
                                        viewModel.reorderTasks(TaskOrdering.date(task), ids)
                                    }
                                },
                            ).semantics { contentDescription = "拖动调整${task.title}的顺序" }, contentAlignment = Alignment.Center) {
                                LineIcon("drag")
                            }
                        }
                    }
                }
            }
        }
        footer()
    }
}
