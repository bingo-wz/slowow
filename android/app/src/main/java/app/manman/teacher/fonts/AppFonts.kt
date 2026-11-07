package app.manman.teacher.fonts

import android.content.Context
import android.graphics.Typeface
import androidx.compose.ui.text.font.FontFamily
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

enum class AppFont(val title: String, val detail: String, val fileName: String?, val size: Long, val sha256: String) {
    SYSTEM("系统默认", "跟随手机字体", null, 0, ""),
    KAI("楷体", "马善政楷书 · 首次下载约 5.6 MB", "kai.ttf", 5_857_936, "6d2546bb189c732a8ca29af9e22457b152387d158aa459e4ac2ce1e51788b7fb"),
    CUTE("可爱", "站酷快乐体 · 首次下载约 1.5 MB", "cute.ttf", 1_514_968, "812a6fc1fe54b6d73a419245c32dfeba8aa33104d5be90d1cf6af082007cb71d"),
    HANDWRITTEN("手写", "龙藏体 · 首次下载约 5 MB", "hand.ttf", 5_162_508, "e5bf2c3f24ef2327c6f136d8f73e2f9dfdf44896fdbeb35a9515f44777bb91bc"),
}

data class AppFontState(
    val selected: AppFont = AppFont.SYSTEM,
    val family: FontFamily? = null,
    val downloading: AppFont? = null,
    val error: String? = null,
)

class AppFonts(context: Context, private val baseUrl: String) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences("manman_font", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow(AppFontState())
    private var restored = false
    val state: StateFlow<AppFontState> = mutableState
    private val directory: File get() = File(appContext.filesDir, "fonts").apply { mkdirs() }

    init {
        scope.launch {
            mutex.withLock {
                restoreLocked()
            }
        }
    }

    fun select(font: AppFont) {
        scope.launch {
            mutex.withLock {
                restoreLocked()
                if (font == mutableState.value.selected && mutableState.value.error == null) return@withLock
                mutableState.value = mutableState.value.copy(downloading = if (font == AppFont.SYSTEM) null else font, error = null)
                try {
                    val family = if (font == AppFont.SYSTEM) null else {
                        val file = File(directory, requireNotNull(font.fileName))
                        if (!isValid(file, font)) download(font, file)
                        load(file)
                    }
                    if (!preferences.edit().putString("selected", font.name).commit()) throw IOException("无法保存字体选择")
                    mutableState.value = AppFontState(font, family)
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    mutableState.value = mutableState.value.copy(downloading = null, error = "字体暂时无法使用，请检查网络或稍后重试。")
                }
            }
        }
    }

    fun clearError() { mutableState.value = mutableState.value.copy(error = null) }

    private fun restoreLocked() {
        if (restored) return
        restored = true
        val saved = AppFont.entries.firstOrNull { it.name == preferences.getString("selected", "SYSTEM") } ?: AppFont.SYSTEM
        if (saved == AppFont.SYSTEM) return
        try {
            val file = File(directory, requireNotNull(saved.fileName))
            if (isValid(file, saved)) {
                mutableState.value = AppFontState(saved, load(file))
                return
            }
        } catch (_: Exception) {
            // 字体缓存损坏时保留系统字体，允许用户重新下载。
        }
        preferences.edit().putString("selected", AppFont.SYSTEM.name).apply()
        mutableState.value = AppFontState()
    }

    private fun load(file: File): FontFamily = FontFamily(Typeface.createFromFile(file))

    private fun isValid(file: File, font: AppFont): Boolean {
        if (!file.isFile || file.length() != font.size) return false
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) } == font.sha256
    }

    private fun download(font: AppFont, target: File) {
        require(baseUrl.startsWith("https://")) { "字体连接必须使用 HTTPS。" }
        val temporary = File.createTempFile("font-", ".tmp", directory)
        val connection = URL("${baseUrl.trimEnd('/')}/fonts/${font.fileName}").openConnection() as HttpURLConnection
        try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 15_000
            connection.readTimeout = 45_000
            if (connection.responseCode != 200) throw IOException("字体下载失败")
            var total = 0L
            connection.inputStream.buffered().use { input ->
                temporary.outputStream().buffered().use { output ->
                    val buffer = ByteArray(16 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        if (total > font.size) throw IOException("字体文件大小无效")
                        output.write(buffer, 0, count)
                    }
                }
            }
            if (!isValid(temporary, font)) throw IOException("字体文件校验失败")
            load(temporary)
            if (target.exists() && !target.delete()) throw IOException("无法替换字体")
            if (!temporary.renameTo(target)) throw IOException("无法保存字体")
        } finally {
            connection.disconnect()
            temporary.delete()
        }
    }
}
