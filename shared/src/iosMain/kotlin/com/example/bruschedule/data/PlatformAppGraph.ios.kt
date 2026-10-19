package com.example.bruschedule.data

import app.cash.sqldelight.driver.native.NativeSqliteDriver
import com.example.bruschedule.data.local.db.ScheduleDatabase
import com.example.bruschedule.data.remote.HtmlTransport
import com.example.bruschedule.data.remote.KtorHtmlTransport
import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.plugins.HttpTimeout

actual fun createPlatformHtmlTransport(): HtmlTransport = KtorHtmlTransport(HttpClient(Darwin) {
    install(HttpTimeout) {
        requestTimeoutMillis = 15_000
        connectTimeoutMillis = 8_000
        socketTimeoutMillis = 15_000
    }
})

/** iOS stores the same SQLDelight schema in the app's documents container. */
fun createIosAppGraph(): AppGraph = createAppGraph(
    driver = NativeSqliteDriver(ScheduleDatabase.Schema, "bru-schedule.db"),
)
