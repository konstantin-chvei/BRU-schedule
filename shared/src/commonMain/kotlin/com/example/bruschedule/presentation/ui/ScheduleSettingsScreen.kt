package com.example.bruschedule.presentation.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BruColors.background)
            .padding(contentPadding)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 21.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("СИНХРОНИЗАЦИЯ · 03", color = BruColors.cyan, fontSize = 9.sp, letterSpacing = 1.4.sp)
        Text("Журнал и настройки", color = BruColors.foreground, fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
        Text("Проверяйте обновления, локальный кэш и историю версий расписания.", color = BruColors.muted, fontSize = 12.sp, lineHeight = 18.sp)

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
            }
        }

        Surface(shape = RoundedCornerShape(21.dp), color = BruColors.surface, border = BorderStroke(1.dp, BruColors.outline)) {
            Column(Modifier.fillMaxWidth().padding(17.dp), verticalArrangement = Arrangement.spacedBy(11.dp)) {
                Text("OFFLINE-FIRST", color = BruColors.mint, fontSize = 9.sp, letterSpacing = 1.1.sp, fontWeight = FontWeight.SemiBold)
                Text("Сначала локальное. Сеть — потом.", color = BruColors.foreground, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    "Расписание, ручные правки и задания читаются из SQLDelight сразу при запуске. Если сервер недоступен или HTML изменился, текущие данные остаются нетронутыми; неудачная попытка попадёт в журнал.",
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
                JournalLine("Цвета занятий", "Лекция · индиго   Лаба · коралл   Практика · янтарь")
            }
        }
        Spacer(Modifier.height(12.dp))
    }
}

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
