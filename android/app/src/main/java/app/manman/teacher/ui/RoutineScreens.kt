package app.manman.teacher.ui

import android.app.TimePickerDialog
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.manman.teacher.MainUiState
import app.manman.teacher.MainViewModel
import app.manman.teacher.data.RoutineRule
import app.manman.teacher.data.RoutineVersion
import app.manman.teacher.domain.AppTime
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

private val routineDayNames = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

data class RoutinePreset(val key: String, val title: String, val weekdays: Set<Int>, val showMealList: Boolean = false)

val routinePresets = listOf(
    RoutinePreset("flag", "升旗", setOf(1)),
    RoutinePreset("morning", "早读晨检", emptySet()),
    RoutinePreset("lunch", "午餐核销", emptySet(), showMealList = true),
    RoutinePreset("break", "大课间", setOf(2, 3, 4)),
)

private fun routineDays(days: Set<Int>): String = (1..7).filter { it in days }
    .joinToString("、") { routineDayNames[it - 1] }

@Composable
fun RoutineListScreen(state: MainUiState, back: () -> Unit, onAdd: (String) -> Unit, onEdit: (String) -> Unit) {
    Column(Modifier.fillMaxSize()) {
        PageHeader("固定安排", "按星期加入待办", back = back, action = { ActionIcon("plus", "添加固定安排", { onAdd("") }) })
        LazyColumn(Modifier.weight(1f), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (state.routines.isEmpty()) item {
                Text("还没有固定安排。选择常用事项或点右上角添加。", color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium)
            }
            items(state.routines, key = { "routine:${it.id}" }) { rule ->
                val version = rule.current
                Block(onClick = { onEdit(rule.id) }) {
                    Text(version.title, style = MaterialTheme.typography.titleMedium)
                    val time = if (version.start == null || version.end == null) "未设置时间段" else "${version.start}—${version.end}"
                    Text("${routineDays(version.weekdays)} · $time" + if (version.enabled) "" else " · 已暂停",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            val missingPresets = routinePresets.filter { preset -> state.routines.none { it.current.title == preset.title } }
            if (missingPresets.isNotEmpty()) {
                item { SectionHeading("常用安排") }
                items(missingPresets, key = { "preset:${it.key}" }) { preset ->
                    Block(onClick = { onAdd(preset.key) }) {
                        Text(preset.title, style = MaterialTheme.typography.bodyLarge)
                        Text(if (preset.weekdays.isEmpty()) "选择星期和时间" else "${routineDays(preset.weekdays)} · 可修改",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            item { Spacer(Modifier.height(16.dp)) }
        }
    }
}

@Composable
fun RoutineEditorScreen(state: MainUiState, viewModel: MainViewModel, current: RoutineRule?, editingId: String?, preset: RoutinePreset?, back: () -> Unit) {
    var baseline by rememberSaveable(editingId, preset?.key) { mutableStateOf(current) }
    LaunchedEffect(editingId, current) {
        if (editingId != null && baseline == null && current != null) baseline = current
    }
    if (editingId != null && baseline == null) {
        Column(Modifier.fillMaxSize()) {
            PageHeader("编辑固定安排", back = back)
            Text("正在读取固定安排；如果已在其他设备删除，请返回查看。", Modifier.padding(16.dp))
        }
        return
    }
    val existing = baseline
    val context = LocalContext.current
    val original = existing?.current
    var title by rememberSaveable(existing?.id, preset?.key) { mutableStateOf(original?.title ?: preset?.title.orEmpty()) }
    var weekdays by rememberSaveable(existing?.id, preset?.key) {
        mutableStateOf(ArrayList(original?.weekdays ?: preset?.weekdays.orEmpty()))
    }
    var start by rememberSaveable(existing?.id, preset?.key) { mutableStateOf(original?.start?.toString().orEmpty()) }
    var end by rememberSaveable(existing?.id, preset?.key) { mutableStateOf(original?.end?.toString().orEmpty()) }
    var enabled by rememberSaveable(existing?.id, preset?.key) { mutableStateOf(original?.enabled ?: true) }
    var showMealList by rememberSaveable(existing?.id, preset?.key) { mutableStateOf(original?.showMealList ?: preset?.showMealList ?: false) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmBack by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val dirty = title != (original?.title ?: preset?.title.orEmpty()) || weekdays.toSet() != (original?.weekdays ?: preset?.weekdays.orEmpty()) ||
        start != original?.start?.toString().orEmpty() || end != original?.end?.toString().orEmpty() ||
        enabled != (original?.enabled ?: true) || showMealList != (original?.showMealList ?: preset?.showMealList ?: false)
    fun leave() { if (dirty) confirmBack = true else back() }
    BackHandler { leave() }
    fun chooseRange() {
        val initialStart = start.takeIf(String::isNotBlank)?.let(LocalTime::parse) ?: LocalTime.of(8, 0)
        val initialEnd = end.takeIf(String::isNotBlank)?.let(LocalTime::parse) ?: initialStart.plusMinutes(40)
        TimePickerDialog(context, { _, hour, minute ->
            val selectedStart = LocalTime.of(hour, minute)
            TimePickerDialog(context, { _, endHour, endMinute ->
                val selectedEnd = LocalTime.of(endHour, endMinute)
                if (selectedEnd <= selectedStart) error = "结束时间应晚于开始时间。"
                else { start = selectedStart.toString(); end = selectedEnd.toString(); error = null }
            }, initialEnd.hour, initialEnd.minute, true).show()
        }, initialStart.hour, initialStart.minute, true).show()
    }
    Column(Modifier.fillMaxSize()) {
        PageHeader(if (existing == null) "添加固定安排" else "编辑固定安排", back = ::leave,
            action = if (existing == null) null else { { ActionIcon("delete", "删除固定安排", { confirmDelete = true }, enabled = !state.busy) } })
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            QuietTextField(title, { title = it.take(100) }, "安排名称")
            Block {
                Text("每周哪几天", style = MaterialTheme.typography.titleMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    routineDayNames.forEachIndexed { index, name ->
                        val day = index + 1
                        FilterChip(selected = day in weekdays, onClick = feedbackClick {
                            weekdays = ArrayList(if (day in weekdays) weekdays - day else weekdays + day)
                        }, label = { Text(name) })
                    }
                }
                if (weekdays.isEmpty()) Text("请选择至少一天。", style = MaterialTheme.typography.bodySmall)
            }
            Block {
                Text("时间段", style = MaterialTheme.typography.titleMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FeedbackTextButton(onClick = ::chooseRange) {
                        Text(if (start.isEmpty()) "设置时间段" else "$start—$end  ›")
                    }
                    if (start.isNotEmpty()) FeedbackTextButton(onClick = { start = ""; end = ""; error = null }) { Text("清除") }
                }
                Text(if (start.isEmpty()) "不设置时间也可以手动完成。" else "结束后下次打开应用会自动完成。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                error?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
            Block {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("启用安排", style = MaterialTheme.typography.bodyLarge)
                        Text("假期可暂停，恢复后继续。", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = enabled, onCheckedChange = feedbackToggle { enabled = it },
                        modifier = Modifier.semantics { contentDescription = "启用固定安排" })
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = showMealList, onCheckedChange = feedbackToggle { showMealList = it },
                        modifier = Modifier.semantics { contentDescription = "关联不在校用餐名单" })
                    Text("关联不在校用餐名单", style = MaterialTheme.typography.bodyMedium)
                }
                Text("更改从今天起生效；历史、已完成及单独修改过的事项会保留。", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        MainButton("保存固定安排", {
            val today = LocalDate.now(AppTime.zone)
            val version = RoutineVersion(today, title.trim(), weekdays.toSet(), start.takeIf(String::isNotBlank)?.let(LocalTime::parse),
                end.takeIf(String::isNotBlank)?.let(LocalTime::parse), enabled, showMealList)
            val rule = if (existing == null) RoutineRule(UUID.randomUUID().toString(), Instant.now(), listOf(version))
            else existing.copy(versions = existing.versions.filter { it.effectiveFrom < today } + version)
            viewModel.saveRoutine(rule, existing, back)
        }, enabled = title.isNotBlank() && weekdays.isNotEmpty() && error == null && !state.busy,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp))
        Spacer(Modifier.height(16.dp))
    }
    if (confirmBack) AlertDialog(onDismissRequest = feedbackClick { confirmBack = false },
        title = { Text("固定安排还没有保存") }, text = { Text("离开后，未保存的内容不会保留。") },
        confirmButton = { FeedbackTextButton(onClick = { confirmBack = false; back() }) { Text("放弃修改") } },
        dismissButton = { FeedbackTextButton(onClick = { confirmBack = false }) { Text("继续编辑") } })
    if (confirmDelete && existing != null) AlertDialog(onDismissRequest = feedbackClick { confirmDelete = false },
        title = { Text("删除固定安排？") },
        text = { Text("历史、已完成和单独修改过的事项会保留；今后未完成的重复事项会移除。") },
        confirmButton = { FeedbackTextButton(onClick = { confirmDelete = false; viewModel.deleteRoutine(existing.id, existing, back) },
            enabled = !state.busy) { Text("删除") } },
        dismissButton = { FeedbackTextButton(onClick = { confirmDelete = false }) { Text("取消") } })
}
