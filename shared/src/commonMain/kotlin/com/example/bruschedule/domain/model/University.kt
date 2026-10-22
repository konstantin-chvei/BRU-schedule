package com.example.bruschedule.domain.model

/** Identifies how a university's timetable is obtained and interpreted. */
enum class UniversitySourceType {
    /** The built-in directory and timetable adapter for rasp.bru.by. */
    BRU_CATALOG,

    /** HTML timetable tables and public Google Sheets used by the first BSU adapters. */
    BSU_HTML_AND_SHEETS,

    /** A user-supplied HTTPS timetable page parsed as a regular HTML table. */
    DIRECT_HTML,
}

data class University(
    val id: String,
    val name: String,
    /** University website or schedule host entered when the institution is added. */
    val websiteUrl: String,
    val sourceType: UniversitySourceType,
    val isBuiltIn: Boolean = false,
)

object UniversityIds {
    const val BRU = "bru"
    const val BSU = "bsu"
}

object DefaultUniversities {
    val BRU = University(
        id = UniversityIds.BRU,
        name = "Белорусско-Российский университет",
        websiteUrl = "https://rasp.bru.by",
        sourceType = UniversitySourceType.BRU_CATALOG,
        isBuiltIn = true,
    )

    val BSU = University(
        id = UniversityIds.BSU,
        name = "Белорусский государственный университет",
        websiteUrl = "https://bsu.by",
        sourceType = UniversitySourceType.BSU_HTML_AND_SHEETS,
        isBuiltIn = true,
    )
}
