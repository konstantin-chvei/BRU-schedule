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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.bruschedule.presentation.DeadlineUiItem
import com.example.bruschedule.presentation.ScheduleUiState
import com.example.bruschedule.presentation.ScheduleViewModel
import com.example.bruschedule.ui.theme.BruColors
import com.example.bruschedule.ui.theme.LessonThemeMapper
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.daysUntil
import kotlinx.datetime.toLocalDateTime

@Composable
fun DeadlinesDashboardScreen(
    state: ScheduleUiState,
    viewModel: ScheduleViewModel,
    contentPadding: PaddingValues,
) {
    val openTasks = state.deadlines.count { !it.task.isCompleted }
    LazyColumn(
        modifier = Modifier.fillMaxSize().background(BruColors.background).padding(contentPadding),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 21.dp, bottom = 26.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Text("РАБОЧИЙ СТОЛ · 02", color = BruColors.cyan, fontSize = 9.sp, letterSpacing = 1.4.sp)
                Text("Дедлайны", color = BruColors.foreground, fontSize = 27.sp, fontWeight = FontWeight.SemiBold)
                Text("Все задания по профилям — от ближайшего срока к дальнему.", color = BruColors.muted, fontSize = 12.sp, lineHeight = 17.sp)
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                DeadlineSummaryTile("АКТИВНЫЕ", openTasks.toString().padStart(2, '0'), BruColors.amber, Modifier.weight(1f))
                DeadlineSummaryTile("ВСЕГО", state.deadlines.size.toString().padStart(2, '0'), BruColors.indigo, Modifier.weight(1f))
            }
            Spacer(Modifier.height(5.dp))
        }
        if (state.deadlines.isEmpty()) {
            item {
                Surface(
                    shape = RoundedCornerShape(22.dp),
                    color = BruColors.surface,
                    border = BorderStroke(1.dp, BruColors.outline),
                ) {
                    Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                        Text("◈", color = BruColors.indigo, fontSize = 24.sp)
                        Text("Список чист", color = BruColors.foreground, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                        Text("Добавьте дедлайн из карточки пары — он появится здесь и будет связан с предметом.", color = BruColors.muted, fontSize = 12.sp, lineHeight = 18.sp)
                    }
                }
            }
        } else {
            items(state.deadlines, key = { it.task.id }) { item ->
                DeadlineCard(item, state.nowEpochMillis) { completed -> viewModel.setTaskCompleted(item.task.id, completed) }
            }
        }
    }
}

@Composable
private fun DeadlineSummaryTile(label: String, value: String, accent: androidx.compose.ui.graphics.Color, modifier: Modifier) {
    Surface(modifier = modifier, shape = RoundedCornerShape(17.dp), color = BruColors.surface, border = BorderStroke(1.dp, BruColors.outline)) {
        Row(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = BruColors.dim, fontSize = 8.sp, letterSpacing = 0.9.sp)
            Text(value, color = accent, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun DeadlineCard(
    item: DeadlineUiItem,
    nowEpochMillis: Long,
    onCompletedChange: (Boolean) -> Unit,
) {
    val task = item.task
    val zone = TimeZone.currentSystemDefault()
    val today = Instant.fromEpochMilliseconds(nowEpochMillis).toLocalDateTime(zone).date
    val dueDate = Instant.fromEpochMilliseconds(task.dueDate).toLocalDateTime(zone).date
    val remainingDays = today.daysUntil(dueDate)
    val urgent = !task.isCompleted && remainingDays <= 1
    val accent = when {
        task.isCompleted -> BruColors.mint
        remainingDays < 0 -> BruColors.danger
        remainingDays <= 1 -> BruColors.amber
        else -> BruColors.cyan
    }
    Surface(
        shape = RoundedCornerShape(19.dp),
        color = BruColors.surface,
        border = BorderStroke(1.dp, if (urgent) accent.copy(alpha = 0.42f) else BruColors.outline),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Checkbox(
                checked = task.isCompleted,
                onCheckedChange = onCompletedChange,
                colors = CheckboxDefaults.colors(
                    checkedColor = BruColors.mint,
                    uncheckedColor = BruColors.dim,
                    checkmarkColor = BruColors.background,
                ),
            )
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    item.lessonType?.let { type ->
                        Surface(shape = CircleShape, color = LessonThemeMapper.typeAccent(type).copy(alpha = 0.18f)) {
                            Spacer(Modifier.size(7.dp).background(LessonThemeMapper.typeAccent(type), CircleShape))
                        }
                    }
                    Text(item.subjectName, color = BruColors.muted, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    item.profileName?.let {
                        Text("· $it", color = BruColors.dim, fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                Text(
                    task.taskTitle,
                    color = if (task.isCompleted) BruColors.muted else BruColors.foreground,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (task.description.isNotBlank()) {
                    Text(task.description, color = BruColors.dim, fontSize = 10.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(formatDeadlineDate(task.dueDate), color = BruColors.foreground, fontSize = 10.sp, fontWeight = FontWeight.Medium)
                Text(
                    when {
                        task.isCompleted -> "ГОТОВО"
                        remainingDays < 0 -> "ПРОСРОЧЕНО"
                        remainingDays == 0 -> "СЕГОДНЯ"
                        remainingDays == 1 -> "ЗАВТРА"
                        else -> "ЧЕРЕЗ $remainingDays ДН."
                    },
                    color = accent,
                    fontSize = 8.sp,
                    letterSpacing = 0.5.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

private fun formatDeadlineDate(epochMillis: Long): String {
    val date = Instant.fromEpochMilliseconds(epochMillis).toLocalDateTime(TimeZone.currentSystemDefault()).date
    return "${date.dayOfMonth.toString().padStart(2, '0')}.${date.monthNumber.toString().padStart(2, '0')}"
}

