package com.example.bruschedule.data.remote

import com.example.bruschedule.data.scraper.BsuBiologyScheduleHtmlParser
import kotlinx.coroutines.CancellationException

/** Fetches public HTTPS schedule pages and the project's public GitHub messages file. */
interface HtmlTransport {
    suspend fun get(url: String): String
}

/** The Biology timetable uses a session-aware public page; prime its cookie jar before filtered GETs. */
suspend fun HtmlTransport.primeBiologySession() {
    try {
        get(BsuBiologyScheduleHtmlParser.BIOLOGY_SCHEDULE_URL)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        // A warm-up is opportunistic. The actual filtered request reports the actionable failure.
    }
}

/** Binary counterpart for public PDF/Office timetable files. */
interface BinaryResourceTransport {
    suspend fun getBytes(url: String): ByteArray
}

expect fun createPlatformHtmlTransport(): HtmlTransport
