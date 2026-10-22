package com.example.bruschedule.domain.usecase

import com.example.bruschedule.domain.model.SemesterSchedulePreferences
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus

/** Keeps the calendar tied to the session end instead of an arbitrary number of weeks. */
object SemesterCoveragePolicy {
    const val DAYS_AFTER_SESSION = 7

    fun coverageStartDate(today: LocalDate): LocalDate = today

    fun sessionEndDate(preferences: SemesterSchedulePreferences): LocalDate =
        LocalDate.parse(preferences.sessionEndDateIso)

    fun sessionEndDate(preferences: SemesterSchedulePreferences, profileId: String?): LocalDate {
        val profileEndDate = profileId
            ?.let(preferences.profileSessionEndDatesIso::get)
            ?.let { value -> runCatching { LocalDate.parse(value) }.getOrNull() }
        return profileEndDate ?: sessionEndDate(preferences)
    }

    fun coverageEndDate(preferences: SemesterSchedulePreferences): LocalDate =
        sessionEndDate(preferences).plus(DatePeriod(days = DAYS_AFTER_SESSION))

    fun coverageEndDate(preferences: SemesterSchedulePreferences, profileId: String?): LocalDate =
        sessionEndDate(preferences, profileId).plus(DatePeriod(days = DAYS_AFTER_SESSION))

    /** A stale saved date must never make the selectable calendar end before today. */
    fun coverageEndDate(today: LocalDate, preferences: SemesterSchedulePreferences): LocalDate =
        coverageEndDate(preferences).let { configuredEnd -> if (configuredEnd < today) today else configuredEnd }

    fun coverageEndDate(
        today: LocalDate,
        preferences: SemesterSchedulePreferences,
        profileId: String?,
    ): LocalDate = coverageEndDate(preferences, profileId)
        .let { configuredEnd -> if (configuredEnd < today) today else configuredEnd }
}
