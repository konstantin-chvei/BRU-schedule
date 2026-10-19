package com.example.bruschedule.data.remote

/** Fetches HTML from rasp.bru.by; the core use case depends on domain.repository's outbound port. */
interface HtmlTransport {
    suspend fun get(url: String): String
}

expect fun createPlatformHtmlTransport(): HtmlTransport
