package app.manman.teacher.fonts

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class AppTextSize(val title: String, val scale: Float?) {
    SYSTEM("跟随系统", null),
    SMALL("偏小", 0.9f),
    STANDARD("标准", 1f),
    LARGE("偏大", 1.15f),
    EXTRA_LARGE("大", 1.3f),
    LARGEST("特大", 1.5f),
}

class AppTextSizes(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("manman_text_size", Context.MODE_PRIVATE)
    private val selected = MutableStateFlow(AppTextSize.entries.firstOrNull {
        it.name == preferences.getString("selected", AppTextSize.SYSTEM.name)
    } ?: AppTextSize.SYSTEM)
    val state: StateFlow<AppTextSize> = selected.asStateFlow()

    fun select(size: AppTextSize) {
        preferences.edit().putString("selected", size.name).apply()
        selected.value = size
    }
}
