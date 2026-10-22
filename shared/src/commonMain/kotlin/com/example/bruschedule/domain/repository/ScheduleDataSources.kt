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

    /** True only for timetable pages with an explicit alternating week-one/week-two cycle. */
    fun hasAlternatingTwoWeekCycle(html: String): Boolean = false

    /** Profile-aware variant lets university adapters interpret their own week labels safely. */
    fun hasAlternatingTwoWeekCycle(profile: ScheduleProfile, html: String): Boolean =
        hasAlternatingTwoWeekCycle(html)

    /** True when a one-week template for this profile should repeat through the configured term. */
    fun hasWeeklyScheduleTemplate(profile: ScheduleProfile, html: String): Boolean = false
}

/** Key-value metadata port; SQLDelight remains responsible for database schema migrations. */
interface ParserVersionRepository {
    fun parserVersion(profileId: String): Int
    fun markParserVersion(profileId: String, version: Int)
    fun databaseMarker(): Int
    fun setDatabaseMarker(version: Int)
}
