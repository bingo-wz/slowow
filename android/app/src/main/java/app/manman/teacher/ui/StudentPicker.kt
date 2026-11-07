package app.manman.teacher.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.manman.teacher.domain.Student
import app.manman.teacher.domain.StudentDirectory
import app.manman.teacher.domain.StudentSection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private sealed interface PickerRow {
    data class Heading(val initial: String) : PickerRow
    data class Person(val student: Student) : PickerRow
}

@Composable
fun StudentPicker(students: List<Student>, onDismiss: () -> Unit, onSelect: (Student) -> Unit) {
    val sections by produceState<List<StudentSection>?>(initialValue = null, students) {
        value = withContext(Dispatchers.Default) { StudentDirectory.sections(students) }
    }
    var query by rememberSaveable { mutableStateOf("") }
    val rows = remember(sections, query) {
        sections.orEmpty().flatMap { section ->
            val members = section.students.filter { it.name.contains(query.trim(), ignoreCase = true) }
            if (members.isEmpty()) emptyList() else listOf(PickerRow.Heading(section.initial)) + members.map(PickerRow::Person)
        }
    }
    val initials = remember(rows) { rows.mapIndexedNotNull { index, row -> (row as? PickerRow.Heading)?.initial?.let { it to index } } }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth().fillMaxHeight(0.9f).padding(horizontal = 16.dp, vertical = 16.dp),
            shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("选择学生", Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall)
                    TextButton(onClick = onDismiss) { Text("取消") }
                }
                QuietTextField(query, { query = it.take(50) }, "搜索姓名")
                if (sections == null) {
                    Text("正在整理名单…", Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall)
                } else if (rows.isEmpty()) {
                    Text("没有找到学生", Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall)
                } else {
                    Row(Modifier.weight(1f).fillMaxWidth()) {
                        LazyColumn(Modifier.weight(1f).fillMaxHeight(), state = listState) {
                            items(rows, key = { row -> when (row) {
                                is PickerRow.Heading -> "heading-${row.initial}"
                                is PickerRow.Person -> "student-${row.student.id}"
                            } }) { row ->
                                when (row) {
                                    is PickerRow.Heading -> Text(row.initial, Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
                                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                                    is PickerRow.Person -> Text(row.student.name,
                                        Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { onSelect(row.student) }.padding(8.dp),
                                        style = MaterialTheme.typography.bodyLarge)
                                }
                            }
                        }
                        Column(Modifier.width(48.dp).fillMaxHeight().verticalScroll(rememberScrollState()),
                            horizontalAlignment = Alignment.CenterHorizontally) {
                            initials.forEach { (initial, position) ->
                                Text(initial, Modifier.fillMaxWidth().heightIn(min = 48.dp)
                                    .clickable { scope.launch { listState.scrollToItem(position) } }
                                    .semantics { contentDescription = "跳到 $initial" }.padding(vertical = 8.dp),
                                    textAlign = TextAlign.Center, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
            }
        }
    }
}
