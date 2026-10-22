package com.example.bruschedule.domain.model

/** The 2026/27 winter-session track used to determine the schedule horizon. */
enum class AcademicTrack(val label: String) {
    BELARUSIAN("Белорусское отделение"),
    RUSSIAN("Российское отделение"),
    IFZO("ИФЗО"),
}

data class SemesterSchedulePreferences(
    val track: AcademicTrack = AcademicTrack.BELARUSIAN,
    /** Default last session day for the selected track, ISO-8601. */
    val sessionEndDateIso: String = DEFAULT_BELARUSIAN_SESSION_END,
    /** Keeps the personal IFZO date when the user temporarily switches to another track. */
    val ifzoSessionEndDateIso: String = DEFAULT_IFZO_SESSION_END,
    /** Optional per-schedule last session dates; faculties can end at different times. */
    val profileSessionEndDatesIso: Map<String, String> = emptyMap(),
) {
    companion object {
        const val DEFAULT_BELARUSIAN_SESSION_END = "2027-01-10"
        const val DEFAULT_RUSSIAN_SESSION_END = "2027-01-25"
        const val DEFAULT_IFZO_SESSION_END = "2027-01-30"

        fun defaultSessionEndDate(track: AcademicTrack): String = when (track) {
            AcademicTrack.BELARUSIAN -> DEFAULT_BELARUSIAN_SESSION_END
            AcademicTrack.RUSSIAN -> DEFAULT_RUSSIAN_SESSION_END
            AcademicTrack.IFZO -> DEFAULT_IFZO_SESSION_END
        }
    }
}
