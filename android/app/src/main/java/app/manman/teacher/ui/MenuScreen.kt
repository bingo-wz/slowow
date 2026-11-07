package app.manman.teacher.ui

import android.app.DatePickerDialog
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.manman.teacher.MainUiState
import app.manman.teacher.MainViewModel
import app.manman.teacher.domain.LunchRules
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import java.time.DayOfWeek
import java.time.temporal.TemporalAdjusters

@Composable
fun MenuScreen(state: MainUiState, viewModel: MainViewModel, back: () -> Unit, onImage: (String) -> Unit,
    onRecognize: (LocalDate, String) -> Unit) {
    val context = LocalContext.current
    var dayText by rememberSaveable { mutableStateOf(LocalDate.now(LunchRules.defaultZone).toString()) }
    var draft by rememberSaveable { mutableStateOf(state.menus.firstOrNull { it.day.toString() == dayText }?.dishes.orEmpty()) }
    var original by rememberSaveable { mutableStateOf(draft) }
    var pendingDay by rememberSaveable { mutableStateOf("") }
    var confirmBack by rememberSaveable { mutableStateOf(false) }
    var confirmRecognition by rememberSaveable { mutableStateOf(false) }
    val draftId = rememberSaveable { UUID.randomUUID().toString() }
    val staged = state.imageDrafts[draftId].orEmpty()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) viewModel.stageImages(draftId, listOf(uri))
    }
    val day = LocalDate.parse(dayText)
    val weekStart = day.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    val image = state.menuWeeks.firstOrNull { it.start == weekStart }
    fun changeDay(next: LocalDate) {
        if (draft != original) pendingDay = next.toString()
        else {
            dayText = next.toString()
            original = state.menus.firstOrNull { it.day == next }?.dishes.orEmpty()
            draft = original
        }
    }
    fun leave() {
        if (draft != original || staged.isNotEmpty()) confirmBack = true else back()
    }
    androidx.activity.compose.BackHandler { leave() }
    Column(Modifier.fillMaxSize()) {
        PageHeader("食堂菜单", "按日期填写，每行一道菜", back = ::leave)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Block {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = { changeDay(day.minusDays(1)) }) { Text("前一天") }
                    TextButton(onClick = {
                        DatePickerDialog(context, { _, year, month, date -> changeDay(LocalDate.of(year, month + 1, date)) },
                            day.year, day.monthValue - 1, day.dayOfMonth).show()
                    }) { Text(day.format(DateTimeFormatter.ofPattern("M月d日 EEEE", Locale.SIMPLIFIED_CHINESE))) }
                    TextButton(onClick = { changeDay(day.plusDays(1)) }) { Text("后一天") }
                }
            }
            QuietTextField(draft, { draft = it.take(3000) }, "今天的菜", singleLine = false, modifier = Modifier.height(200.dp))
            Text("保存后会显示在对应日期，也可以从本周原图识别后逐日校对。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            SectionHeading("周菜单原图")
            Block {
                Text("${weekStart.monthValue} 月 ${weekStart.dayOfMonth} 日起的一周", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (image != null) TextButton(onClick = { onImage(image.attachmentId) }) { Text("查看原图") }
                if (image != null) {
                    TextButton(onClick = { confirmRecognition = true }, enabled = !state.busy && draft == original && staged.isEmpty()) { Text("识别这周菜单") }
                    if (draft != original || staged.isNotEmpty()) Text("请先保存当天菜单和新图片，再开始识别。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (staged.isNotEmpty()) {
                    Text("已选择新图片，确认保存后应用到这一周。")
                    Row {
                        TextButton(onClick = { viewModel.saveMenuImage(weekStart, draftId) {} }, enabled = !state.busy) { Text("保存原图") }
                        TextButton(onClick = { viewModel.discardDraft(draftId) }) { Text("取消选择") }
                    }
                } else TextButton(onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, enabled = !state.busy) {
                    Text(if (image == null) "选择周菜单图片" else "更换周菜单图片")
                }
            }
        }
        MainButton("保存当天菜单", { viewModel.saveMenu(day, draft) { original = draft; back() } },
            enabled = !state.busy && staged.isEmpty(), modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp))
        Spacer(Modifier.height(16.dp))
    }
    if (confirmRecognition && image != null) AlertDialog(
        onDismissRequest = { confirmRecognition = false },
        title = { Text("发送菜单图片进行识别？") },
        text = { Text("将本周菜单原图发送给小米 MiMo 识别，可能产生 API 费用。结果需要你逐日检查并确认，图片需先同步到云端。") },
        confirmButton = { TextButton(onClick = {
            confirmRecognition = false
            onRecognize(weekStart, image.attachmentId)
        }) { Text("确认发送") } },
        dismissButton = { TextButton(onClick = { confirmRecognition = false }) { Text("取消") } },
    )
    if (pendingDay.isNotEmpty() || confirmBack) {
        AlertDialog(
            onDismissRequest = { pendingDay = ""; confirmBack = false },
            title = { Text("菜单还没有保存") },
            text = { Text("离开后，未保存的菜单和新选择的图片不会保留。") },
            confirmButton = { TextButton(onClick = {
                if (confirmBack) { confirmBack = false; viewModel.discardDraft(draftId); back() }
                else {
                    dayText = pendingDay
                    original = state.menus.firstOrNull { it.day.toString() == pendingDay }?.dishes.orEmpty()
                    draft = original
                    pendingDay = ""
                }
            }) { Text("放弃修改") } },
            dismissButton = { TextButton(onClick = { pendingDay = ""; confirmBack = false }) { Text("继续编辑") } },
        )
    }
}
