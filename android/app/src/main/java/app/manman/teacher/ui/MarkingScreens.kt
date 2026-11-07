package app.manman.teacher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.manman.teacher.MainUiState
import app.manman.teacher.MainViewModel
import app.manman.teacher.data.MarkingRecord
import app.manman.teacher.data.MarkingStudent
import app.manman.teacher.domain.Student
import java.time.Instant
import java.util.UUID

@Composable
fun MarkingScreen(state: MainUiState, viewModel: MainViewModel, back: () -> Unit, onAddStudents: () -> Unit) {
    val students = state.students.filter(Student::active)
    var title by rememberSaveable { mutableStateOf("") }
    var first by rememberSaveable { mutableStateOf("有") }
    var second by rememberSaveable { mutableStateOf("没有") }
    val choices = remember(students.map(Student::id)) {
        mutableStateMapOf<String, Boolean>().apply { students.forEach { put(it.id, true) } }
    }
    val firstCount = students.count { choices[it.id] != false }
    val density = LocalDensity.current
    val minStudentWidth = (with(density) { 14.sp.toDp() } * 4 + 24.dp).coerceAtLeast(80.dp)
    Column(Modifier.fillMaxSize()) {
        PageHeader("班级标记", "先设置事项，再点选学生", back = back)
        if (students.isEmpty()) {
            Column(Modifier.padding(horizontal = 16.dp)) { EmptyHint("先添加学生", "有名单后就可以给全班做标记。", "添加学生", onAddStudents) }
        } else {
            LazyVerticalGrid(columns = GridCells.Adaptive(minStudentWidth), modifier = Modifier.weight(1f),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                  Column {
                    QuietTextField(title, { title = it.take(80) }, "事项名称")
                    Spacer(Modifier.height(8.dp))
                    if (density.fontScale >= 1.3f) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            QuietTextField(first, { first = it.take(12) }, "状态一")
                            QuietTextField(second, { second = it.take(12) }, "状态二")
                        }
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            QuietTextField(first, { first = it.take(12) }, "状态一", Modifier.weight(1f))
                            QuietTextField(second, { second = it.take(12) }, "状态二", Modifier.weight(1f))
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    Text("$first $firstCount 人 · $second ${students.size - firstCount} 人", style = MaterialTheme.typography.titleMedium)
                    Text("点姓名切换状态。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    FlowRow {
                        TextButton(onClick = { students.forEach { choices[it.id] = true } }) { Text("全部设为$first") }
                        TextButton(onClick = { students.forEach { choices[it.id] = false } }) { Text("全部设为$second") }
                    }
                  }
                }
                items(students, key = Student::id) { student ->
                    val selected = choices[student.id] != false
                    val label = if (selected) first else second
                    Column(
                        Modifier.fillMaxWidth().heightIn(min = 88.dp)
                            .background(if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow, MaterialTheme.shapes.medium)
                            .semantics(mergeDescendants = true) { contentDescription = "${student.name}，$label，点击切换状态" }
                            .clickable { choices[student.id] = !selected }
                            .padding(8.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(student.name, style = MaterialTheme.typography.bodySmall)
                        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
                    }
                }
                item(span = { GridItemSpan(maxLineSpan) }) { Spacer(Modifier.height(8.dp)) }
            }
            MainButton("保存记录", {
                val snapshot = MarkingRecord(UUID.randomUUID().toString(), title.trim(), first.trim(), second.trim(), Instant.now(),
                    students.map { MarkingStudent(it.id, it.name, choices[it.id] != false) })
                viewModel.saveMarking(snapshot, back)
            }, enabled = title.isNotBlank() && first.isNotBlank() && second.isNotBlank() && first.trim() != second.trim() && !state.busy,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp))
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
fun MarkingDetailScreen(record: MarkingRecord, back: () -> Unit) {
    val first = record.students.filter(MarkingStudent::first)
    val second = record.students.filterNot(MarkingStudent::first)
    Column(Modifier.fillMaxSize()) {
        PageHeader(record.title, "保存时的班级快照", back = back)
        LazyColumn(Modifier.weight(1f), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item { MarkingGroup(record.firstLabel, first) }
            item { MarkingGroup(record.secondLabel, second) }
        }
    }
}

@Composable
private fun MarkingGroup(label: String, students: List<MarkingStudent>) {
    Block {
        Text("$label · ${students.size} 人", style = MaterialTheme.typography.titleMedium)
        Text(if (students.isEmpty()) "暂无" else students.joinToString("、") { it.name }, style = MaterialTheme.typography.bodyLarge)
    }
}
