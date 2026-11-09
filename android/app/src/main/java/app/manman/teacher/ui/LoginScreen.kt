package app.manman.teacher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.manman.teacher.MainUiState
import app.manman.teacher.MainViewModel

@Composable
fun LoginScreen(state: MainUiState, viewModel: MainViewModel) {
    var phone by rememberSaveable { mutableStateOf("") }
    var code by rememberSaveable { mutableStateOf("") }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            Modifier.size(56.dp).background(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.shapes.medium),
            contentAlignment = Alignment.Center,
        ) { LineIcon("check") }
        Spacer(Modifier.height(24.dp))
        Text("慢慢", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text("把班级里的事，安心交给慢慢。", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(32.dp))
        Text("手机号登录", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text("首次登录将自动创建账号", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(16.dp))
        QuietTextField(
            value = phone,
            onValueChange = { phone = it.filter { char -> char.isDigit() }.take(11) },
            label = "手机号",
            enabled = state.verification == null && !state.busy,
            keyboardType = KeyboardType.Phone,
        )
        Spacer(Modifier.height(16.dp))
        if (state.verification == null) {
            MainButton("获取验证码", { viewModel.requestCode(phone) }, enabled = !state.busy, modifier = Modifier.fillMaxWidth())
        } else {
            QuietTextField(
                value = code,
                onValueChange = { code = it.filter(Char::isDigit).take(6) },
                label = "短信验证码",
                enabled = !state.busy,
                keyboardType = KeyboardType.Number,
            )
            Spacer(Modifier.height(16.dp))
            MainButton("登录", { viewModel.verifyCode(code) }, enabled = !state.busy, modifier = Modifier.fillMaxWidth())
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("已发送至 ${maskPhone(phone)}", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FeedbackTextButton(onClick = { code = ""; viewModel.resetVerification() }, enabled = !state.busy) { Text("更换号码 / 重发") }
            }
        }
        Spacer(Modifier.height(24.dp))
        Text("登录后，班级记录会保存到你的账号。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(32.dp))
    }
}

@Composable
fun QuietTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    singleLine: Boolean = true,
    keyboardType: KeyboardType = KeyboardType.Text,
) {
    TextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth().feedbackOnFocus(),
        label = { Text(label) },
        enabled = enabled,
        singleLine = singleLine,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        shape = MaterialTheme.shapes.medium,
        colors = TextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
            unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
            disabledIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
        ),
    )
}

private fun maskPhone(phone: String): String = if (phone.length == 11) phone.take(3) + "****" + phone.takeLast(4) else phone
