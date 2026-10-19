package com.example.bruschedule.data

import android.content.Context
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import com.example.bruschedule.data.local.db.ScheduleDatabase
import com.example.bruschedule.data.remote.HtmlTransport
import com.example.bruschedule.data.remote.KtorHtmlTransport
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout

actual fun createPlatformHtmlTransport(): HtmlTransport = KtorHtmlTransport(HttpClient(CIO) {
    install(HttpTimeout) {
        requestTimeoutMillis = 15_000
        connectTimeoutMillis = 8_000
        socketTimeoutMillis = 15_000
    }
})

/** Android uses its app-private SQLite file; no INTERNET data is ever needed to open the app. */
fun createAndroidAppGraph(context: Context): AppGraph = createAppGraph(
    driver = AndroidSqliteDriver(
        schema = ScheduleDatabase.Schema,
        context = context.applicationContext,
        name = "bru-schedule.db",
    ),
)
