package com.example.bruschedule.data

import app.cash.sqldelight.driver.native.NativeSqliteDriver
import com.example.bruschedule.data.local.db.ScheduleDatabase

/** iOS stores the same SQLDelight schema in the app's documents container. */
fun createIosAppGraph(): AppGraph = createAppGraph(
    driver = NativeSqliteDriver(ScheduleDatabase.Schema, "bru-schedule.db"),
)
