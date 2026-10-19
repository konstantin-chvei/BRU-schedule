package com.example.bruschedule.presentation.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.bruschedule.domain.model.LessonType
import com.example.bruschedule.domain.model.ScheduleAppTheme
import com.example.bruschedule.presentation.ScheduleUiState
import com.example.bruschedule.presentation.ScheduleViewModel
import com.example.bruschedule.ui.theme.BruColors
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

@Composable
fun ScheduleSettingsScreen(
    state: ScheduleUiState,
    viewModel: ScheduleViewModel,
    contentPadding: PaddingValues,
) {
    val sync = state.syncState
    var showDeleteConfirmation by remember(state.selectedProfile?.id) { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BruColors.background)
            .padding(contentPadding)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 21.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("НАСТРОЙКИ · 03", color = BruColors.cyan, fontSize = 9.sp, letterSpacing = 1.4.sp)
        Text("Журнал и настройки", color = BruColors.foreground, fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
        Text("Проверяйте обновления, локальный кэш и историю версий расписания.", color = BruColors.muted, fontSize = 12.sp, lineHeight = 18.sp)

        AppearanceSettingsCard(
            state = state,
            onThemeSelected = viewModel::setTheme,
            onLessonColorSelected = viewModel::setLessonColor,
            onResetLessonColors = viewModel::resetLessonColors,
        )

        Surface(shape = RoundedCornerShape(21.dp), color = BruColors.surface, border = BorderStroke(1.dp, BruColors.outline)) {
            Column(Modifier.fillMaxWidth().padding(17.dp), verticalArrangement = Arrangement.spacedBy(13.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("АКТИВНЫЙ ИСТОЧНИК", color = BruColors.dim, fontSize = 8.sp, letterSpacing = 1.sp)
                        Text(state.selectedProfile?.name ?: "Профиль не выбран", color = BruColors.foreground, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    }
                    Text(if (state.isRefreshing) "●" else if (sync?.lastSuccessfulAtEpochMillis != null) "●" else "○", color = if (state.isRefreshing) BruColors.amber else BruColors.mint, fontSize = 17.sp)
                }
                HorizontalDividerLine()
                JournalLine("Последнее успешное обновление", sync?.lastSuccessfulAtEpochMillis?.let(::formatSyncTimestamp) ?: "Пока нет")
                JournalLine("Последняя попытка", sync?.lastAttemptAtEpochMillis?.let(::formatSyncTimestamp) ?: "Ещё не запускали")
                JournalLine("Снимок V−1", sync?.previousSnapshotAtEpochMillis?.let(::formatSyncTimestamp) ?: "Нет сохранённого снимка")
                sync?.lastError?.let { error ->
                    Surface(shape = RoundedCornerShape(12.dp), color = BruColors.danger.copy(alpha = 0.10f)) {
                        Text("Последняя ошибка: $error", modifier = Modifier.padding(11.dp), color = BruColors.danger, fontSize = 10.sp, lineHeight = 15.sp)
                    }
                }
                Button(
                    onClick = viewModel::refreshSelectedProfile,
                    enabled = state.selectedProfile != null && !state.isRefreshing,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(13.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = BruColors.indigo, contentColor = BruColors.background),
                ) {
                    Text(if (state.isRefreshing) "Обновляем расписание…" else "Обновить с rasp.bru.by", fontWeight = FontWeight.SemiBold)
                }
                OutlinedButton(
                    onClick = viewModel::rollbackSelectedProfile,
                    enabled = sync?.canRollback == true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(13.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = BruColors.cyan),
                    border = BorderStroke(1.dp, if (sync?.canRollback == true) BruColors.cyan.copy(alpha = 0.5f) else BruColors.outline),
                ) {
                    Text("↶  Откатить к предыдущей версии", fontWeight = FontWeight.Medium)
                }
                OutlinedButton(
                    onClick = { showDeleteConfirmation = true },
                    enabled = state.selectedProfile != null && !state.isRefreshing,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(13.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = BruColors.danger),
                    border = BorderStroke(1.dp, BruColors.danger.copy(alpha = 0.45f)),
                ) {
                    Text("Удалить это расписание", fontWeight = FontWeight.Medium)
                }
            }
        }

        Surface(shape = RoundedCornerShape(21.dp), color = BruColors.surface, border = BorderStroke(1.dp, BruColors.outline)) {
            Column(Modifier.fillMaxWidth().padding(17.dp), verticalArrangement = Arrangement.spacedBy(11.dp)) {
                Text("OFFLINE-FIRST", color = BruColors.mint, fontSize = 9.sp, letterSpacing = 1.1.sp, fontWeight = FontWeight.SemiBold)
                Text("Сначала локальное. Сеть — потом.", color = BruColors.foreground, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    "Для обычной двухнедельной сетки локально хранится 28-недельный цикл; ручные правки и задания читаются из SQLDelight сразу при запуске. Если сервер недоступен или HTML изменился, сохранённое расписание остаётся нетронутым; неудачная попытка попадёт в журнал.",
                    color = BruColors.muted,
                    fontSize = 11.sp,
                    lineHeight = 17.sp,
                )
            }
        }

        Surface(shape = RoundedCornerShape(21.dp), color = BruColors.surface, border = BorderStroke(1.dp, BruColors.outline)) {
            Column(Modifier.fillMaxWidth().padding(17.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Text("КОНТУР ДАННЫХ", color = BruColors.dim, fontSize = 8.sp, letterSpacing = 1.1.sp)
                JournalLine("Парсер", "Ksoup · таблицы групп и занятости преподавателей")
                JournalLine("Локальная база", "SQLDelight · Android SQLite / iOS SQLite")
                JournalLine("Флаги формата", "KMP Settings · версия парсера и маркер схемы")
                JournalLine("Оформление", "KMP Settings · тема и четыре цвета занятий")
            }
        }
        Spacer(Modifier.height(12.dp))
    }

    if (showDeleteConfirmation) {
        val profileName = state.selectedProfile?.name.orEmpty()
        AlertDialog(
            onDismissRequest = { showDeleteConfirmation = false },
            title = { Text("Удалить расписание?") },
            text = {
                Text(
                    "Профиль «$profileName», сохранённые пары, ручные правки и связанные задания будут удалены с устройства.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirmation = false
                        viewModel.deleteSelectedProfile()
                    },
                ) {
                    Text("Удалить", color = BruColors.danger)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmation = false }) {
                    Text("Отмена", color = BruColors.muted)
                }
            },
            containerColor = BruColors.surface,
            titleContentColor = BruColors.foreground,
            textContentColor = BruColors.muted,
        )
    }
}

@Composable
private fun AppearanceSettingsCard(
    state: ScheduleUiState,
    onThemeSelected: (ScheduleAppTheme) -> Unit,
    onLessonColorSelected: (LessonType, String) -> Unit,
    onResetLessonColors: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(21.dp),
        color = BruColors.surface,
        border = BorderStroke(1.dp, BruColors.outline),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(17.dp),
            verticalArrangement = Arrangement.spacedBy(13.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("ВНЕШНИЙ ВИД", color = BruColors.cyan, fontSize = 9.sp, letterSpacing = 1.1.sp, fontWeight = FontWeight.SemiBold)
                Text("Своя палитра", color = BruColors.foreground, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Text("Тема и акценты сохраняются на устройстве и применяются сразу.", color = BruColors.muted, fontSize = 10.sp, lineHeight = 15.sp)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                ScheduleAppTheme.entries.forEach { theme ->
                    ThemeChoice(
                        theme = theme,
                        selected = state.appearance.theme == theme,
                        onClick = { onThemeSelected(theme) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            HorizontalDividerLine()
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("ЦВЕТА ЗАНЯТИЙ", color = BruColors.foreground, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    Text("Выберите акцент для каждого типа пары", color = BruColors.muted, fontSize = 9.sp)
                }
                TextButton(onClick = onResetLessonColors) {
                    Text("Сбросить", color = BruColors.cyan, fontSize = 10.sp)
                }
            }
            LessonAccentEditor(
                label = "Лекция",
                type = LessonType.LECTURE,
                colorHex = state.appearance.lessonAccents.lecture,
                onColorSelected = onLessonColorSelected,
            )
            LessonAccentEditor(
                label = "Лабораторная",
                type = LessonType.LABORATORY,
                colorHex = state.appearance.lessonAccents.laboratory,
                onColorSelected = onLessonColorSelected,
            )
            LessonAccentEditor(
                label = "Практика",
                type = LessonType.PRACTICE,
                colorHex = state.appearance.lessonAccents.practice,
                onColorSelected = onLessonColorSelected,
            )
            LessonAccentEditor(
                label = "Другое",
                type = LessonType.UNKNOWN,
                colorHex = state.appearance.lessonAccents.unknown,
                onColorSelected = onLessonColorSelected,
            )
        }
    }
}

@Composable
private fun ThemeChoice(
    theme: ScheduleAppTheme,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    val preview = themePreview(theme)
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(13.dp),
        color = if (selected) BruColors.elevatedSurface else BruColors.surface,
        border = BorderStroke(1.dp, if (selected) BruColors.indigo else BruColors.outline),
    ) {
        Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Surface(
                modifier = Modifier.fillMaxWidth().height(31.dp),
                shape = RoundedCornerShape(8.dp),
                color = preview.background,
                border = BorderStroke(1.dp, preview.outline),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Box(Modifier.size(width = 25.dp, height = 5.dp).background(preview.accent, RoundedCornerShape(4.dp)))
                    Text("Aa", color = preview.foreground, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            Text(theme.label, color = BruColors.foreground, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Text(
                when (theme) {
                    ScheduleAppTheme.BLUE -> "Насыщенный синий"
                    ScheduleAppTheme.BLACK -> "AMOLED"
                    ScheduleAppTheme.WHITE -> "Светлая"
                },
                color = BruColors.dim,
                fontSize = 7.sp,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun LessonAccentEditor(
    label: String,
    type: LessonType,
    colorHex: String,
    onColorSelected: (LessonType, String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, color = BruColors.muted, fontSize = 10.sp, fontWeight = FontWeight.Medium)
            Text(colorHex.uppercase(), color = BruColors.dim, fontSize = 9.sp, letterSpacing = 0.4.sp)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            LESSON_COLOR_PRESETS.forEach { color ->
                ColorSwatch(
                    colorHex = color,
                    selected = colorHex.equals(color, ignoreCase = true),
                    onClick = { onColorSelected(type, color) },
                )
            }
        }
    }
}

@Composable
private fun ColorSwatch(colorHex: String, selected: Boolean, onClick: () -> Unit) {
    val color = com.example.bruschedule.ui.theme.LessonThemeMapper.parseHex(colorHex) ?: BruColors.indigo
    Box(
        modifier = Modifier
            .size(25.dp)
            .clip(CircleShape)
            .background(color)
            .border(1.dp, if (selected) BruColors.foreground else BruColors.outline, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            Text("✓", color = if (color.luminance() < 0.48f) Color.White else Color.Black, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
    }
}

private data class ThemePreview(
    val background: Color,
    val accent: Color,
    val foreground: Color,
    val outline: Color,
)

private fun themePreview(theme: ScheduleAppTheme): ThemePreview = when (theme) {
    ScheduleAppTheme.BLUE -> ThemePreview(Color(0xFF0A0D12), Color(0xFF8FD9F4), Color(0xFFF2F5FA), Color(0xFF263241))
    ScheduleAppTheme.BLACK -> ThemePreview(Color.Black, Color(0xFF83A9FF), Color(0xFFF5F7FA), Color(0xFF262B35))
    ScheduleAppTheme.WHITE -> ThemePreview(Color(0xFFF4F6FA), Color(0xFF3C57D8), Color(0xFF152033), Color(0xFFD8DFEA))
}

private val LESSON_COLOR_PRESETS = listOf(
    "#A7B9FF",
    "#FF8998",
    "#FFC979",
    "#8FD9F4",
    "#8FDFC0",
    "#B89BFF",
    "#F4B7D4",
    "#A6B0C2",
)

@Composable
private fun JournalLine(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = BruColors.muted, fontSize = 10.sp, modifier = Modifier.weight(1f))
        Text(value, color = BruColors.foreground, fontSize = 10.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun HorizontalDividerLine() {
    Spacer(Modifier.fillMaxWidth().height(1.dp).background(BruColors.outline))
}

private fun formatSyncTimestamp(epochMillis: Long): String {
    val dateTime = Instant.fromEpochMilliseconds(epochMillis).toLocalDateTime(TimeZone.currentSystemDefault())
    val date = dateTime.date
    val time = dateTime.time
    return "${date.dayOfMonth.toString().padStart(2, '0')}.${date.monthNumber.toString().padStart(2, '0')}.${date.year} · ${time.hour.toString().padStart(2, '0')}:${time.minute.toString().padStart(2, '0')}"
}
