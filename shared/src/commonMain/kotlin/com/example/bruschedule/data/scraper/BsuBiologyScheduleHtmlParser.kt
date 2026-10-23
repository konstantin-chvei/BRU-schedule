package com.example.bruschedule.data.scraper

import com.example.bruschedule.domain.model.Lesson
import com.example.bruschedule.domain.model.LessonType
import com.example.bruschedule.domain.model.LessonTypePalette
import com.example.bruschedule.domain.model.ScheduleProfile
import com.example.bruschedule.domain.repository.ScheduleHtmlParser
import com.fleeksoft.ksoup.Ksoup
import com.fleeksoft.ksoup.nodes.Element
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.minus
import kotlinx.datetime.plus

/** Adapter for the Bio faculty's weekly, multi-group matrix at bio.bsu.by/schedule/. */
class BsuBiologyScheduleHtmlParser : ScheduleHtmlParser {
    override val formatVersion: Int = 3

    override fun parse(profile: ScheduleProfile, html: String, referenceDate: LocalDate): List<Lesson> {
        require(isBiologyProfile(profile)) { "Профиль не относится к расписанию Биологического факультета БГУ" }
        val groupId = profile.sourceKey
            ?.takeIf { it.startsWith(BIO_GROUP_SOURCE_KEY_PREFIX) }
            ?.removePrefix(BIO_GROUP_SOURCE_KEY_PREFIX)
            ?.takeIf(String::isNotBlank)
            ?: error("В профиле Биофака не указан идентификатор учебной группы")
        val pages = splitWeeklyPages(html, referenceDate)
        val lessons = pages.flatMap { (weekDate, pageHtml) ->
            parseWeek(profile, groupId, weekDate, pageHtml)
        }.distinctBy(Lesson::sourceKey)
            .sortedWith(compareBy({ it.lessonDate }, { it.startTime }, { it.slotNumber }, { it.id }))
        require(lessons.isNotEmpty()) {
            "В расписании Биофака не найдены пары выбранной группы. Обновите список групп и попробуйте снова"
        }
        return lessons
    }

    override fun hasAlternatingTwoWeekCycle(profile: ScheduleProfile, html: String): Boolean = false

    /** A single saved week is a repeatable template; a fetched week envelope already has exact dates. */
    override fun hasWeeklyScheduleTemplate(profile: ScheduleProfile, html: String): Boolean =
        isBiologyProfile(profile) && !hasWeeklyEnvelope(html)

    fun discoverGroups(html: String): List<BsuBiologyGroup> {
        val document = Ksoup.parse(html)
        val headers = document.select("#schedule-header th.group-header").toList()
        if (headers.isEmpty()) return emptyList()
        val cellGroupIds = document.select("#schedule-body td.cell[data-group-id]")
            .map { it.attr("data-group-id").trim() }
            .filter(String::isNotBlank)
            .distinct()
        val optionGroupIds = document.select("#schedule-group-filter-list input[type=checkbox][value]")
            .map { it.attr("value").trim() }
            .filter(String::isNotBlank)
        val ids = when {
            cellGroupIds.size >= headers.size -> cellGroupIds
            optionGroupIds.size >= headers.size -> optionGroupIds
            else -> emptyList()
        }
        return headers.mapIndexedNotNull { index, header ->
            val groupId = ids.getOrNull(index) ?: return@mapIndexedNotNull null
            val number = header.selectFirst(".group-header-number")?.text()?.trim().orEmpty()
                .ifBlank { header.text().substringBefore(' ').trim() }
            if (number.isBlank()) return@mapIndexedNotNull null
            val programme = header.selectFirst(".group-header-name")?.text()?.trim().orEmpty()
            BsuBiologyGroup(
                id = groupId,
                number = number,
                programme = programme,
                name = number,
            )
        }.distinctBy(BsuBiologyGroup::id)
    }

    fun isBiologyProfile(profile: ScheduleProfile): Boolean =
        profile.sourceUrl?.contains(BIOLOGY_HOST_PATH, ignoreCase = true) == true

    fun combineWeeklyPages(pages: List<Pair<LocalDate, String>>): String = pages
        .joinToString("\n") { (weekDate, html) -> "$WEEK_MARKER_PREFIX$weekDate-->\n$html" }

    private fun parseWeek(
        profile: ScheduleProfile,
        groupId: String,
        requestedWeek: LocalDate,
        html: String,
    ): List<Lesson> {
        val document = Ksoup.parse(html)
        val table = document.selectFirst("table.grid-table")
            ?: error("На странице Биофака не найдена таблица расписания")
        val weekMonday = weekStartFromDocument(document)
            ?: requestedWeek.minus(DatePeriod(days = requestedWeek.dayOfWeek.isoDayNumber - 1))
        val timesByDayAndSlot = buildMap {
            document.select("#schedule-body td.time-header[data-day][data-slot-id]").forEach { timeCell ->
                val day = timeCell.attr("data-day").toIntOrNull() ?: return@forEach
                val slot = timeCell.attr("data-slot-id").toIntOrNull() ?: return@forEach
                val time = TIME_RANGE.find(timeCell.text()) ?: return@forEach
                put(day to slot, normalizeTime(time.groupValues[1]) to normalizeTime(time.groupValues[2]))
            }
        }
        val groupCells = table.select("tbody#schedule-body td.cell[data-group-id]")
            .filter { it.attr("data-group-id").trim() == groupId }
        return groupCells.flatMapIndexed { ordinal, cell ->
            if ("empty-cell" in cell.attr("class").split(WHITESPACE)) return@flatMapIndexed emptyList()
            val dayIndex = cell.attr("data-day").toIntOrNull() ?: return@flatMapIndexed emptyList()
            val slot = cell.attr("data-slot").toIntOrNull() ?: return@flatMapIndexed emptyList()
            if (dayIndex !in 0..6 || slot <= 0) return@flatMapIndexed emptyList()
            val times = timesByDayAndSlot[dayIndex to slot]
                ?: timesByDayAndSlot[0 to slot]
                ?: return@flatMapIndexed emptyList()
            val date = runCatching { weekMonday.plus(DatePeriod(days = dayIndex)) }.getOrNull()
                ?: return@flatMapIndexed emptyList()
            val card = cell.selectFirst(".lesson-card") ?: return@flatMapIndexed emptyList()
            val title = card.selectFirst(".lesson-title")?.text()?.trim().orEmpty()
            if (title.isBlank()) return@flatMapIndexed emptyList()

            val subgroups = card.select(".subgroup-mini-slot").toList()
            val specializations = card.select(".spec-mini-slot").toList()
            val teachers: List<String>
            val rooms: List<String>
            val subject: String
            if (subgroups.isNotEmpty()) {
                teachers = subgroups.mapNotNull { subgroup ->
                    val teacher = subgroup.selectFirst(".lesson-teacher")?.text()?.trim().orEmpty()
                    teacher.takeIf(String::isNotBlank)?.let { "${subgroup.selectFirst(".subgroup-mini-slot-title")?.text()?.trim().orEmpty()}: $it" }
                }.distinct()
                rooms = subgroups.mapNotNull { subgroup ->
                    val room = subgroup.selectFirst(".lesson-room")?.text()?.trim().orEmpty()
                    room.takeIf(String::isNotBlank)?.let { "${subgroup.selectFirst(".subgroup-mini-slot-title")?.text()?.trim().orEmpty()}: $it" }
                }.distinct()
                subject = title
            } else if (specializations.isNotEmpty()) {
                val specLabels = specializations.map { specialization ->
                    specialization.text().trim().replace(WHITESPACE, " ")
                }.filter(String::isNotBlank).distinct()
                teachers = card.select(".lesson-teacher, .teacher-link")
                    .map { it.text().trim() }
                    .filter(String::isNotBlank)
                    .distinct()
                val specializationRooms = specLabels.flatMap { label ->
                    label.split('·').map(String::trim)
                        .filter { "ауд" in it.lowercase() || "каб" in it.lowercase() }
                }
                val directRooms = card.select(".lesson-room, .lesson-address")
                    .map { it.text().trim() }
                    .filter(String::isNotBlank)
                rooms = (specializationRooms + directRooms).distinct()
                subject = if (specLabels.isEmpty()) title else "$title · ${specLabels.joinToString("; ")}"
            } else {
                teachers = card.select(".lesson-teacher").map { it.text().trim() }
                    .filter(String::isNotBlank).distinct()
                rooms = card.select(".lesson-room, .lesson-address").map { it.text().trim() }
                    .filter(String::isNotBlank).distinct()
                subject = title
            }
            val type = lessonType(card, title)
            val comment = card.selectFirst(".lesson-comment")?.text()?.trim().orEmpty()
            val subjectWithComment = if (comment.isBlank()) subject else "$subject · $comment"
            val externalLessonIds = card.select("[data-lesson-id]")
                .map { it.attr("data-lesson-id").trim() }
                .filter(String::isNotBlank)
                .distinct()
                .joinToString("-")
            val identity = externalLessonIds.ifBlank { ordinal.toString() }
            val sourceKey = "${profile.id}|$date|$slot|${times.first}-${times.second}|bio:$groupId:$identity"
            listOf(
                Lesson(
                    id = "web:$sourceKey",
                    profileId = profile.id,
                    subjectName = subjectWithComment,
                    teacherName = teachers.joinToString(" · "),
                    classroom = rooms.joinToString(" · "),
                    startTime = times.first,
                    endTime = times.second,
                    type = type,
                    colorHex = LessonTypePalette.accent(type),
                    isCustom = false,
                    version = 1,
                    lessonDate = date.toString(),
                    dayOfWeek = date.dayOfWeek.isoDayNumber,
                    slotNumber = slot,
                    sourceKey = sourceKey,
                ),
            )
        }
    }

    private fun weekStartFromDocument(document: com.fleeksoft.ksoup.nodes.Document): LocalDate? {
        val text = document.selectFirst("#week-info")?.text().orEmpty()
        val match = EXPLICIT_DATE.find(text) ?: return null
        return parseDate(match.groupValues[1], match.groupValues[2], match.groupValues.getOrNull(3))
    }

    private fun splitWeeklyPages(html: String, referenceDate: LocalDate): List<Pair<LocalDate, String>> {
        val matches = WEEK_MARKER.findAll(html).toList()
        if (matches.isEmpty()) return listOf(referenceDate to html)
        return matches.mapIndexedNotNull { index, match ->
            val start = match.range.last + 1
            val end = matches.getOrNull(index + 1)?.range?.first ?: html.length
            val page = html.substring(start, end).trim()
            val weekDate = runCatching { LocalDate.parse(match.groupValues[1]) }.getOrNull() ?: return@mapIndexedNotNull null
            page.takeIf(String::isNotBlank)?.let { weekDate to it }
        }
    }

    private fun lessonType(card: Element, title: String): LessonType {
        val content = (card.attr("class") + " " + title + " " + card.text())
            .lowercase().replace('ё', 'е')
        return when {
            "lecture" in content || "лекц" in content || Regex("\\bлк\\b").containsMatchIn(content) -> LessonType.LECTURE
            "laboratory" in content || "lab" in content || "лаб" in content || Regex("\\bлр\\b").containsMatchIn(content) -> LessonType.LABORATORY
            "practice" in content || "pract" in content || "практ" in content || Regex("\\bпз\\b").containsMatchIn(content) -> LessonType.PRACTICE
            else -> LessonType.UNKNOWN
        }
    }

    private fun normalizeTime(value: String): String {
        val parts = value.replace('.', ':').split(':', limit = 2)
        return "${parts[0].padStart(2, '0')}:${parts.getOrElse(1) { "00" }}"
    }

    private fun parseDate(day: String, month: String, year: String?): LocalDate? {
        val reference = year?.toIntOrNull() ?: return null
        return runCatching { LocalDate(reference, month.toInt(), day.toInt()) }.getOrNull()
    }

    private fun hasWeeklyEnvelope(html: String): Boolean = WEEK_MARKER.containsMatchIn(html)

    companion object {
        const val BIOLOGY_HOST_PATH = "bio.bsu.by/schedule"
        const val BIO_GROUP_SOURCE_KEY_PREFIX = "bio-group-id:"
        private const val WEEK_MARKER_PREFIX = "<!--BSU-BIO-WEEK:"
        private val WEEK_MARKER = Regex("<!--BSU-BIO-WEEK:(\\d{4}-\\d{2}-\\d{2})-->")
        private val TIME_RANGE = Regex("(\\d{1,2}[:.]\\d{2})\\s*[-–—−]\\s*(\\d{1,2}[:.]\\d{2})")
        private val EXPLICIT_DATE = Regex("(\\d{1,2})\\.(\\d{1,2})\\.(\\d{4})")
        private val WHITESPACE = Regex("\\s+")
    }
}

data class BsuBiologyGroup(
    val id: String,
    val number: String,
    val programme: String,
    val name: String,
)
