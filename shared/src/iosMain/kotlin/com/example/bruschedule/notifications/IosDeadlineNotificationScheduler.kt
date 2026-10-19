package com.example.bruschedule.notifications

import com.example.bruschedule.domain.model.AcademicTask
import com.example.bruschedule.domain.model.DeadlineReminderIntervals
import com.example.bruschedule.domain.repository.DeadlineNotificationScheduler
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.Foundation.NSDate
import platform.UserNotifications.UNAuthorizationOptionAlert
import platform.UserNotifications.UNAuthorizationOptionBadge
import platform.UserNotifications.UNAuthorizationOptionSound
import platform.UserNotifications.UNMutableNotificationContent
import platform.UserNotifications.UNNotificationRequest
import platform.UserNotifications.UNNotificationSound
import platform.UserNotifications.UNTimeIntervalNotificationTrigger
import platform.UserNotifications.UNUserNotificationCenter
import kotlin.coroutines.resume

/** Local notifications are scheduled natively and remain available while the app is closed. */
class IosDeadlineNotificationScheduler : DeadlineNotificationScheduler {
    private val center = UNUserNotificationCenter.currentNotificationCenter()

    override suspend fun requestPermission(): Boolean = suspendCancellableCoroutine { continuation ->
        center.requestAuthorizationWithOptions(
            UNAuthorizationOptionAlert or UNAuthorizationOptionBadge or UNAuthorizationOptionSound,
        ) { granted, _ ->
            if (continuation.isActive) continuation.resume(granted)
        }
    }

    override suspend fun schedule(task: AcademicTask, offsetsMinutes: List<Int>): Boolean {
        cancel(task.id)
        if (task.isCompleted || offsetsMinutes.isEmpty()) return true

        val nowMillis = (NSDate().timeIntervalSince1970 * 1_000.0).toLong()
        offsetsMinutes.distinct().forEach { offset ->
            val reminderAt = task.dueDate - offset * 60_000L
            val secondsUntilReminder = (reminderAt - nowMillis) / 1_000.0
            if (secondsUntilReminder <= 0.0) return@forEach

            val content = UNMutableNotificationContent().apply {
                title = "Скоро дедлайн"
                body = "${task.taskTitle} · срок ${formatDate(task.dueDate)}"
                sound = UNNotificationSound.defaultSound()
            }
            val trigger = UNTimeIntervalNotificationTrigger.triggerWithTimeInterval(
                secondsUntilReminder,
                repeats = false,
            )
            val request = UNNotificationRequest.requestWithIdentifier(
                identifier = notificationId(task.id, offset),
                content = content,
                trigger = trigger,
            )
            center.addNotificationRequest(request) { _ -> }
        }
        return true
    }

    override suspend fun cancel(taskId: String) {
        val identifiers = DeadlineReminderIntervals.choices.map { notificationId(taskId, it) }
        center.removePendingNotificationRequestsWithIdentifiers(identifiers)
        center.removeDeliveredNotificationsWithIdentifiers(identifiers)
    }

    private fun notificationId(taskId: String, offset: Int): String = "deadline-$taskId-$offset"

    private fun formatDate(epochMillis: Long): String {
        val date = NSDate(timeIntervalSince1970 = epochMillis / 1_000.0)
        // Keep the notification's date compact and locale-independent for a predictable reminder.
        val formatter = platform.Foundation.NSDateFormatter().apply {
            dateFormat = "dd.MM.yyyy"
        }
        return formatter.stringFromDate(date)
    }
}
