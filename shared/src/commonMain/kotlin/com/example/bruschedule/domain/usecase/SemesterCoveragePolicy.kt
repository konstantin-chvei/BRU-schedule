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

    fun coverageEndDate(preferences: SemesterSchedulePreferences): LocalDate =
        sessionEndDate(preferences).plus(DatePeriod(days = DAYS_AFTER_SESSION))

    /** A stale saved date must never make the selectable calendar end before today. */
    fun coverageEndDate(today: LocalDate, preferences: SemesterSchedulePreferences): LocalDate =
        coverageEndDate(preferences).let { configuredEnd -> if (configuredEnd < today) today else configuredEnd }
}
