package com.example.bruschedule.data.remote

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
