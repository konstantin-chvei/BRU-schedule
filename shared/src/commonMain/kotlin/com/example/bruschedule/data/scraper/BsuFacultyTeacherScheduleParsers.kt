package com.example.bruschedule.data.scraper

import com.example.bruschedule.domain.model.Lesson
import com.example.bruschedule.domain.model.ScheduleProfile
import com.example.bruschedule.domain.model.ScheduleProfileType
import com.fleeksoft.ksoup.Ksoup
import kotlinx.datetime.LocalDate

/** Faculty adapters differ at the source boundary while reusing the common KMP lesson models. */
internal interface BsuFacultyTeacherScheduleParser {
    fun discoverTeacherNames(
        html: String,
        genericParser: GenericHtmlScheduleParser,
    ): List<String> = genericParser.discoverTeacherNames(html)

    fun parse(
        profile: ScheduleProfile,
        html: String,
        referenceDate: LocalDate,
        scheduleParser: BsuScheduleHtmlParser,
        genericParser: GenericHtmlScheduleParser,
    ): List<Lesson>
}

/** Adapter for faculty Google Sheets which publish one matrix column per group/subgroup. */
internal open class BsuGoogleSheetsTeacherScheduleParser : BsuFacultyTeacherScheduleParser {
    override fun parse(
        profile: ScheduleProfile,
        html: String,
        referenceDate: LocalDate,
        scheduleParser: BsuScheduleHtmlParser,
        genericParser: GenericHtmlScheduleParser,
    ): List<Lesson> = parseFacultyTeacherSchedule(
        profile = profile,
        html = html,
        referenceDate = referenceDate,
        scheduleParser = scheduleParser,
        genericParser = genericParser,
        preferGeneric = false,
    )
}

/** Economy-faculty adapter for odd/even Google Sheets with adjacent subject and room columns. */
internal class BsuEconomyFacultyTeacherScheduleParser : BsuGoogleSheetsTeacherScheduleParser()

/** FPMI adapter for large Google Sheets whose course groups are split across subgroup columns. */
internal class BsuFpmiFacultyTeacherScheduleParser : BsuGoogleSheetsTeacherScheduleParser()

/** RCT publishes direct Google Sheets links grouped by course and magistracy semester. */
internal class BsuRctFacultyTeacherScheduleParser : BsuGoogleSheetsTeacherScheduleParser()

/** MMF publishes a day/time/subject-teacher HTML table on each group's own page. */
internal class BsuMmfFacultyTeacherScheduleParser : BsuFacultyTeacherScheduleParser {
    override fun parse(
        profile: ScheduleProfile,
        html: String,
        referenceDate: LocalDate,
        scheduleParser: BsuScheduleHtmlParser,
        genericParser: GenericHtmlScheduleParser,
    ): List<Lesson> = parseFacultyTeacherSchedule(
        profile = profile,
        html = html,
        referenceDate = referenceDate,
        scheduleParser = scheduleParser,
        genericParser = genericParser,
        preferGeneric = true,
    )
}

/** Shared KMP document-table strategy used by faculty-specific downloadable schedule adapters. */
internal open class BsuDocumentFacultyTeacherScheduleParser : BsuFacultyTeacherScheduleParser {
    override fun parse(
        profile: ScheduleProfile,
        html: String,
        referenceDate: LocalDate,
        scheduleParser: BsuScheduleHtmlParser,
        genericParser: GenericHtmlScheduleParser,
    ): List<Lesson> = parseFacultyTeacherSchedule(
        profile = profile,
        html = html,
        referenceDate = referenceDate,
        scheduleParser = scheduleParser,
        genericParser = genericParser,
        preferGeneric = true,
    )
}

/** Geography schedules are linked through the faculty's Dropbox/document directory. */
internal class BsuGeographyFacultyTeacherScheduleParser : BsuDocumentFacultyTeacherScheduleParser()

/** FIR publishes per-specialty timetable PDFs. */
internal class BsuFirFacultyTeacherScheduleParser : BsuDocumentFacultyTeacherScheduleParser()

/** FSC publishes weekly PDF tables with weekday and subgroup columns. */
internal class BsuFscFacultyTeacherScheduleParser : BsuDocumentFacultyTeacherScheduleParser()

/** Business Institute schedules are served from its own faculty calendar pages. */
internal class BsuBusinessInstituteTeacherScheduleParser : BsuDocumentFacultyTeacherScheduleParser()

/** History-faculty course sheets are parsed through the shared document-table strategy. */
internal class BsuHistoryFacultyTeacherScheduleParser : BsuDocumentFacultyTeacherScheduleParser()

/** Law-faculty weekly schedules are published as multi-sheet legacy XLS files. */
internal class BsuLawFacultyTeacherScheduleParser : BsuDocumentFacultyTeacherScheduleParser()

/** Chemistry-faculty course schedules are linked as downloadable office documents. */
internal class BsuChemistryFacultyTeacherScheduleParser : BsuDocumentFacultyTeacherScheduleParser()

/** Physics-faculty schedules link separate files for each course and speciality. */
internal class BsuPhysicsFacultyTeacherScheduleParser : BsuDocumentFacultyTeacherScheduleParser()

/** Journalism schedules are published as course/speciality PDF and office files. */
internal class BsuJournalismFacultyTeacherScheduleParser : BsuDocumentFacultyTeacherScheduleParser()

/** Philology schedules are published in separate PDF documents by speciality and year. */
internal class BsuPhilologyFacultyTeacherScheduleParser : BsuDocumentFacultyTeacherScheduleParser()

/** FFSN course schedules use the common KMP document reader behind a named faculty adapter. */
internal class BsuFfsnFacultyTeacherScheduleParser : BsuDocumentFacultyTeacherScheduleParser()

/** Biology's live route uses BsuBiologyScheduleHtmlParser; this registration is a legacy fallback. */
internal class BsuBiologyFacultyTeacherScheduleParser : BsuDocumentFacultyTeacherScheduleParser()

/** Safe fallback for saved profiles whose faculty is unknown or no longer publishes a listed source. */
internal class BsuGenericFacultyTeacherScheduleParser : BsuFacultyTeacherScheduleParser {
    override fun parse(
        profile: ScheduleProfile,
        html: String,
        referenceDate: LocalDate,
        scheduleParser: BsuScheduleHtmlParser,
        genericParser: GenericHtmlScheduleParser,
    ): List<Lesson> = parseFacultyTeacherSchedule(
        profile = profile,
        html = html,
        referenceDate = referenceDate,
        scheduleParser = scheduleParser,
        genericParser = genericParser,
        preferGeneric = false,
    )
}

internal object BsuFacultyTeacherScheduleParserRegistry {
    private val genericParser = BsuGenericFacultyTeacherScheduleParser()
    private val facultyParsers = mapOf(
        "Экономический факультет" to BsuEconomyFacultyTeacherScheduleParser(),
        "ФПМИ" to BsuFpmiFacultyTeacherScheduleParser(),
        "Механико-математический факультет" to BsuMmfFacultyTeacherScheduleParser(),
        "Географический факультет" to BsuGeographyFacultyTeacherScheduleParser(),
        "ФМО" to BsuFirFacultyTeacherScheduleParser(),
        "ФСК" to BsuFscFacultyTeacherScheduleParser(),
        "Биологический факультет" to BsuBiologyFacultyTeacherScheduleParser(),
        "Институт бизнеса" to BsuBusinessInstituteTeacherScheduleParser(),
        "Исторический факультет" to BsuHistoryFacultyTeacherScheduleParser(),
        "Юридический факультет" to BsuLawFacultyTeacherScheduleParser(),
        "Химический факультет" to BsuChemistryFacultyTeacherScheduleParser(),
        "Физический факультет" to BsuPhysicsFacultyTeacherScheduleParser(),
        "Факультет журналистики" to BsuJournalismFacultyTeacherScheduleParser(),
        "Филологический факультет" to BsuPhilologyFacultyTeacherScheduleParser(),
        "Факультет философии и социальных наук" to BsuFfsnFacultyTeacherScheduleParser(),
        "Факультет радиофизики и компьютерных технологий" to BsuRctFacultyTeacherScheduleParser(),
    )

    val registeredFacultyNames: Set<String> get() = facultyParsers.keys

    fun parserFor(facultyName: String?): BsuFacultyTeacherScheduleParser? {
        val name = facultyName?.trim()?.takeIf(String::isNotBlank) ?: return null
        return facultyParsers[name] ?: genericParser
    }
}

private fun parseFacultyTeacherSchedule(
    profile: ScheduleProfile,
    html: String,
    referenceDate: LocalDate,
    scheduleParser: BsuScheduleHtmlParser,
    genericParser: GenericHtmlScheduleParser,
    preferGeneric: Boolean,
): List<Lesson> {
    val rawSourceKey = profile.sourceKey?.takeIf(String::isNotBlank)
    val teacherName = BsuTeacherProfileSourceKey.teacherName(rawSourceKey)
        ?: rawSourceKey?.takeIf { it !in BsuFacultyTeacherScheduleParserRegistry.registeredFacultyNames }
        ?: profile.name

    fun matching(lessons: List<Lesson>): List<Lesson> = lessons
        .filter { BsuTeacherNameMatcher.matches(it.teacherName, teacherName) }
        .distinctBy(Lesson::sourceKey)
        .sortedWith(compareBy({ it.lessonDate }, { it.startTime }, { it.slotNumber }, { it.id }))

    fun genericLessons(): List<Lesson> = runCatching {
        genericParser.parse(profile, html, referenceDate)
    }.getOrNull()?.let(::matching).orEmpty()

    fun matrixLessons(): List<Lesson> = Ksoup.parse(html).select("table").toList().flatMapIndexed { tableIndex, table ->
        val tableHtml = table.outerHtml()
        scheduleParser.discoverTableGroupLabels(tableHtml).flatMap { group ->
            val groupProfile = profile.copy(
                type = ScheduleProfileType.GROUP,
                name = group,
                sourceKey = group,
            )
            runCatching { scheduleParser.parse(groupProfile, tableHtml, referenceDate) }
                .getOrNull()
                .orEmpty()
                .map { lesson ->
                    val scope = "|faculty-table:$tableIndex|group:${BsuTeacherNameMatcher.normalize(group)}"
                    lesson.copy(id = "web:${lesson.sourceKey}$scope", sourceKey = lesson.sourceKey + scope)
                }
        }
    }.let(::matching)

    fun documentRowLessons(): List<Lesson> = runCatching {
        scheduleParser.parseDayFirstDocumentTables(profile, html, referenceDate)
    }.getOrNull()?.let(::matching).orEmpty()

    fun numberedLessons(): List<Lesson> = runCatching {
        scheduleParser.parseAllNumberedGroupBlocks(profile, html, referenceDate)
    }.getOrNull()?.let(::matching).orEmpty()

    val orderedAttempts = if (preferGeneric) {
        listOf(::genericLessons, ::documentRowLessons, ::matrixLessons, ::numberedLessons)
    } else {
        listOf(::matrixLessons, ::numberedLessons, ::documentRowLessons, ::genericLessons)
    }
    orderedAttempts.forEach { attempt ->
        attempt().takeIf { lessons -> lessons.isNotEmpty() }?.let { return it }
    }
    error("В расписании не найдены занятия преподавателя «$teacherName». Проверьте источник факультета")
}
