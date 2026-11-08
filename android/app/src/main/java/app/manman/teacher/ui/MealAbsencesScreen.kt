package app.manman.teacher.ui

import android.app.DatePickerDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.manman.teacher.MainUiState
import app.manman.teacher.domain.MealAbsenceRules
import java.time.LocalDate
import java.time.format.DateTimeFormatter

@Composable
fun MealAbsencesScreen(state: MainUiState, back: () -> Unit) {
    val today = rememberToday()
    var selectedDate by rememberSaveable { mutableStateOf(today.toString()) }
    val day = LocalDate.parse(selectedDate)
    val people = remember(day, state.students, state.leaves) { MealAbsenceRules.forDate(day, state.students, state.leaves) }
    val context = LocalContext.current
    fun copyNames() {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("不在校用餐名单", people.joinToString("、") { it.name }))
        Toast.makeText(context, "已复制名单", Toast.LENGTH_SHORT).show()
    }
    Column(Modifier.fillMaxSize()) {
        PageHeader("不在校用餐名单", back = back,
            action = { ActionIcon("copy", "复制不在校用餐名单", ::copyNames, enabled = people.isNotEmpty()) })
        LazyColumn(Modifier.weight(1f), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    ActionIcon("back", "前一天", { selectedDate = day.minusDays(1).toString() })
                    TextButton(onClick = {
                        DatePickerDialog(context, { _, year, month, date ->
                            selectedDate = LocalDate.of(year, month + 1, date).toString()
                        }, day.year, day.monthValue - 1, day.dayOfMonth).show()
                    }, modifier = Modifier.weight(1f)) {
                        Text(day.format(DateTimeFormatter.ofPattern("yyyy年M月d日")) + if (day == today) " · 今天" else "  ›")
                    }
                    ActionIcon("forward", "后一天", { selectedDate = day.plusDays(1).toString() })
                }
                Text("共 ${people.size} 人", style = MaterialTheme.typography.titleMedium)
                Text("按当前班级设置和现有请假实时计算。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (people.isEmpty()) item { Text("这一天没有不在校用餐的学生。", Modifier.padding(vertical = 16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(people, key = { it.studentId }) { person ->
                Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                    Text(person.name, style = MaterialTheme.typography.bodyLarge)
                    Text(when {
                        person.regular && person.onLeave -> "固定不在校用餐 · 请假"
                        person.regular -> "固定不在校用餐"
                        else -> "全天或上午请假"
                    }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            item { Spacer(Modifier.height(16.dp)) }
        }
    }
}
