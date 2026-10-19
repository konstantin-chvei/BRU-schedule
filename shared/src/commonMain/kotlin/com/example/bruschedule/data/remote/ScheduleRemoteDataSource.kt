package com.example.bruschedule.data.remote

/** Fetches allow-listed remote documents (schedule HTML and the public GitHub messages text file). */
interface HtmlTransport {
    suspend fun get(url: String): String
}

expect fun createPlatformHtmlTransport(): HtmlTransport
