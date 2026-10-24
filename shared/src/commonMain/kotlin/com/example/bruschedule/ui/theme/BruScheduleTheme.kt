package com.example.bruschedule.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import com.example.bruschedule.domain.model.AppearancePreferences
import com.example.bruschedule.domain.model.Lesson
import com.example.bruschedule.domain.model.LessonType
import com.example.bruschedule.domain.model.LessonTypePalette
import com.example.bruschedule.domain.model.ScheduleAppTheme

private data class BruColorPalette(
    val background: Color,
    val surface: Color,
    val elevatedSurface: Color,
    val outline: Color,
    val foreground: Color,
    val muted: Color,
    val dim: Color,
    val indigo: Color,
    val onAccent: Color,
    val cyan: Color,
    val mint: Color,
    val coral: Color,
    val amber: Color,
    val danger: Color,
    val lecture: Color,
    val laboratory: Color,
    val practice: Color,
    val unknown: Color,
)

private val LocalBruPalette = staticCompositionLocalOf { paletteFor(AppearancePreferences()) }

/** Palette tokens are provided by the selected persisted theme and remain Compose-observable. */
object BruColors {
    val background: Color
        @Composable get() = LocalBruPalette.current.background
    val surface: Color
        @Composable get() = LocalBruPalette.current.surface
    val elevatedSurface: Color
        @Composable get() = LocalBruPalette.current.elevatedSurface
    val outline: Color
        @Composable get() = LocalBruPalette.current.outline
    val foreground: Color
        @Composable get() = LocalBruPalette.current.foreground
    val muted: Color
        @Composable get() = LocalBruPalette.current.muted
    val dim: Color
        @Composable get() = LocalBruPalette.current.dim
    val indigo: Color
        @Composable get() = LocalBruPalette.current.indigo
    val cyan: Color
        @Composable get() = LocalBruPalette.current.cyan
    val mint: Color
        @Composable get() = LocalBruPalette.current.mint
    val coral: Color
        @Composable get() = LocalBruPalette.current.coral
    val amber: Color
        @Composable get() = LocalBruPalette.current.amber
    val danger: Color
        @Composable get() = LocalBruPalette.current.danger

    @Composable
    fun lessonAccent(type: LessonType): Color = when (type) {
        LessonType.LECTURE -> LocalBruPalette.current.lecture
        LessonType.LABORATORY -> LocalBruPalette.current.laboratory
        LessonType.PRACTICE -> LocalBruPalette.current.practice
        LessonType.UNKNOWN -> LocalBruPalette.current.unknown
    }
}

@Composable
fun BruScheduleTheme(
    appearance: AppearancePreferences = AppearancePreferences(),
    content: @Composable () -> Unit,
) {
    val palette = remember(appearance) { paletteFor(appearance) }
    val colorScheme = when (appearance.theme) {
        ScheduleAppTheme.WHITE -> lightColorScheme(
            primary = palette.indigo,
            onPrimary = palette.onAccent,
            secondary = palette.cyan,
            onSecondary = palette.onAccent,
            tertiary = palette.mint,
            background = palette.background,
            onBackground = palette.foreground,
            surface = palette.surface,
            onSurface = palette.foreground,
            surfaceVariant = palette.elevatedSurface,
            onSurfaceVariant = palette.muted,
            outline = palette.outline,
            error = palette.danger,
            onError = palette.onAccent,
        )
        ScheduleAppTheme.BLUE, ScheduleAppTheme.BLACK -> darkColorScheme(
            primary = palette.indigo,
            onPrimary = palette.onAccent,
            secondary = palette.cyan,
            onSecondary = palette.onAccent,
            tertiary = palette.mint,
            background = palette.background,
            onBackground = palette.foreground,
            surface = palette.surface,
            onSurface = palette.foreground,
            surfaceVariant = palette.elevatedSurface,
            onSurfaceVariant = palette.muted,
            outline = palette.outline,
            error = palette.danger,
            onError = palette.onAccent,
        )
    }
    CompositionLocalProvider(LocalBruPalette provides palette) {
        MaterialTheme(colorScheme = colorScheme, content = content)
    }
}

/** Lesson accents respond immediately to the appearance preference, regardless of cached DB hex. */
object LessonThemeMapper {
    @Composable
    fun accent(lesson: Lesson): Color = BruColors.lessonAccent(lesson.type)

    @Composable
    fun typeAccent(type: LessonType): Color = BruColors.lessonAccent(type)

    fun parseHex(value: String): Color? = parseColor(value)

    fun normalizedHex(type: LessonType): String = LessonTypePalette.accent(type)
}

private fun paletteFor(appearance: AppearancePreferences): BruColorPalette {
    val base = when (appearance.theme) {
        ScheduleAppTheme.BLUE -> BruColorPalette(
            background = Color(0xFF0A0D12),
            surface = Color(0xFF111720),
            elevatedSurface = Color(0xFF17212D),
            outline = Color(0xFF263241),
            foreground = Color(0xFFF2F5FA),
            muted = Color(0xFF8D9BAC),
            dim = Color(0xFF5D6B7D),
            indigo = Color(0xFFB3A5FF),
            onAccent = Color(0xFF0A0D12),
            cyan = Color(0xFF8FD9F4),
            mint = Color(0xFF8FDFC0),
            coral = Color(0xFFFF8998),
            amber = Color(0xFFFFC979),
            danger = Color(0xFFFF7887),
            lecture = Color(0xFFA7B9FF),
            laboratory = Color(0xFFFF8998),
            practice = Color(0xFFFFC979),
            unknown = Color(0xFFA6B0C2),
        )
        ScheduleAppTheme.BLACK -> BruColorPalette(
            background = Color(0xFF000000),
            surface = Color(0xFF090A0D),
            elevatedSurface = Color(0xFF14171D),
            outline = Color(0xFF262B35),
            foreground = Color(0xFFF5F7FA),
            muted = Color(0xFFA3AAB7),
            dim = Color(0xFF626B79),
            indigo = Color(0xFF83A9FF),
            onAccent = Color(0xFF05070B),
            cyan = Color(0xFF7ED8F5),
            mint = Color(0xFF7BD7AC),
            coral = Color(0xFFFF8296),
            amber = Color(0xFFF8C36D),
            danger = Color(0xFFFF6F7E),
            lecture = Color(0xFF9DB8FF),
            laboratory = Color(0xFFFF8296),
            practice = Color(0xFFF8C36D),
            unknown = Color(0xFFAAB2C0),
        )
        ScheduleAppTheme.WHITE -> BruColorPalette(
            background = Color(0xFFF4F6FA),
            surface = Color(0xFFFFFFFF),
            elevatedSurface = Color(0xFFEEF2F8),
            outline = Color(0xFFD8DFEA),
            foreground = Color(0xFF152033),
            muted = Color(0xFF5D6879),
            dim = Color(0xFF828EA0),
            indigo = Color(0xFF3C57D8),
            onAccent = Color(0xFFFFFFFF),
            cyan = Color(0xFF137C9F),
            mint = Color(0xFF14805D),
            coral = Color(0xFFD84D67),
            amber = Color(0xFF9B5C00),
            danger = Color(0xFFC83A4D),
            lecture = Color(0xFF526EDC),
            laboratory = Color(0xFFD84D67),
            practice = Color(0xFF9B5C00),
            unknown = Color(0xFF697586),
        )
    }
    val accents = appearance.lessonAccents
    return base.copy(
        lecture = parseColor(accents.lecture) ?: base.lecture,
        laboratory = parseColor(accents.laboratory) ?: base.laboratory,
        practice = parseColor(accents.practice) ?: base.practice,
        unknown = parseColor(accents.unknown) ?: base.unknown,
    )
}

private fun parseColor(value: String): Color? {
    val hex = value.trim().removePrefix("#")
    if (hex.length != 6) return null
    val raw = hex.toLongOrNull(16) ?: return null
    val red = ((raw shr 16) and 0xFF).toFloat() / 255f
    val green = ((raw shr 8) and 0xFF).toFloat() / 255f
    val blue = (raw and 0xFF).toFloat() / 255f
    return Color(red = red, green = green, blue = blue, alpha = 1f)
}
