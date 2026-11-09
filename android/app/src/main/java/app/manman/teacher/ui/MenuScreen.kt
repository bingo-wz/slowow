package app.manman.teacher.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.manman.teacher.MainUiState
import app.manman.teacher.MainViewModel
import app.manman.teacher.domain.AppTime
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters

@Composable
fun MenuScreen(state: MainUiState, viewModel: MainViewModel, back: () -> Unit) {
    val start = LocalDate.now(AppTime.zone).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    val menu = state.menuWeeks.firstOrNull { it.start == start }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) viewModel.saveMenuImage(start, uri)
    }
    fun selectImage() = picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    val dateFormat = DateTimeFormatter.ofPattern("M/d")
    Column(Modifier.fillMaxSize()) {
        PageHeader("本周菜单", "${start.format(dateFormat)} - ${start.plusDays(6).format(dateFormat)}", back = back,
            action = if (menu != null) {
                { ActionIcon("edit", "更换菜单图片", { selectImage() }, enabled = !state.busy) }
            } else null)
        if (menu != null) {
            if (state.busy) CircularProgressIndicator(Modifier.padding(horizontal = 16.dp))
            AttachmentImage(state, menu.attachmentId, Modifier.weight(1f))
        } else {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.busy) {
                    CircularProgressIndicator()
                    Text("正在保存图片")
                } else {
                    EmptyHint("本周还没有菜单", "添加菜单图片后，可以在这里查看。", "添加图片") { selectImage() }
                }
            }
        }
    }
}
