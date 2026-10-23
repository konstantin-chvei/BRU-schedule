package com.example.bruschedule.data.remote

/** Fetches public HTTPS schedule pages and the project's public GitHub messages file. */
interface HtmlTransport {
    suspend fun get(url: String): String
}

/** Binary counterpart for public PDF/Office timetable files. */
interface BinaryResourceTransport {
    suspend fun getBytes(url: String): ByteArray
}

expect fun createPlatformHtmlTransport(): HtmlTransport
