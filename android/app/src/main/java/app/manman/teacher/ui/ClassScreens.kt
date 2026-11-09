package app.manman.teacher.ui

import androidx.compose.foundation.clickable
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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.manman.teacher.MainUiState
import app.manman.teacher.MainViewModel
import app.manman.teacher.domain.Student
import java.util.Locale

@Composable
fun ClassScreen(state: MainUiState, back: () -> Unit, onImport: () -> Unit, onStudent: (String) -> Unit, onMarking: () -> Unit) {
    val activeStudents = state.students.filter(Student::active)
    Column(Modifier.fillMaxSize()) {
        PageHeader("班级", "${activeStudents.size} 位学生", back = back, action = { ActionIcon("plus", "添加学生", onImport) })
        if (activeStudents.isEmpty()) {
            Column(Modifier.padding(horizontal = 16.dp)) {
                EmptyHint("还没有学生名单", "从 Excel 复制姓名，粘贴后就可以一起添加。", "添加学生", onAction = onImport)
            }
        } else {
            LazyColumn(Modifier.weight(1f), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    Block(onClick = onMarking) {
                        Text("班级标记", style = MaterialTheme.typography.titleMedium)
                        Text("自定义事项，快速记录每位学生的状态。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("开始记录  ›", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
                    }
                    Spacer(Modifier.height(8.dp))
                    SectionHeading("学生名单", "添加", onImport)
                }
                items(activeStudents, key = Student::id) { student ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onStudent(student.id) }.padding(horizontal = 8.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(student.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                        val days = student.regularSchoolLunchDays()
                        if (days.isEmpty()) SmallBadge("固定不在校用餐")
                        else if (days.size < 7) SmallBadge("按星期用餐")
                        Text("  ›", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
fun ImportStudentsScreen(state: MainUiState, viewModel: MainViewModel, back: () -> Unit) {
    var input by rememberSaveable { mutableStateOf("") }
    val names = remember(input) { input.split('\n', '\r', '\t').map(String::trim).filter(String::isNotBlank) }
    val duplicates = remember(names, state.students) {
        (names + state.students.filter(Student::active).map(Student::name)).groupingBy { it }.eachCount().filterValues { it > 1 }.keys
    }
    Column(Modifier.fillMaxSize()) {
        PageHeader("添加学生", "粘贴一列姓名，每行一位", back = back)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            QuietTextField(input, { input = it }, "学生姓名", singleLine = false, modifier = Modifier.height(200.dp))
            if (names.isNotEmpty()) {
                SectionHeading("预览 · ${names.size} 人")
                if (duplicates.isNotEmpty()) {
                    Text("发现重名：${duplicates.joinToString("、")}。确认后仍可保存。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Block {
                    names.forEachIndexed { index, name ->
                        Text("${index + 1}.  $name", style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        }
        MainButton("保存名单", { viewModel.addStudents(names, back) }, enabled = names.isNotEmpty() && !state.busy,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp))
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
fun StudentScreen(student: Student, state: MainUiState, viewModel: MainViewModel, back: () -> Unit) {
    var name by rememberSaveable(student.id) { mutableStateOf(student.name) }
    val initialDays = student.regularSchoolLunchDays()
    var eats by rememberSaveable(student.id) { mutableStateOf(initialDays.isNotEmpty()) }
    var excludedMask by rememberSaveable(student.id) {
        mutableIntStateOf(if (initialDays.isEmpty()) 0 else (1..7).filterNot(initialDays::contains).fold(0) { mask, day -> mask or (1 shl day) })
    }
    var initial by rememberSaveable(student.id) { mutableStateOf(student.sortInitial.orEmpty()) }
    var editInitial by rememberSaveable(student.id) { mutableStateOf(initial.isNotEmpty()) }
    var confirmRemove by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        PageHeader("学生信息", back = back)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            QuietTextField(name, { name = it.take(50) }, "姓名")
            Block {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("通常在校吃午饭", style = MaterialTheme.typography.bodyLarge)
                        Text("关闭后，每天都计入不在校用餐。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = eats, onCheckedChange = { eats = it })
                }
            }
            if (eats) {
                Block {
                    Text("每周固定不在校用餐", style = MaterialTheme.typography.titleMedium)
                    Text("选择固定不在校用餐的星期；请假会自动计入当天名单。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日").forEachIndexed { index, label ->
                            val bit = 1 shl (index + 1)
                            FilterChip(selected = excludedMask and bit != 0, onClick = { excludedMask = excludedMask xor bit },
                                label = { Text(label) }, border = null,
                                colors = FilterChipDefaults.filterChipColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer))
                        }
                    }
                }
            }
            TextButton(onClick = { editInitial = !editInitial }) { Text(if (editInitial) "收起姓名首字母设置" else "调整姓名首字母") }
            if (editInitial) {
                QuietTextField(initial, { value -> initial = value.uppercase(Locale.ROOT).filter { it in 'A'..'Z' }.take(1) },
                    "首字母（选填）", keyboardType = KeyboardType.Ascii)
                Text("姓氏是多音字时可填写；留空时自动按拼音分组。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = { confirmRemove = true }) { Text("从当前班级移除") }
        }
        MainButton("保存", {
            val days = if (eats) (1..7).filter { excludedMask and (1 shl it) == 0 }.toSet() else emptySet()
            viewModel.saveStudent(student.copy(name = name, normallyEatsAtSchool = days.isNotEmpty(), schoolLunchDays = days,
                sortInitial = initial.ifEmpty { null }), back)
        },
            enabled = name.isNotBlank() && !state.busy, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp))
        Spacer(Modifier.height(16.dp))
    }
    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text("移出当前班级？") },
            text = { Text("历史班级标记会保留当时的姓名。") },
            confirmButton = { TextButton(onClick = { confirmRemove = false; viewModel.saveStudent(student.copy(active = false), back) }) { Text("移除") } },
            dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text("取消") } },
        )
    }
}
