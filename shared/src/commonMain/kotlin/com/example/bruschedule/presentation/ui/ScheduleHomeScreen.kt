package com.example.bruschedule.presentation.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.bruschedule.domain.model.Lesson
import com.example.bruschedule.domain.model.LessonType
import com.example.bruschedule.domain.model.ScheduleProfileType
import com.example.bruschedule.presentation.LessonUiItem
import com.example.bruschedule.presentation.ScheduleUiState
import com.example.bruschedule.presentation.ScheduleViewModel
import com.example.bruschedule.ui.theme.BruColors
import com.example.bruschedule.ui.theme.LessonThemeMapper
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.minus
import kotlinx.datetime.plus

@Composable
fun ScheduleHomeScreen(
    state: ScheduleUiState,
    viewModel: ScheduleViewModel,
    contentPadding: PaddingValues,
) {
    var addProfileVisible by remember { mutableStateOf(false) }
    var showSemesterCalendar by remember { mutableStateOf(false) }
    var lessonInEditor by remember { mutableStateOf<Lesson?>(null) }
    var isNewLesson by remember { mutableStateOf(false) }
    var deadlineEditorVisible by remember { mutableStateOf(false) }
    var deadlineInitialLesson by remember { mutableStateOf<Lesson?>(null) }
    var deadlineLessonToPersist by remember { mutableStateOf<Lesson?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BruColors.background)
            .padding(contentPadding)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
    ) {
        Spacer(Modifier.height(16.dp))
        ScheduleTopBar(
            dailyMessage = state.dailyMessage,
            refreshing = state.isRefreshing,
            onRefresh = viewModel::refreshSelectedProfile,
        )
        Spacer(Modifier.height(22.dp))

        if (state.isLoading) {
            ScheduleLoadingStatusCard(title = "Загружаем сохранённые данные", detail = "Поднимаем расписание из локальной базы.")
        } else if (state.profiles.isEmpty()) {
            FirstProfileCard(onAdd = { addProfileVisible = true })
        } else {
            ProfileRail(
                profiles = state.profiles,
                selectedProfileId = state.selectedProfile?.id,
                onSelect = viewModel::selectProfile,
                onAdd = { addProfileVisible = true },
            )
            Spacer(Modifier.height(22.dp))
            WeekCalendarRail(
                selectedDate = state.selectedDate,
                today = state.todayDate,
                datesWithLessons = state.weekDatesWithLessons,
                allDatesWithLessons = state.allScheduleDatesWithLessons,
                coverageStartDate = state.scheduleCoverageStartDate,
                coverageEndDate = state.scheduleCoverageEndDate,
                showCycleIndicator = state.hasRemoteSchedule,
                showSemesterCalendar = showSemesterCalendar,
                onToggleSemesterCalendar = { showSemesterCalendar = !showSemesterCalendar },
                onSelect = viewModel::selectDate,
                onPreviousWeek = { viewModel.changeWeek(-1) },
                onNextWeek = { viewModel.changeWeek(1) },
            )
            Spacer(Modifier.height(20.dp))
            DaySummary(
                date = state.selectedDate,
                lessonCount = state.lessons.size,
                pendingTaskCount = state.lessons.sumOf { it.pendingTaskCount },
            )
            Spacer(Modifier.height(14.dp))

            val syncError = state.syncState?.lastError?.takeIf(String::isNotBlank)
            when {
                !state.hasRemoteSchedule && state.isRefreshing -> ScheduleLoadingStatusCard(
                    title = "Загружаем расписание",
                    detail = "Получаем расписание и сохраняем его на устройстве. Это может занять несколько секунд.",
                )
                !state.hasRemoteSchedule && syncError != null -> ScheduleErrorStatusCard(
                    message = syncError,
                    onRetry = viewModel::refreshSelectedProfile,
                )
                !state.hasRemoteSchedule -> ScheduleNotLoadedCard(onRetry = viewModel::refreshSelectedProfile)
                state.isRefreshing -> ScheduleRefreshingBanner()
                syncError != null -> CachedScheduleWarning(message = syncError, onRetry = viewModel::refreshSelectedProfile)
            }
            Spacer(Modifier.height(12.dp))

            if (state.lessons.isEmpty() && state.hasRemoteSchedule) {
                EmptyDayCard(
                    date = state.selectedDate,
                    onAddLesson = {
                        viewModel.newLessonDraft()?.let {
                            isNewLesson = true
                            lessonInEditor = it
                        }
                    },
                )
            } else if (state.lessons.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    state.lessons.forEach { item ->
                        LessonCard(
                            item = item,
                            onClick = {
                                isNewLesson = false
                                lessonInEditor = item.lesson
                            },
                        )
                    }
                    TextButton(
                        onClick = {
                            viewModel.newLessonDraft()?.let {
                                isNewLesson = true
                                lessonInEditor = it
                            }
                        },
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                    ) {
                        Text("＋ Добавить свою пару", color = BruColors.muted)
                    }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }

    if (addProfileVisible) {
        AddProfileBottomSheet(
            searchState = state.profileSearch,
            onClose = { addProfileVisible = false },
            onSearch = viewModel::searchProfiles,
            onAddManual = { type, name, url ->
                viewModel.saveManualProfile(type, name, url)
                addProfileVisible = false
            },
            onSelectCandidate = { candidate ->
                viewModel.addProfileCandidate(candidate)
                addProfileVisible = false
            },
        )
    }

    val editingLesson = lessonInEditor
    if (editingLesson != null) {
        LessonActionBottomSheet(
            lesson = editingLesson,
            isNew = isNewLesson,
            onDismiss = { lessonInEditor = null },
            onSaveLesson = { saved ->
                viewModel.saveCustomLesson(saved)
                lessonInEditor = null
            },
            onCreateTask = { lesson ->
                val shouldPersistLesson = isNewLesson || editingLesson.isCustom || lesson.hasEditableChangesFrom(editingLesson)
                deadlineInitialLesson = if (shouldPersistLesson) lesson else lesson.copy(isCustom = false)
                deadlineLessonToPersist = lesson.takeIf { shouldPersistLesson }
                lessonInEditor = null
                deadlineEditorVisible = true
            },
        )
    }
    if (deadlineEditorVisible) {
        DeadlineEditorBottomSheet(
            state = state,
            initialLesson = deadlineInitialLesson,
            lessonToPersist = deadlineLessonToPersist,
            onDismiss = {
                deadlineEditorVisible = false
                deadlineInitialLesson = null
                deadlineLessonToPersist = null
            },
            onSave = { draft ->
                viewModel.addTask(draft)
                deadlineEditorVisible = false
                deadlineInitialLesson = null
                deadlineLessonToPersist = null
            },
        )
    }
}

@Composable
private fun ScheduleTopBar(
    dailyMessage: String,
    refreshing: Boolean,
    onRefresh: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(11.dp),
        ) {
            BrandCalendarGlyph()
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("BRU / SCHEDULE", color = BruColors.foreground, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
                Text(
                    dailyMessage,
                    color = BruColors.muted,
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Surface(
            onClick = onRefresh,
            enabled = !refreshing,
            shape = CircleShape,
            color = BruColors.elevatedSurface,
            border = BorderStroke(1.dp, BruColors.outline),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                Text(if (refreshing) "···" else "↻", color = BruColors.cyan, fontSize = 17.sp)
                Text(if (refreshing) "Загрузка" else "Обновить", color = BruColors.foreground, fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun BrandCalendarGlyph() {
    val ink = BruColors.foreground
    val accent = BruColors.cyan
    Canvas(Modifier.size(45.dp)) {
        drawBrandCalendarGlyph(ink, accent)
    }
}

private fun DrawScope.drawBrandCalendarGlyph(ink: Color, accent: Color) {
    val unit = minOf(size.width, size.height) / 44f
    val stroke = 2.1f * unit
    val left = 5f * unit
    val top = 7f * unit
    val frameSize = Size(34f * unit, 30f * unit)

    listOf(14f, 30f).forEach { x ->
        drawLine(ink, Offset(x * unit, 3f * unit), Offset(x * unit, 12f * unit), stroke, StrokeCap.Round)
    }
    drawRoundRect(
        color = ink,
        topLeft = Offset(left, top),
        size = frameSize,
        cornerRadius = CornerRadius(5f * unit),
        style = Stroke(width = stroke),
    )
    drawLine(ink, Offset(left, 17f * unit), Offset(left + frameSize.width, 17f * unit), stroke, StrokeCap.Round)

    val cellSize = 3.2f * unit
    val cellGap = 2.1f * unit
    val gridWidth = cellSize * 4f + cellGap * 3f
    val gridLeft = (size.width - gridWidth) / 2f
    for (row in 0..1) {
        for (column in 0..3) {
            val x = gridLeft + column * (cellSize + cellGap)
            val y = (21f + row * 7f) * unit
            drawRoundRect(
                color = if (row == 0 && column == 2) accent else ink.copy(alpha = 0.9f),
                topLeft = Offset(x, y),
                size = Size(cellSize, cellSize),
                cornerRadius = CornerRadius(1f * unit),
            )
        }
    }
}

@Composable
private fun ProfileRail(
    profiles: List<com.example.bruschedule.domain.model.ScheduleProfile>,
    selectedProfileId: String?,
    onSelect: (String) -> Unit,
    onAdd: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionEyebrow("МОИ РАСПИСАНИЯ", trailing = "${profiles.size} проф.")
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            profiles.forEach { profile ->
                val selected = profile.id == selectedProfileId
                Surface(
                    onClick = { onSelect(profile.id) },
                    shape = RoundedCornerShape(15.dp),
                    color = if (selected) BruColors.indigo.copy(alpha = 0.14f) else BruColors.surface,
                    border = BorderStroke(1.dp, if (selected) BruColors.indigo.copy(alpha = 0.7f) else BruColors.outline),
                ) {
                    Column(Modifier.padding(horizontal = 13.dp, vertical = 9.dp)) {
                        Text(
                            profile.name,
                            color = if (selected) BruColors.foreground else BruColors.muted,
                            fontSize = 12.sp,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                        )
                        Text(
                            if (profile.type == ScheduleProfileType.TEACHER) "ПРЕПОДАВАТЕЛЬ" else "ГРУППА",
                            color = if (selected) BruColors.cyan else BruColors.dim,
                            fontSize = 8.sp,
                            letterSpacing = 1.sp,
                        )
                    }
                }
            }
            Surface(
                onClick = onAdd,
                shape = RoundedCornerShape(15.dp),
                color = BruColors.surface,
                border = BorderStroke(1.dp, BruColors.outline),
            ) {
                Text("＋", modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp), color = BruColors.cyan, fontSize = 17.sp)
            }
        }
    }
}

@Composable
private fun WeekCalendarRail(
    selectedDate: LocalDate,
    today: LocalDate,
    datesWithLessons: Set<String>,
    allDatesWithLessons: Set<String>,
    coverageStartDate: LocalDate?,
    coverageEndDate: LocalDate?,
    showCycleIndicator: Boolean,
    showSemesterCalendar: Boolean,
    onToggleSemesterCalendar: () -> Unit,
    onSelect: (LocalDate) -> Unit,
    onPreviousWeek: () -> Unit,
    onNextWeek: () -> Unit,
) {
    val monday = selectedDate.minus(DatePeriod(days = selectedDate.dayOfWeek.isoDayNumber - 1))
    val days = (0..6).map { monday.plus(DatePeriod(days = it)) }
    val firstCoveredWeek = coverageStartDate?.startOfWeek()
    val lastCoveredWeek = coverageEndDate?.startOfWeek()
    val hasSemesterCycle = showCycleIndicator && coverageStartDate != null && coverageEndDate != null &&
        coverageEndDate > coverageStartDate
    val canGoPrevious = firstCoveredWeek != null && monday.minus(DatePeriod(days = 7)) >= firstCoveredWeek
    val canGoNext = lastCoveredWeek != null && monday.plus(DatePeriod(days = 7)) <= lastCoveredWeek
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                SectionEyebrow(
                    if (showSemesterCalendar) "СЕМЕСТР · ОБЗОР" else "НЕДЕЛЯ · ${weekNumberHint(monday)}",
                    trailing = if (hasSemesterCycle) coverageEndDate?.let { "ДО ${it.toRuDate().uppercase()}" } else null,
                )
                Text(
                    if (showSemesterCalendar && coverageStartDate != null && coverageEndDate != null) {
                        "${coverageStartDate.toRuDate()} — ${coverageEndDate.toRuDate()}"
                    } else {
                        "${monday.toRuDate()} — ${monday.plus(DatePeriod(days = 6)).toRuDate()}"
                    },
                    color = BruColors.muted,
                    fontSize = 12.sp,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                WeekArrow("‹", onPreviousWeek, enabled = canGoPrevious)
                WeekArrow("›", onNextWeek, enabled = canGoNext)
            }
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CalendarModePill("Неделя", selected = !showSemesterCalendar, modifier = Modifier.weight(1f), onClick = { if (showSemesterCalendar) onToggleSemesterCalendar() })
            CalendarModePill("Весь семестр", selected = showSemesterCalendar, modifier = Modifier.weight(1f), onClick = { if (!showSemesterCalendar) onToggleSemesterCalendar() })
        }
        if (showSemesterCalendar) {
            SemesterMonthGrid(
                selectedDate = selectedDate,
                today = today,
                datesWithLessons = allDatesWithLessons,
                coverageStartDate = coverageStartDate,
                coverageEndDate = coverageEndDate,
                onSelect = onSelect,
            )
        } else {
            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                val showAllDays = maxWidth >= 364.dp
                val pillGap = if (showAllDays) 5.dp else 8.dp
                val pillWidth = if (showAllDays) {
                    ((maxWidth - 42.dp) / 7f).coerceAtMost(52.dp)
                } else {
                    ((maxWidth - 12.dp - 40.dp) / 6f).coerceIn(36.dp, 56.dp)
                }
                Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                    Spacer(Modifier.width(6.dp))
                    days.forEachIndexed { index, date ->
                        if (index > 0) Spacer(Modifier.width(pillGap))
                        DayPill(
                            date = date,
                            pillWidth = pillWidth,
                            selected = date == selectedDate,
                            isToday = date == today,
                            hasLessons = date.toString() in datesWithLessons,
                            enabled = coverageStartDate != null && coverageEndDate != null &&
                                date >= coverageStartDate && date <= coverageEndDate,
                            onClick = { onSelect(date) },
                        )
                    }
                    Spacer(Modifier.width(6.dp))
                }
            }
        }
    }
}

@Composable
private fun CalendarModePill(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(13.dp),
        color = if (selected) BruColors.indigo.copy(alpha = 0.17f) else BruColors.surface,
        border = BorderStroke(1.dp, if (selected) BruColors.indigo else BruColors.outline),
    ) {
        Text(
            label,
            modifier = Modifier.padding(vertical = 10.dp),
            color = if (selected) BruColors.cyan else BruColors.muted,
            fontSize = 11.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

@Composable
private fun SemesterMonthGrid(
    selectedDate: LocalDate,
    today: LocalDate,
    datesWithLessons: Set<String>,
    coverageStartDate: LocalDate?,
    coverageEndDate: LocalDate?,
    onSelect: (LocalDate) -> Unit,
) {
    if (coverageStartDate == null || coverageEndDate == null) {
        Text("Нет данных о периоде расписания.", color = BruColors.muted, fontSize = 11.sp)
        return
    }
    val months = mutableListOf<LocalDate>()
    var cursor = LocalDate(coverageStartDate.year, coverageStartDate.monthNumber, 1)
    val finalMonth = LocalDate(coverageEndDate.year, coverageEndDate.monthNumber, 1)
    while (cursor <= finalMonth) {
        months += cursor
        cursor = cursor.plus(DatePeriod(months = 1))
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        months.forEach { firstOfMonth ->
            val daysInMonth = firstOfMonth.plus(DatePeriod(months = 1)).minus(DatePeriod(days = 1)).dayOfMonth
            val emptyCells = firstOfMonth.dayOfWeek.isoDayNumber - 1
            val cells: List<LocalDate?> = List(emptyCells) { null } +
                (0 until daysInMonth).map { day -> firstOfMonth.plus(DatePeriod(days = day)) }
            val paddedCells = cells + List((7 - cells.size % 7) % 7) { null }
            Surface(
                shape = RoundedCornerShape(17.dp),
                color = BruColors.surface,
                border = BorderStroke(1.dp, BruColors.outline),
            ) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 11.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text(
                        "${monthLabel(firstOfMonth.monthNumber)} ${firstOfMonth.year}",
                        color = BruColors.foreground,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Row(Modifier.fillMaxWidth()) {
                        listOf("Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс").forEach { day ->
                            Text(day, modifier = Modifier.weight(1f), color = BruColors.dim, fontSize = 8.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                        }
                    }
                    paddedCells.chunked(7).forEach { week ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                            week.forEach { date ->
                                Box(Modifier.weight(1f).height(38.dp), contentAlignment = Alignment.Center) {
                                    if (date != null) {
                                        val inCoverage = date >= coverageStartDate && date <= coverageEndDate
                                        SemesterDayCell(
                                            date = date,
                                            selected = date == selectedDate,
                                            isToday = date == today,
                                            hasLessons = date.toString() in datesWithLessons,
                                            enabled = inCoverage,
                                            onClick = { onSelect(date) },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SemesterDayCell(
    date: LocalDate,
    selected: Boolean,
    isToday: Boolean,
    hasLessons: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(35.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(when {
                selected -> BruColors.indigo.copy(alpha = 0.24f)
                else -> Color.Transparent
            })
            .border(
                width = if (selected || isToday) 1.dp else 0.dp,
                color = when { selected -> BruColors.indigo; isToday -> BruColors.cyan.copy(alpha = 0.6f); else -> Color.Transparent },
                shape = RoundedCornerShape(9.dp),
            )
            .clickable(enabled = enabled, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(date.dayOfMonth.toString(), color = if (enabled) BruColors.foreground else BruColors.dim.copy(alpha = 0.45f), fontSize = 10.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
        Box(Modifier.padding(top = 2.dp).size(3.dp).clip(CircleShape).background(if (hasLessons) BruColors.cyan else Color.Transparent))
    }
}

private fun monthLabel(monthNumber: Int): String = when (monthNumber) {
    1 -> "Январь"
    2 -> "Февраль"
    3 -> "Март"
    4 -> "Апрель"
    5 -> "Май"
    6 -> "Июнь"
    7 -> "Июль"
    8 -> "Август"
    9 -> "Сентябрь"
    10 -> "Октябрь"
    11 -> "Ноябрь"
    else -> "Декабрь"
}

@Composable
private fun WeekArrow(symbol: String, onClick: () -> Unit, enabled: Boolean) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(13.dp),
        color = if (enabled) BruColors.surface else BruColors.background,
        border = BorderStroke(1.dp, if (enabled) BruColors.outline else BruColors.outline.copy(alpha = 0.45f)),
    ) {
        Box(Modifier.size(width = 44.dp, height = 42.dp), contentAlignment = Alignment.Center) {
            Text(symbol, color = if (enabled) BruColors.foreground else BruColors.dim.copy(alpha = 0.55f), fontSize = 25.sp)
        }
    }
}

@Composable
private fun DayPill(
    date: LocalDate,
    pillWidth: Dp,
    selected: Boolean,
    isToday: Boolean,
    hasLessons: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val scale by animateFloatAsState(if (selected) 1.04f else 1f, animationSpec = tween(180), label = "day-select-scale")
    val borderColor by animateColorAsState(
        if (selected) BruColors.indigo else if (isToday) BruColors.cyan.copy(alpha = 0.6f) else BruColors.outline,
        animationSpec = tween(180),
        label = "day-select-border",
    )
    Column(
        modifier = Modifier
            .width(pillWidth)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(17.dp))
            .background(if (selected) BruColors.indigo.copy(alpha = 0.16f) else BruColors.surface)
            .border(1.dp, borderColor, RoundedCornerShape(17.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(weekdayShort(date), color = when {
            selected -> BruColors.cyan
            enabled -> BruColors.muted
            else -> BruColors.dim.copy(alpha = 0.45f)
        }, fontSize = 9.sp, letterSpacing = 0.5.sp)
        Text(
            date.dayOfMonth.toString().padStart(2, '0'),
            color = if (enabled) BruColors.foreground else BruColors.dim.copy(alpha = 0.45f),
            fontWeight = FontWeight.SemiBold,
            fontSize = 15.sp,
        )
        Box(
            Modifier
                .size(4.dp)
                .clip(CircleShape)
                .background(
                    when {
                        selected -> BruColors.indigo
                        hasLessons -> BruColors.mint
                        isToday -> BruColors.cyan
                        else -> Color.Transparent
                    },
                ),
        )
    }
}

@Composable
private fun DaySummary(date: LocalDate, lessonCount: Int, pendingTaskCount: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
        SummaryTile(
            modifier = Modifier.weight(1f),
            eyebrow = "${weekdayLong(date).uppercase()} · ${date.toRuDate()}",
            value = lessonCount.toString().padStart(2, '0'),
            caption = "пар в расписании",
            accent = BruColors.cyan,
        )
        SummaryTile(
            modifier = Modifier.weight(1f),
            eyebrow = "АКАДЕМИЧЕСКИЕ ЗАДАЧИ",
            value = pendingTaskCount.toString().padStart(2, '0'),
            caption = "открытых задач",
            accent = if (pendingTaskCount > 0) BruColors.amber else BruColors.mint,
        )
    }
}

@Composable
private fun SummaryTile(
    modifier: Modifier,
    eyebrow: String,
    value: String,
    caption: String,
    accent: Color,
) {
    Surface(
        modifier = modifier.heightIn(min = 98.dp),
        shape = RoundedCornerShape(19.dp),
        color = BruColors.surface,
        border = BorderStroke(1.dp, BruColors.outline),
    ) {
        Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(eyebrow, color = BruColors.dim, fontSize = 8.sp, letterSpacing = 0.75.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                Text(value, color = accent, fontSize = 26.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold)
                Text(caption, color = BruColors.muted, fontSize = 10.sp, modifier = Modifier.padding(bottom = 3.dp))
            }
        }
    }
}

@Composable
private fun LessonCard(item: LessonUiItem, onClick: () -> Unit) {
    val lesson = item.lesson
    val accent = LessonThemeMapper.accent(lesson)
    val stroke by animateColorAsState(
        if (item.isCurrent) accent.copy(alpha = 0.85f) else BruColors.outline,
        animationSpec = tween(250),
        label = "lesson-current-stroke",
    )
    Surface(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize()
            .shadow(if (item.isCurrent) 14.dp else 0.dp, RoundedCornerShape(21.dp), ambientColor = accent.copy(alpha = 0.18f), spotColor = accent.copy(alpha = 0.16f)),
        shape = RoundedCornerShape(21.dp),
        color = if (item.isCurrent) BruColors.elevatedSurface else BruColors.surface,
        border = BorderStroke(if (item.isCurrent) 1.2.dp else 0.7.dp, stroke),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .heightIn(min = 102.dp)
                    .clip(RoundedCornerShape(topEnd = 4.dp, bottomEnd = 4.dp))
                    .background(accent),
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 13.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(Modifier.width(61.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(lesson.startTime, color = BruColors.foreground, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Text(lesson.endTime, color = BruColors.muted, fontSize = 11.sp)
                    Spacer(Modifier.height(1.dp))
                    Text("${lesson.slotNumber.takeIf { it > 0 } ?: "—"} ПАРА", color = BruColors.dim, fontSize = 8.sp, letterSpacing = 0.4.sp)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        TypeTag(lesson.type, accent)
                        if (item.isCurrent) TypeTagLabel("СЕЙЧАС", BruColors.mint)
                        if (lesson.isCustom) TypeTagLabel("МОЁ", BruColors.cyan)
                    }
                    Text(
                        lesson.subjectName.ifBlank { "Новая пара" },
                        color = BruColors.foreground,
                        fontSize = 15.sp,
                        lineHeight = 19.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (lesson.teacherName.isNotBlank()) {
                        Text(lesson.teacherName, color = BruColors.muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (lesson.classroom.isNotBlank()) {
                        Text("⌖  ${lesson.classroom}", color = BruColors.muted, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (item.pendingTaskCount > 0) {
                    Surface(shape = CircleShape, color = accent.copy(alpha = 0.14f)) {
                        Text(
                            item.pendingTaskCount.toString(),
                            modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp),
                            color = accent,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TypeTag(type: LessonType, accent: Color) {
    val label = when (type) {
        LessonType.LECTURE -> "ЛЕКЦИЯ"
        LessonType.LABORATORY -> "ЛАБОРАТОРНАЯ"
        LessonType.PRACTICE -> "ПРАКТИКА"
        LessonType.UNKNOWN -> "ЗАНЯТИЕ"
    }
    TypeTagLabel(label, accent)
}

@Composable
private fun TypeTagLabel(label: String, accent: Color) {
    Surface(shape = RoundedCornerShape(7.dp), color = accent.copy(alpha = 0.12f)) {
        Text(label, modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp), color = accent, fontSize = 8.sp, letterSpacing = 0.55.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun ScheduleLoadingStatusCard(title: String, detail: String) {
    Surface(
        modifier = Modifier.fillMaxWidth().animateContentSize(),
        shape = RoundedCornerShape(20.dp),
        color = BruColors.elevatedSurface,
        border = BorderStroke(1.dp, BruColors.cyan.copy(alpha = 0.28f)),
    ) {
        Row(
            modifier = Modifier.padding(17.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(13.dp),
        ) {
            CircularProgressIndicator(modifier = Modifier.size(21.dp), color = BruColors.cyan, strokeWidth = 2.dp)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, color = BruColors.foreground, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Text(detail, color = BruColors.muted, fontSize = 10.sp, lineHeight = 15.sp)
            }
        }
    }
}

@Composable
private fun ScheduleNotLoadedCard(onRetry: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = BruColors.surface,
        border = BorderStroke(1.dp, BruColors.outline),
    ) {
        Column(Modifier.padding(17.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("РАСПИСАНИЕ ЕЩЁ НЕ ЗАГРУЖЕНО", color = BruColors.cyan, fontSize = 8.sp, letterSpacing = 1.sp, fontWeight = FontWeight.SemiBold)
            Text("Загрузите расписание, чтобы сохранить его офлайн. Обычный недельный цикл будет показан на семестр.", color = BruColors.muted, fontSize = 11.sp, lineHeight = 16.sp)
            TextButton(onClick = onRetry) { Text("Загрузить расписание  →", color = BruColors.cyan, fontSize = 11.sp) }
        }
    }
}

@Composable
private fun ScheduleErrorStatusCard(message: String, onRetry: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = BruColors.danger.copy(alpha = 0.07f),
        border = BorderStroke(1.dp, BruColors.danger.copy(alpha = 0.30f)),
    ) {
        Column(Modifier.padding(17.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text("НЕ УДАЛОСЬ ЗАГРУЗИТЬ", color = BruColors.danger, fontSize = 8.sp, letterSpacing = 1.sp, fontWeight = FontWeight.SemiBold)
            Text(message, color = BruColors.muted, fontSize = 10.sp, lineHeight = 15.sp, maxLines = 4, overflow = TextOverflow.Ellipsis)
            TextButton(onClick = onRetry) { Text("Повторить", color = BruColors.danger, fontSize = 11.sp) }
        }
    }
}

@Composable
private fun ScheduleRefreshingBanner() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = BruColors.cyan.copy(alpha = 0.07f),
        border = BorderStroke(1.dp, BruColors.cyan.copy(alpha = 0.2f)),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 13.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            CircularProgressIndicator(modifier = Modifier.size(15.dp), color = BruColors.cyan, strokeWidth = 1.7.dp)
            Text("Обновляем семестровый цикл · сохранённое расписание доступно", color = BruColors.muted, fontSize = 9.sp, lineHeight = 13.sp)
        }
    }
}

@Composable
private fun CachedScheduleWarning(message: String, onRetry: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = BruColors.amber.copy(alpha = 0.07f),
        border = BorderStroke(1.dp, BruColors.amber.copy(alpha = 0.24f)),
    ) {
        Row(
            modifier = Modifier.padding(start = 13.dp, top = 9.dp, bottom = 9.dp, end = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("!", color = BruColors.amber, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Text("Не удалось обновить. Показан локальный кэш: $message", modifier = Modifier.weight(1f), color = BruColors.muted, fontSize = 9.sp, lineHeight = 13.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
            TextButton(onClick = onRetry) { Text("Повтор", color = BruColors.amber, fontSize = 9.sp) }
        }
    }
}

@Composable
private fun EmptyDayCard(date: LocalDate, onAddLesson: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(22.dp),
        color = BruColors.surface,
        border = BorderStroke(1.dp, BruColors.outline),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(21.dp),
            verticalArrangement = Arrangement.spacedBy(11.dp),
        ) {
            Text("◌", color = BruColors.indigo, fontSize = 24.sp)
            Text("Окно для своих дел", color = BruColors.foreground, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Text("На ${weekdayLong(date).lowercase()} занятий нет. Можно добавить личную пару или оставить день свободным.", color = BruColors.muted, fontSize = 12.sp, lineHeight = 18.sp)
            Button(
                onClick = onAddLesson,
                colors = ButtonDefaults.buttonColors(containerColor = BruColors.indigo, contentColor = BruColors.background),
                shape = RoundedCornerShape(13.dp),
            ) { Text("＋ Своя пара", fontWeight = FontWeight.SemiBold) }
        }
    }
}

@Composable
private fun FirstProfileCard(onAdd: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(26.dp),
        color = BruColors.surface,
        border = BorderStroke(1.dp, BruColors.outline),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("01 / ПЕРВЫЙ ПРОФИЛЬ", color = BruColors.cyan, fontSize = 9.sp, letterSpacing = 1.2.sp)
            Text("Прогуливайте по графику.", color = BruColors.foreground, fontSize = 26.sp, lineHeight = 31.sp, fontWeight = FontWeight.SemiBold)
            Text(
                "Добавьте группу, друга или преподавателя. Идеальный хаос требует идеального расписания.",
                color = BruColors.muted,
                fontSize = 13.sp,
                lineHeight = 20.sp,
            )
            Button(
                onClick = onAdd,
                colors = ButtonDefaults.buttonColors(containerColor = BruColors.indigo, contentColor = BruColors.background),
                shape = RoundedCornerShape(14.dp),
            ) { Text("Найти расписание  →", fontWeight = FontWeight.SemiBold) }
        }
    }
}

@Composable
private fun SectionEyebrow(text: String, trailing: String? = null) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(text, color = BruColors.dim, fontSize = 9.sp, letterSpacing = 1.2.sp, fontWeight = FontWeight.SemiBold)
        trailing?.let { Text(it, color = BruColors.dim, fontSize = 9.sp) }
    }
}

private fun Lesson.hasEditableChangesFrom(original: Lesson): Boolean =
    subjectName != original.subjectName ||
        teacherName != original.teacherName ||
        classroom != original.classroom ||
        lessonDate != original.lessonDate ||
        dayOfWeek != original.dayOfWeek ||
        startTime != original.startTime ||
        endTime != original.endTime ||
        slotNumber != original.slotNumber ||
        type != original.type

private fun LocalDate.startOfWeek(): LocalDate =
    minus(DatePeriod(days = dayOfWeek.isoDayNumber - 1))

private fun LocalDate.toRuDate(): String =
    "${dayOfMonth.toString().padStart(2, '0')} ${monthNameShort(monthNumber)}"

private fun weekdayShort(date: LocalDate): String = when (date.dayOfWeek.isoDayNumber) {
    1 -> "ПН"
    2 -> "ВТ"
    3 -> "СР"
    4 -> "ЧТ"
    5 -> "ПТ"
    6 -> "СБ"
    else -> "ВС"
}

private fun weekdayLong(date: LocalDate): String = when (date.dayOfWeek.isoDayNumber) {
    1 -> "Понедельник"
    2 -> "Вторник"
    3 -> "Среда"
    4 -> "Четверг"
    5 -> "Пятница"
    6 -> "Суббота"
    else -> "Воскресенье"
}

private fun monthNameShort(month: Int): String = when (month) {
    1 -> "янв"
    2 -> "фев"
    3 -> "мар"
    4 -> "апр"
    5 -> "май"
    6 -> "июн"
    7 -> "июл"
    8 -> "авг"
    9 -> "сен"
    10 -> "окт"
    11 -> "ноя"
    else -> "дек"
}

private fun weekNumberHint(monday: LocalDate): String = "${monday.dayOfMonth.toString().padStart(2, '0')}.${monday.monthNumber.toString().padStart(2, '0')}"
