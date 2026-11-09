package app.manman.teacher.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.manman.teacher.MainUiState
import app.manman.teacher.MainViewModel
import app.manman.teacher.data.MarkingRecord
import app.manman.teacher.data.MarkingStudent
import app.manman.teacher.domain.Student
import app.manman.teacher.domain.AppTime
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.UUID

@Composable
fun MarkingScreen(state: MainUiState, viewModel: MainViewModel, existing: MarkingRecord?, back: () -> Unit, onAddStudents: () -> Unit) {
    val students = remember(existing?.id, state.students) {
        existing?.students ?: state.students.filter(Student::active).map { MarkingStudent(it.id, it.name, true) }
    }
    var title by rememberSaveable(existing?.id) { mutableStateOf(existing?.title.orEmpty()) }
    var first by rememberSaveable(existing?.id) { mutableStateOf(existing?.firstLabel ?: "有") }
    var second by rememberSaveable(existing?.id) { mutableStateOf(existing?.secondLabel ?: "没有") }
    var secondIds by rememberSaveable(existing?.id) { mutableStateOf(ArrayList(students.filterNot(MarkingStudent::first).map(MarkingStudent::studentId))) }
    val selectedSecond = secondIds.toSet()
    val firstCount = students.count { it.studentId !in selectedSecond }
    val density = LocalDensity.current
    val minStudentWidth = (with(density) { 14.sp.toDp() } * 4 + 24.dp).coerceAtLeast(80.dp)
    Column(Modifier.fillMaxSize()) {
        PageHeader(if (existing == null) "班级标记" else "编辑班级标记", "先设置事项，再点选学生", back = back)
        if (students.isEmpty()) {
            Column(Modifier.padding(horizontal = 16.dp)) { EmptyHint("先添加学生", "有名单后就可以给全班做标记。", "添加学生", onAction = onAddStudents) }
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
                        FeedbackTextButton(onClick = { secondIds = arrayListOf() }) { Text("全部设为$first") }
                        FeedbackTextButton(onClick = { secondIds = ArrayList(students.map(MarkingStudent::studentId)) }) { Text("全部设为$second") }
                    }
                  }
                }
                items(students, key = MarkingStudent::studentId) { student ->
                    val selected = student.studentId !in selectedSecond
                    val label = if (selected) first else second
                    Column(
                        Modifier.fillMaxWidth().heightIn(min = 88.dp)
                            .background(if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow, MaterialTheme.shapes.medium)
                            .semantics(mergeDescendants = true) { contentDescription = "${student.name}，$label，点击切换状态" }
                            .feedbackClickable {
                                secondIds = ArrayList(if (selected) selectedSecond + student.studentId else selectedSecond - student.studentId)
                            }
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
                val snapshot = MarkingRecord(existing?.id ?: UUID.randomUUID().toString(), title.trim(), first.trim(), second.trim(),
                    existing?.createdAt ?: Instant.now(), students.map { it.copy(first = it.studentId !in selectedSecond) })
                viewModel.saveMarking(snapshot, back)
            }, enabled = title.isNotBlank() && first.isNotBlank() && second.isNotBlank() && first.trim() != second.trim() && !state.busy,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp))
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
fun MarkingDetailScreen(record: MarkingRecord, state: MainUiState, viewModel: MainViewModel, back: () -> Unit, onEdit: () -> Unit) {
    val first = record.students.filter(MarkingStudent::first)
    val second = record.students.filterNot(MarkingStudent::first)
    val context = LocalContext.current
    var confirmDelete by remember { mutableStateOf(false) }
    val date = record.createdAt.atZone(AppTime.zone)
    fun copyNames(students: List<MarkingStudent>) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("学生名单", students.joinToString("、") { it.name }))
        Toast.makeText(context, "已复制名单", Toast.LENGTH_SHORT).show()
    }
    Column(Modifier.fillMaxSize()) {
        PageHeader("标记详情", back = back,
            action = {
                Row {
                    ActionIcon("edit", "编辑班级标记", onEdit)
                    ActionIcon("delete", "删除班级标记", { confirmDelete = true }, enabled = !state.busy)
                }
            })
        LazyColumn(Modifier.weight(1f), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(record.title, style = MaterialTheme.typography.titleMedium)
                    Text(date.format(DateTimeFormatter.ofPattern("yyyy年M月d日 HH:mm")),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            item { MarkingGroup(record.firstLabel, first) { copyNames(first) } }
            item { MarkingGroup(record.secondLabel, second) { copyNames(second) } }
        }
    }
    if (confirmDelete) AlertDialog(
        onDismissRequest = feedbackClick { confirmDelete = false }, title = { Text("删除这条班级标记？") },
        text = { Text("删除后，这条记录也会从已同步的设备中移除。") },
        confirmButton = { FeedbackTextButton(onClick = { confirmDelete = false; viewModel.deleteMarking(record, back) }) { Text("删除") } },
        dismissButton = { FeedbackTextButton(onClick = { confirmDelete = false }) { Text("取消") } },
    )
}

@Composable
private fun MarkingGroup(label: String, students: List<MarkingStudent>, onCopy: () -> Unit) {
    Block {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("$label · ${students.size} 人", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            ActionIcon("copy", "复制${label}名单", onCopy, enabled = students.isNotEmpty())
        }
        Text(if (students.isEmpty()) "暂无" else students.joinToString("、") { it.name }, style = MaterialTheme.typography.bodyLarge)
    }
}
