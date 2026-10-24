package com.example.bruschedule.data

import android.content.Context
import androidx.activity.ComponentActivity
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import com.example.bruschedule.data.local.db.ScheduleDatabase
import com.example.bruschedule.domain.repository.NoOpDeadlineNotificationScheduler
import com.example.bruschedule.notifications.AndroidDeadlineNotificationScheduler

/** Android uses its app-private SQLite file; no INTERNET data is ever needed to open the app. */
fun createAndroidAppGraph(context: Context): AppGraph = createAppGraph(
    driver = AndroidSqliteDriver(
        schema = ScheduleDatabase.Schema,
        context = context.applicationContext,
        name = "bru-schedule.db",
    ),
    deadlineNotificationScheduler = (context as? ComponentActivity)
        ?.let(::AndroidDeadlineNotificationScheduler)
        ?: NoOpDeadlineNotificationScheduler(),
)
