package com.example.bruschedule.domain.repository

import com.example.bruschedule.domain.model.AcademicTask
import com.example.bruschedule.domain.model.DeadlineReminderPreferences
import kotlinx.coroutines.flow.Flow

/** App-wide reminder defaults are stored in KMP Settings and observed by the presentation layer. */
interface DeadlineReminderPreferencesRepository {
    fun observe(): Flow<DeadlineReminderPreferences>
    suspend fun update(preferences: DeadlineReminderPreferences)
}

/** Platform boundary for Android AlarmManager and iOS UserNotifications. */
interface DeadlineNotificationScheduler {
    /** Requests notification authorization where the platform requires it. */
    suspend fun requestPermission(): Boolean

    /** Replaces any pending notifications for this task with the supplied minute offsets. */
    suspend fun schedule(task: AcademicTask, offsetsMinutes: List<Int>): Boolean

    suspend fun cancel(taskId: String)
}

/** Used by non-platform tests and tooling that does not provide a native notification service. */
class NoOpDeadlineNotificationScheduler : DeadlineNotificationScheduler {
    override suspend fun requestPermission(): Boolean = true
    override suspend fun schedule(task: AcademicTask, offsetsMinutes: List<Int>): Boolean = true
    override suspend fun cancel(taskId: String) = Unit
}
