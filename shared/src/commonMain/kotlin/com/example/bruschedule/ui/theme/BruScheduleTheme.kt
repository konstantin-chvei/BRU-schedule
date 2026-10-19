package com.example.bruschedule.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.example.bruschedule.domain.model.Lesson
import com.example.bruschedule.domain.model.LessonType
import com.example.bruschedule.domain.model.LessonTypePalette

object BruColors {
    val background = Color(0xFF0A0D12)
    val surface = Color(0xFF111720)
    val elevatedSurface = Color(0xFF17212D)
    val outline = Color(0xFF263241)
    val foreground = Color(0xFFF2F5FA)
    val muted = Color(0xFF8D9BAC)
    val dim = Color(0xFF5D6B7D)
    val indigo = Color(0xFFB3A5FF)
    val cyan = Color(0xFF8FD9F4)
    val mint = Color(0xFF8FDFC0)
    val coral = Color(0xFFFF8998)
    val amber = Color(0xFFFFC979)
    val danger = Color(0xFFFF7887)
}

private val CyberDarkScheme = darkColorScheme(
    primary = BruColors.indigo,
    onPrimary = BruColors.background,
    secondary = BruColors.cyan,
    onSecondary = BruColors.background,
    tertiary = BruColors.mint,
    background = BruColors.background,
    onBackground = BruColors.foreground,
    surface = BruColors.surface,
    onSurface = BruColors.foreground,
    surfaceVariant = BruColors.elevatedSurface,
    onSurfaceVariant = BruColors.muted,
    outline = BruColors.outline,
    error = BruColors.danger,
    onError = BruColors.background,
)

@Composable
fun BruScheduleTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = CyberDarkScheme,
        content = content,
    )
}

/** Maps scraper output to the app's dark-theme accents, not to the raw (often saturated) CSS. */
object LessonThemeMapper {
    fun accent(lesson: Lesson): Color = parseHex(lesson.colorHex) ?: typeAccent(lesson.type)

    fun typeAccent(type: LessonType): Color = when (type) {
        LessonType.LECTURE -> BruColors.indigo
        LessonType.LABORATORY -> BruColors.coral
        LessonType.PRACTICE -> BruColors.amber
        LessonType.UNKNOWN -> BruColors.muted
    }

    fun parseHex(value: String): Color? {
        val hex = value.trim().removePrefix("#")
        if (hex.length != 6) return null
        val raw = hex.toLongOrNull(16) ?: return null
        val red = ((raw shr 16) and 0xFF).toFloat() / 255f
        val green = ((raw shr 8) and 0xFF).toFloat() / 255f
        val blue = (raw and 0xFF).toFloat() / 255f
        return Color(red = red, green = green, blue = blue, alpha = 1f)
    }

    fun normalizedHex(type: LessonType): String = LessonTypePalette.accent(type)
}
