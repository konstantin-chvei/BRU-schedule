package com.example.bruschedule

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
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

    BruScheduleTheme {
        Scaffold(
            containerColor = BruColors.background,
            snackbarHost = { SnackbarHost(snackbarHostState) },
            bottomBar = {
                NavigationBar(containerColor = BruColors.surface) {
                    NavigationDestination(
                        selected = state.destination == MainDestination.SCHEDULE,
                        symbol = "◷",
                        label = "Расписание",
                        onClick = { viewModel.showDestination(MainDestination.SCHEDULE) },
                    )
                    NavigationDestination(
                        selected = state.destination == MainDestination.DEADLINES,
                        symbol = "◈",
                        label = "Дедлайны",
                        onClick = { viewModel.showDestination(MainDestination.DEADLINES) },
                    )
                    NavigationDestination(
                        selected = state.destination == MainDestination.SETTINGS,
                        symbol = "⚙",
                        label = "Журнал",
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

@Composable
private fun NavigationDestination(
    selected: Boolean,
    symbol: String,
    label: String,
    onClick: () -> Unit,
) {
    val scale by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (selected) 1.12f else 0.94f,
        label = "navigation-scale",
    )
    NavigationBarItem(
        selected = selected,
        onClick = onClick,
        icon = {
            Text(
                text = symbol,
                modifier = Modifier.graphicsLayer { scaleX = scale; scaleY = scale },
                color = if (selected) BruColors.cyan else BruColors.muted,
            )
        },
        label = { Text(label) },
    )
}
