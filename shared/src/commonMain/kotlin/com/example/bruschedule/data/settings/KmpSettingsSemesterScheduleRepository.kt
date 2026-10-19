package com.example.bruschedule.data.settings

import com.example.bruschedule.domain.model.AcademicTrack
import com.example.bruschedule.domain.model.SemesterSchedulePreferences
import com.example.bruschedule.domain.repository.SemesterSchedulePreferencesRepository
import com.russhwolf.settings.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.datetime.LocalDate

/** Saves the selected academic track and IFZO's individual session end date on each platform. */
class KmpSettingsSemesterScheduleRepository(
    private val settings: Settings,
) : SemesterSchedulePreferencesRepository {
    private val mutablePreferences = MutableStateFlow(readPreferences())
    private val preferences = mutablePreferences.asStateFlow()

    override fun observe() = preferences

    override suspend fun update(preferences: SemesterSchedulePreferences) {
        LocalDate.parse(preferences.sessionEndDateIso)
        LocalDate.parse(preferences.ifzoSessionEndDateIso)
        settings.putString(TRACK_KEY, preferences.track.name)
        settings.putString(SESSION_END_KEY, preferences.sessionEndDateIso)
        settings.putString(IFZO_SESSION_END_KEY, preferences.ifzoSessionEndDateIso)
        mutablePreferences.value = preferences
    }

    private fun readPreferences(): SemesterSchedulePreferences {
        val track = runCatching {
            AcademicTrack.valueOf(settings.getString(TRACK_KEY, AcademicTrack.BELARUSIAN.name))
        }.getOrDefault(AcademicTrack.BELARUSIAN)
        val storedEndDate = settings.getString(
            SESSION_END_KEY,
            SemesterSchedulePreferences.defaultSessionEndDate(track),
        )
        val validEndDate = runCatching {
            LocalDate.parse(storedEndDate)
            storedEndDate
        }.getOrDefault(SemesterSchedulePreferences.defaultSessionEndDate(track))
        val storedIfzoEndDate = settings.getString(
            IFZO_SESSION_END_KEY,
            SemesterSchedulePreferences.DEFAULT_IFZO_SESSION_END,
        )
        val validIfzoEndDate = runCatching {
            LocalDate.parse(storedIfzoEndDate)
            storedIfzoEndDate
        }.getOrDefault(SemesterSchedulePreferences.DEFAULT_IFZO_SESSION_END)
        return SemesterSchedulePreferences(
            track = track,
            sessionEndDateIso = validEndDate,
            ifzoSessionEndDateIso = validIfzoEndDate,
        )
    }

    private companion object {
        const val TRACK_KEY = "semester.track"
        const val SESSION_END_KEY = "semester.session_end_date"
        const val IFZO_SESSION_END_KEY = "semester.ifzo_session_end_date"
    }
}
