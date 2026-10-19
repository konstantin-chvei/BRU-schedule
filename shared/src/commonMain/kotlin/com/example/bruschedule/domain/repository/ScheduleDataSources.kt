package com.example.bruschedule.domain.repository

import com.example.bruschedule.domain.model.Lesson
import com.example.bruschedule.domain.model.ScheduleProfile
import com.example.bruschedule.domain.model.ScheduleWindow
import kotlinx.datetime.LocalDate

/** Outbound ports used by the refresh use case; data-layer adapters provide Ktor and Ksoup. */
interface ScheduleRemoteDataSource {
    suspend fun fetchHtml(profile: ScheduleProfile, window: ScheduleWindow): String
}

interface ScheduleHtmlParser {
    val formatVersion: Int
    fun parse(profile: ScheduleProfile, html: String, referenceDate: LocalDate): List<Lesson>

    /** True only for regular timetable pages with explicit alternating week-one/week-two headers. */
    fun hasAlternatingTwoWeekCycle(html: String): Boolean = false
}

/** Key-value metadata port; SQLDelight remains responsible for database schema migrations. */
interface ParserVersionRepository {
    fun parserVersion(profileId: String): Int
    fun markParserVersion(profileId: String, version: Int)
    fun databaseMarker(): Int
    fun setDatabaseMarker(version: Int)
}
