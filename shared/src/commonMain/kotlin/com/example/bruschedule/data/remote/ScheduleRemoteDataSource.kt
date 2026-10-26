package com.example.bruschedule.data.remote

import com.example.bruschedule.data.scraper.BsuBiologyScheduleHtmlParser
import kotlinx.coroutines.CancellationException

interface HtmlTransport {
    suspend fun get(url: String): String
}

suspend fun HtmlTransport.primeBiologySession(
    url: String = BsuBiologyScheduleHtmlParser.BIOLOGY_SCHEDULE_URL,
) {
    try {
        get(url)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
    }
}

interface BinaryResourceTransport {
    suspend fun getBytes(url: String): ByteArray
}

expect fun createPlatformHtmlTransport(): HtmlTransport
