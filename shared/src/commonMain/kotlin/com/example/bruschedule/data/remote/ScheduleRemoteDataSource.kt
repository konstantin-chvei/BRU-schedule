package com.example.bruschedule.data.remote

/** Fetches public HTTPS schedule pages and the project's public GitHub messages file. */
interface HtmlTransport {
    suspend fun get(url: String): String
}

expect fun createPlatformHtmlTransport(): HtmlTransport
