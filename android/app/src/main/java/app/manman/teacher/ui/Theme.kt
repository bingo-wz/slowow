package app.manman.teacher.ui

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val LightColors = lightColorScheme(
    primary = Color(0xFF397B40),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDCEEDB),
    onPrimaryContainer = Color(0xFF174E20),
    secondary = Color(0xFF397B40),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE4ECE3),
    onSecondaryContainer = Color(0xFF273B2F),
    tertiary = Color(0xFF397B40),
    error = Color(0xFF397B40),
    onError = Color.White,
    errorContainer = Color(0xFFE1EAE0),
    onErrorContainer = Color(0xFF20392B),
    surface = Color(0xFFF4F8F4),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFFFFFF),
    surfaceContainer = Color(0xFFEEF3EE),
    surfaceContainerHigh = Color(0xFFE1EAE0),
    surfaceContainerHighest = Color(0xFFD8E2D8),
    onSurface = Color(0xFF19221B),
    onSurfaceVariant = Color(0xFF454F46),
    outline = Color(0xFF768178),
    outlineVariant = Color(0xFFC6D0C5),
    background = Color(0xFFF4F8F4),
    onBackground = Color(0xFF19221B),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFA7D8A4),
    onPrimary = Color(0xFF143B1A),
    primaryContainer = Color(0xFF285732),
    onPrimaryContainer = Color(0xFFCFEDCC),
    secondary = Color(0xFFA7D8A4),
    onSecondary = Color(0xFF263B2D),
    secondaryContainer = Color(0xFF34473A),
    onSecondaryContainer = Color(0xFFD7E9D9),
    tertiary = Color(0xFFBBD0BE),
    error = Color(0xFFBBD0BE),
    onError = Color(0xFF263B2D),
    errorContainer = Color(0xFF34473A),
    onErrorContainer = Color(0xFFD7E9D9),
    surface = Color(0xFF111A13),
    surfaceContainerLowest = Color(0xFF0C140E),
    surfaceContainerLow = Color(0xFF19231B),
    surfaceContainer = Color(0xFF202B22),
    surfaceContainerHigh = Color(0xFF2B362D),
    surfaceContainerHighest = Color(0xFF354137),
    onSurface = Color(0xFFE0EAE0),
    onSurfaceVariant = Color(0xFFC1CEC1),
    outline = Color(0xFF8D9B8F),
    outlineVariant = Color(0xFF414D43),
    background = Color(0xFF111A13),
    onBackground = Color(0xFFE0EAE0),
)

private val Heading = TextStyle(fontSize = 24.sp, lineHeight = 32.sp, fontWeight = FontWeight.Medium)
private val Subheading = TextStyle(fontSize = 18.sp, lineHeight = 26.sp, fontWeight = FontWeight.Medium)
private val Body = TextStyle(fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.Normal)
private val Label = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Normal)

private fun manmanTypography(font: FontFamily?): Typography {
    val heading = Heading.copy(fontFamily = font)
    val subheading = Subheading.copy(fontFamily = font)
    val body = Body.copy(fontFamily = font)
    val label = Label.copy(fontFamily = font)
    return Typography(
        displayLarge = heading, displayMedium = heading, displaySmall = heading,
        headlineLarge = heading, headlineMedium = heading, headlineSmall = heading,
        titleLarge = heading, titleMedium = subheading, titleSmall = subheading,
        bodyLarge = body, bodyMedium = body, bodySmall = label,
        labelLarge = body.copy(fontWeight = FontWeight.Medium),
        labelMedium = label.copy(fontWeight = FontWeight.Medium),
        labelSmall = label.copy(fontWeight = FontWeight.Medium),
    )
}

private val ManmanShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp), small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(16.dp), large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(24.dp),
)

private object ManmanMotion : MotionScheme {
    override fun <T> defaultSpatialSpec(): FiniteAnimationSpec<T> = tween(240)
    override fun <T> fastSpatialSpec(): FiniteAnimationSpec<T> = tween(200)
    override fun <T> slowSpatialSpec(): FiniteAnimationSpec<T> = tween(300)
    override fun <T> defaultEffectsSpec(): FiniteAnimationSpec<T> = tween(240)
    override fun <T> fastEffectsSpec(): FiniteAnimationSpec<T> = tween(200)
    override fun <T> slowEffectsSpec(): FiniteAnimationSpec<T> = tween(300)
}

@Composable
fun ManmanTheme(font: FontFamily? = null, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        typography = remember(font) { manmanTypography(font) },
        shapes = ManmanShapes,
        motionScheme = ManmanMotion,
        content = content,
    )
}
