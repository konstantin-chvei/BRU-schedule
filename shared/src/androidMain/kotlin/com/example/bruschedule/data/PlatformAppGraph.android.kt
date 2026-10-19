package com.example.bruschedule.data

import android.content.Context
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import com.example.bruschedule.data.local.db.ScheduleDatabase

/** Android uses its app-private SQLite file; no INTERNET data is ever needed to open the app. */
fun createAndroidAppGraph(context: Context): AppGraph = createAppGraph(
    driver = AndroidSqliteDriver(
        schema = ScheduleDatabase.Schema,
        context = context.applicationContext,
        name = "bru-schedule.db",
    ),
)
