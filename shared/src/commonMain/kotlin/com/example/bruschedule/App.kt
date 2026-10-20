package com.example.bruschedule

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.bruschedule.data.AppGraph
import com.example.bruschedule.presentation.MainDestination
import com.example.bruschedule.presentation.ScheduleViewModel
import com.example.bruschedule.presentation.ui.DeadlinesDashboardScreen
import com.example.bruschedule.presentation.ui.ScheduleHomeScreen
import com.example.bruschedule.presentation.ui.ScheduleSettingsScreen
import com.example.bruschedule.ui.theme.BruColors
import com.example.bruschedule.ui.theme.BruScheduleTheme
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun App(graph: AppGraph) {
    val viewModel: ScheduleViewModel = viewModel(
        factory = viewModelFactory {
            initializer {
                ScheduleViewModel(
                    schedules = graph.scheduleRepository,
                    syncRepository = graph.syncRepository,
                    tasks = graph.taskRepository,
                    profileCatalog = graph.profileCatalog,
                    refreshSchedule = graph.refreshSchedule,
                    appearancePreferences = graph.appearancePreferences,
                    deadlineReminderPreferencesRepository = graph.deadlineReminderPreferences,
                    semesterSchedulePreferencesRepository = graph.semesterSchedulePreferences,
                    dailyMessagesRepository = graph.dailyMessages,
                    typographyPreferencesRepository = graph.typographyPreferences,
                    deadlineNotificationScheduler = graph.deadlineNotificationScheduler,
                )
            }
        },
    )
    val state by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(state.notice) {
        state.notice?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.dismissNotice()
        }
    }

    BruScheduleTheme(appearance = state.appearance) {
        val systemDensity = LocalDensity.current
        val appDensity = Density(
            density = systemDensity.density,
            fontScale = systemDensity.fontScale * state.typography.fontScale,
        )
        CompositionLocalProvider(LocalDensity provides appDensity) {
            Scaffold(
                containerColor = BruColors.background,
                snackbarHost = { SnackbarHost(snackbarHostState) },
                bottomBar = {
                    NavigationBar(
                        modifier = Modifier.height(88.dp),
                        containerColor = BruColors.surface,
                        tonalElevation = 4.dp,
                    ) {
                        NavigationDestination(
                            selected = state.destination == MainDestination.SCHEDULE,
                            destination = MainDestination.SCHEDULE,
                            label = "Расписание",
                            onClick = { viewModel.showDestination(MainDestination.SCHEDULE) },
                        )
                        NavigationDestination(
                            selected = state.destination == MainDestination.DEADLINES,
                            destination = MainDestination.DEADLINES,
                            label = "Дедлайны",
                            onClick = { viewModel.showDestination(MainDestination.DEADLINES) },
                        )
                        NavigationDestination(
                            selected = state.destination == MainDestination.SETTINGS,
                            destination = MainDestination.SETTINGS,
                            label = "Настройки",
                            onClick = { viewModel.showDestination(MainDestination.SETTINGS) },
                        )
                    }
                },
            ) { innerPadding ->
                AnimatedContent(
                    targetState = state.destination,
                    modifier = Modifier,
                    transitionSpec = {
                        (fadeIn() + slideInHorizontally { it / 18 }) togetherWith
                            (fadeOut() + slideOutHorizontally { -it / 18 })
                    },
                    label = "main-navigation",
                ) { destination ->
                    when (destination) {
                        MainDestination.SCHEDULE -> ScheduleHomeScreen(state, viewModel, innerPadding)
                        MainDestination.DEADLINES -> DeadlinesDashboardScreen(state, viewModel, innerPadding)
                        MainDestination.SETTINGS -> ScheduleSettingsScreen(state, viewModel, innerPadding)
                    }
                }
            }
        }
    }
}

@Composable
private fun RowScope.NavigationDestination(
    selected: Boolean,
    destination: MainDestination,
    label: String,
    onClick: () -> Unit,
) {
    val scale by animateFloatAsState(
        targetValue = if (selected) 1.04f else 0.98f,
        label = "navigation-scale",
    )
    NavigationBarItem(
        selected = selected,
        onClick = onClick,
        icon = {
            Surface(
                modifier = Modifier.size(48.dp).graphicsLayer { scaleX = scale; scaleY = scale },
                shape = RoundedCornerShape(if (selected) 16.dp else 13.dp),
                color = if (selected) BruColors.indigo.copy(alpha = 0.2f) else Color.Transparent,
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    NavigationGlyph(
                        destination = destination,
                        color = if (selected) BruColors.cyan else BruColors.muted,
                        modifier = Modifier.size(if (selected) 31.dp else 28.dp),
                    )
                }
            }
        },
        label = {
            Text(
                label,
                fontSize = 11.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                color = if (selected) BruColors.foreground else BruColors.muted,
            )
        },
        alwaysShowLabel = true,
        colors = NavigationBarItemDefaults.colors(
            selectedIconColor = BruColors.cyan,
            unselectedIconColor = BruColors.muted,
            selectedTextColor = BruColors.foreground,
            unselectedTextColor = BruColors.muted,
            indicatorColor = Color.Transparent,
        ),
    )
}

@Composable
private fun NavigationGlyph(
    destination: MainDestination,
    color: Color,
    modifier: Modifier = Modifier,
) {
    val accent = BruColors.cyan
    Canvas(modifier) {
        when (destination) {
            MainDestination.SCHEDULE -> drawCalendarGlyph(color, accent)
            MainDestination.DEADLINES -> drawChecklistGlyph(color)
            MainDestination.SETTINGS -> drawSettingsGlyph(color)
        }
    }
}

private fun DrawScope.drawCalendarGlyph(color: Color, accent: Color) {
    val unit = minOf(size.width, size.height) / 32f
    val stroke = 2.1f * unit
    drawRoundRect(
        color = color,
        topLeft = Offset(4f * unit, 5f * unit),
        size = Size(24f * unit, 23f * unit),
        cornerRadius = CornerRadius(4f * unit),
        style = Stroke(width = stroke),
    )
    drawLine(color, Offset(4f * unit, 12f * unit), Offset(28f * unit, 12f * unit), stroke, StrokeCap.Round)
    listOf(10f, 22f).forEach { x ->
        drawLine(color, Offset(x * unit, 3f * unit), Offset(x * unit, 9f * unit), stroke, StrokeCap.Round)
    }
    for (row in 0..1) {
        for (column in 0..2) {
            val x = (9f + column * 6.5f) * unit
            val y = (17f + row * 5.5f) * unit
            if (row == 0 && column == 1) {
                drawRoundRect(
                    color = accent,
                    topLeft = Offset(x, y),
                    size = Size(3.3f * unit, 3.3f * unit),
                    cornerRadius = CornerRadius(1f * unit),
                )
            } else {
                drawCircle(color, radius = 1.35f * unit, center = Offset(x + 1.65f * unit, y + 1.65f * unit))
            }
        }
    }
}

private fun DrawScope.drawChecklistGlyph(color: Color) {
    val unit = minOf(size.width, size.height) / 32f
    val stroke = 2.0f * unit
    drawRoundRect(
        color = color,
        topLeft = Offset(5f * unit, 3.5f * unit),
        size = Size(22f * unit, 25f * unit),
        cornerRadius = CornerRadius(4f * unit),
        style = Stroke(width = stroke),
    )
    for (row in 0..2) {
        val y = (11f + row * 6f) * unit
        drawLine(color, Offset(8f * unit, y), Offset(10f * unit, y + 2f * unit), stroke, StrokeCap.Round)
        drawLine(color, Offset(10f * unit, y + 2f * unit), Offset(13f * unit, y - 2f * unit), stroke, StrokeCap.Round)
        drawLine(color, Offset(16f * unit, y), Offset(23f * unit, y), stroke, StrokeCap.Round)
    }
}

private fun DrawScope.drawSettingsGlyph(color: Color) {
    val unit = minOf(size.width, size.height) / 32f
    val center = Offset(size.width / 2f, size.height / 2f)
    for (tooth in 0..7) {
        val angle = (PI / 4.0 * tooth) - (PI / 2.0)
        val dx = cos(angle).toFloat()
        val dy = sin(angle).toFloat()
        drawLine(
            color = color,
            start = Offset(center.x + dx * 8.5f * unit, center.y + dy * 8.5f * unit),
            end = Offset(center.x + dx * 13f * unit, center.y + dy * 13f * unit),
            strokeWidth = 3.4f * unit,
            cap = StrokeCap.Round,
        )
    }
    drawCircle(color, radius = 8.2f * unit, center = center, style = Stroke(width = 3.1f * unit))
    drawCircle(color, radius = 2.4f * unit, center = center)
}
