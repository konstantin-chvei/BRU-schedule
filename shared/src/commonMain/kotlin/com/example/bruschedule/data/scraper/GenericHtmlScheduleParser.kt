package com.example.bruschedule.data.scraper

import com.example.bruschedule.domain.model.Lesson
import com.example.bruschedule.domain.model.LessonType
import com.example.bruschedule.domain.model.LessonTypePalette
import com.example.bruschedule.domain.model.ScheduleProfile
import com.example.bruschedule.domain.model.UniversityIds
import com.example.bruschedule.domain.repository.ScheduleHtmlParser
import com.fleeksoft.ksoup.Ksoup
import com.fleeksoft.ksoup.nodes.Element
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.minus
import kotlinx.datetime.plus

/**
 * Best-effort parser for public university pages that publish a timetable as an HTML table.
 * This intentionally does not claim to support JavaScript-only calendars or every site-specific
 * layout; those require a dedicated adapter.
 */
class GenericHtmlScheduleParser : ScheduleHtmlParser {
    override val formatVersion: Int = 1

    override fun parse(
        profile: ScheduleProfile,
        html: String,
        referenceDate: LocalDate,
    ): List<Lesson> {
        require(html.isNotBlank()) { "Страница расписания пуста" }
        val document = Ksoup.parse(html)
        val pageText = document.text().lowercase().replace('ё', 'е')
        val hasTwoWeekLabels = WEEK_ONE.containsMatchIn(pageText) && WEEK_TWO.containsMatchIn(pageText)
        val datedCells = document.select("table th, table td")
            .mapNotNull { EXPLICIT_DATE.find(it.text())?.value }
            .distinct()
        require(!hasTwoWeekLabels || datedCells.size >= 7) {
            "Для двухнедельной таблицы нужны даты в заголовках; другой формат требует отдельной настройки"
        }
        val result = mutableListOf<Lesson>()
        val monday = referenceDate.minus(DatePeriod(days = referenceDate.dayOfWeek.isoDayNumber - 1))

        document.select("table").forEachIndexed { tableIndex, table ->
            val rows = table.select("tr").toList()
            if (rows.size < 2) return@forEachIndexed
            val headerIndex = rows.indexOfFirst { row ->
                val headers = row.select("th, td").map { normalize(it.text()) }
                headers.count { weekdayNumber(it) != null || explicitDate(it, referenceDate) != null } >= 2
            }
            if (headerIndex >= 0) {
                parseDayColumns(
                    profile = profile,
                    rows = rows,
                    headerIndex = headerIndex,
                    tableIndex = tableIndex,
                    monday = monday,
                    referenceDate = referenceDate,
                    target = result,
                )
            }
            parseDayRows(
                profile = profile,
                rows = rows,
                tableIndex = tableIndex,
                monday = monday,
                referenceDate = referenceDate,
                target = result,
            )
        }

        val parsed = result.distinctBy(Lesson::sourceKey)
            .sortedWith(compareBy<Lesson>({ it.lessonDate }, { it.startTime }, { it.slotNumber }, { it.id }))
        require(parsed.isNotEmpty()) {
            "Не удалось распознать таблицу расписания. Нужна прямая ссылка на HTML-страницу с парами"
        }
        return parsed
    }

    override fun hasAlternatingTwoWeekCycle(html: String): Boolean {
        val document = Ksoup.parse(html)
        val pageText = document.text().lowercase().replace('ё', 'е')
        val datedHeaders = document.select("table th, table td")
            .mapNotNull { EXPLICIT_DATE.find(it.text())?.value }
            .distinct()
        return WEEK_ONE.containsMatchIn(pageText) && WEEK_TWO.containsMatchIn(pageText) && datedHeaders.size >= 7
    }

    override fun hasWeeklyScheduleTemplate(profile: ScheduleProfile, html: String): Boolean {
        if (profile.universityId == UniversityIds.BRU) return false
        val document = Ksoup.parse(html)
        val pageText = document.text().lowercase().replace('ё', 'е')
        if (WEEK_ONE.containsMatchIn(pageText) || WEEK_TWO.containsMatchIn(pageText)) return false
        return document.select("table").any { table ->
            val cells = table.select("th, td")
            val weekdays = cells.mapNotNull { weekdayNumber(it.text()) }.distinct()
            val dates = cells.mapNotNull { EXPLICIT_DATE.find(it.text())?.value }.distinct()
            weekdays.size in 2..7 && dates.size <= 7 && cells.any { TIME_RANGE.containsMatchIn(it.text()) }
        }
    }

    private fun parseDayColumns(
        profile: ScheduleProfile,
        rows: List<Element>,
        headerIndex: Int,
        tableIndex: Int,
        monday: LocalDate,
        referenceDate: LocalDate,
        target: MutableList<Lesson>,
    ) {
        val headerCells = rows[headerIndex].select("th, td").toList()
        val dayDates = headerCells.mapIndexedNotNull { index, cell ->
            dayDate(cell.text(), monday, referenceDate)?.let { index to it }
        }.toMap()
        if (dayDates.size < 2) return

        rows.drop(headerIndex + 1).forEachIndexed { rowOffset, row ->
            val cells = row.select("th, td").toList()
            val timeIndex = cells.indexOfFirst { TIME_RANGE.containsMatchIn(it.text()) }
            if (timeIndex < 0) return@forEachIndexed
            val timeText = cells[timeIndex].text()
            val time = TIME_RANGE.find(timeText) ?: return@forEachIndexed
            val start = normalizeTime(time.groupValues[1])
            val end = normalizeTime(time.groupValues[2])
            val slot = PAIR_NUMBER.find(timeText)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: rowOffset + 1

            dayDates.forEach { (cellIndex, date) ->
                if (cellIndex == timeIndex) return@forEach
                val cell = cells.getOrNull(cellIndex) ?: return@forEach
                appendLesson(
                    profile = profile,
                    date = date,
                    timeText = timeText,
                    content = cell,
                    start = start,
                    end = end,
                    slot = slot,
                    ordinal = tableIndex * 1000 + cellIndex,
                    target = target,
                )
            }
        }
    }

    /** Supports the common vertical layout: one row per lesson with day, time and subject columns. */
    private fun parseDayRows(
        profile: ScheduleProfile,
        rows: List<Element>,
        tableIndex: Int,
        monday: LocalDate,
        referenceDate: LocalDate,
        target: MutableList<Lesson>,
    ) {
        val headerIndex = rows.indexOfFirst { row ->
            val labels = row.select("th, td").map { normalize(it.text()) }
            labels.any(::isTimeHeader) && labels.any(::isDayHeader)
        }
        if (headerIndex < 0) return

        val headers = rows[headerIndex].select("th, td").map { normalize(it.text()) }
        val timeColumn = headers.indexOfFirst(::isTimeHeader)
        val dayColumn = headers.indexOfFirst(::isDayHeader)
        val subjectColumns = headers.indices.filter { it != timeColumn && it != dayColumn }
        if (timeColumn < 0 || dayColumn < 0 || subjectColumns.isEmpty()) return

        rows.drop(headerIndex + 1).forEachIndexed { rowOffset, row ->
            val cells = row.select("th, td").toList()
            val timeCell = cells.getOrNull(timeColumn) ?: return@forEachIndexed
            val timeText = timeCell.text()
            val time = TIME_RANGE.find(timeText) ?: return@forEachIndexed
            val dayCell = cells.getOrNull(dayColumn) ?: return@forEachIndexed
            val date = dayDate(dayCell.text(), monday, referenceDate) ?: return@forEachIndexed
            val start = normalizeTime(time.groupValues[1])
            val end = normalizeTime(time.groupValues[2])
            val slot = PAIR_NUMBER.find(timeText)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: rowOffset + 1
            val content = subjectColumns.mapNotNull(cells::getOrNull)
                .firstOrNull { it.text().isNotBlank() } ?: return@forEachIndexed
            appendLesson(
                profile = profile,
                date = date,
                timeText = timeText,
                content = content,
                start = start,
                end = end,
                slot = slot,
                ordinal = tableIndex * 1000 + rowOffset,
                target = target,
            )
        }
    }

    private fun appendLesson(
        profile: ScheduleProfile,
        date: LocalDate,
        timeText: String,
        content: Element,
        start: String,
        end: String,
        slot: Int,
        ordinal: Int,
        target: MutableList<Lesson>,
    ) {
        val lines = contentLines(content)
        if (lines.isEmpty()) return
        val subjectLine = lines.firstOrNull { line ->
            !TIME_RANGE.containsMatchIn(line) &&
                !EXPLICIT_DATE.containsMatchIn(line) &&
                weekdayNumber(line) == null &&
                !TEACHER_LINE.containsMatchIn(line) &&
                !ROOM_LINE.containsMatchIn(line)
        } ?: return
        val subject = subjectLine
            .replaceFirst(SUBJECT_PREFIX, "")
            .trim(' ', ':', '–', '-')
            .takeIf(String::isNotBlank)
            ?: return
        val teacher = lines.firstNotNullOfOrNull { line ->
            TEACHER_LINE.find(line)?.groupValues?.getOrNull(1)?.trim()?.takeIf(String::isNotBlank)
        }.orEmpty()
        val room = lines.firstNotNullOfOrNull { line ->
            ROOM_LINE.find(line)?.groupValues?.getOrNull(1)?.trim()?.takeIf(String::isNotBlank)
        }.orEmpty()
        val typeText = (lines + timeText + content.attr("class")).joinToString(" ").lowercase()
        val type = when {
            "лекц" in typeText || "lecture" in typeText -> LessonType.LECTURE
            "лаб" in typeText || "laboratory" in typeText -> LessonType.LABORATORY
            "практ" in typeText || "practice" in typeText -> LessonType.PRACTICE
            else -> LessonType.UNKNOWN
        }
        val sourceKey = "${profile.id}|$date|$slot|$start-$end|$ordinal"
        target += Lesson(
            id = "web:$sourceKey",
            profileId = profile.id,
            subjectName = subject,
            teacherName = teacher,
            classroom = room,
            startTime = start,
            endTime = end,
            type = type,
            colorHex = LessonTypePalette.accent(type),
            isCustom = false,
            version = 1,
            lessonDate = date.toString(),
            dayOfWeek = date.dayOfWeek.isoDayNumber,
            slotNumber = slot,
            sourceKey = sourceKey,
        )
    }

    private fun dayDate(
        text: String,
        monday: LocalDate,
        referenceDate: LocalDate,
    ): LocalDate? {
        explicitDate(text, referenceDate)?.let { return it }
        val weekday = weekdayNumber(text) ?: return null
        return runCatching { monday.plus(DatePeriod(days = weekday - 1)) }.getOrNull()
    }

    private fun explicitDate(text: String, referenceDate: LocalDate): LocalDate? {
        val match = EXPLICIT_DATE.find(text) ?: return null
        val day = match.groupValues[1].toIntOrNull() ?: return null
        val month = match.groupValues[2].toIntOrNull() ?: return null
        val year = match.groupValues.getOrNull(3)?.toIntOrNull() ?: when {
            referenceDate.monthNumber == 12 && month == 1 -> referenceDate.year + 1
            referenceDate.monthNumber == 1 && month == 12 -> referenceDate.year - 1
            else -> referenceDate.year
        }
        return runCatching { LocalDate(year, month, day) }.getOrNull()
    }

    private fun contentLines(element: Element): List<String> {
        val html = element.html()
            .replace(BR_TAG, "\n")
            .replace(BLOCK_START_TAG, "\n")
            .replace(BLOCK_END_TAG, "\n")
            .replace(HTML_TAG, " ")
            .replace("&nbsp;", " ", ignoreCase = true)
            .replace("&#160;", " ", ignoreCase = true)
        val rawLines = html.split('\n')
            .map { normalize(it) }
            .filter(String::isNotBlank)
        return rawLines.ifEmpty { listOf(normalize(element.text())).filter(String::isNotBlank) }
    }

    private fun normalize(value: String): String = value
        .replace('\u00a0', ' ')
        .replace(WHITESPACE, " ")
        .trim()

    private fun isTimeHeader(text: String): Boolean {
        val normalized = normalize(text).lowercase().replace('ё', 'е')
        return normalized.contains("время") || normalized.contains("час") || normalized.contains("time")
    }

    private fun isDayHeader(text: String): Boolean {
        val normalized = normalize(text).lowercase().replace('ё', 'е')
        return normalized.contains("день") || normalized.contains("дата") ||
            normalized.contains("weekday") || weekdayNumber(normalized) != null
    }

    private fun weekdayNumber(text: String): Int? {
        val normalized = normalize(text).lowercase().replace('ё', 'е')
        return when {
            Regex("\\bпн\\b|понедельник|панядзел").containsMatchIn(normalized) -> 1
            Regex("\\bвт\\b|вторник|аўторак|ауторок").containsMatchIn(normalized) -> 2
            Regex("\\bср\\b|среда|серада").containsMatchIn(normalized) -> 3
            Regex("\\bчт\\b|четверг|чацвер").containsMatchIn(normalized) -> 4
            Regex("\\bпт\\b|пятниц|пятніца|пятница").containsMatchIn(normalized) -> 5
            Regex("\\bсб\\b|суббот|субота").containsMatchIn(normalized) -> 6
            Regex("\\bвс\\b|воскресень|нядзел").containsMatchIn(normalized) -> 7
            else -> null
        }
    }

    private fun normalizeTime(value: String): String {
        val parts = value.split(':', limit = 2)
        return "${parts[0].padStart(2, '0')}:${parts.getOrElse(1) { "00" }}"
    }

    private companion object {
        val TIME_RANGE = Regex("(\\d{1,2}:\\d{2})\\s*[-–—−]\\s*(\\d{1,2}:\\d{2})")
        val PAIR_NUMBER = Regex("(\\d+)\\s*(?:пара|занятие|pair)", RegexOption.IGNORE_CASE)
        val EXPLICIT_DATE = Regex("(\\d{1,2})\\.(\\d{1,2})(?:\\.(\\d{4}))?")
        val WEEK_ONE = Regex("недел[ья]\\s*1|week\\s*1", RegexOption.IGNORE_CASE)
        val WEEK_TWO = Regex("недел[ья]\\s*2|week\\s*2", RegexOption.IGNORE_CASE)
        val TEACHER_LINE = Regex("(?:преподаватель|преп\\.?|teacher|викладчык)\\s*[:–-]?\\s*(.+)", RegexOption.IGNORE_CASE)
        val ROOM_LINE = Regex("(?:ауд(?:итория)?\\.?|каб(?:инет)?\\.?|room|корпус)\\s*[:№–-]?\\s*(.+)", RegexOption.IGNORE_CASE)
        val SUBJECT_PREFIX = Regex("^(?:лекция|лекц\\.?|лабораторная|лаб\\.?|практика|практ\\.?)\\s*[:–-]?\\s*", RegexOption.IGNORE_CASE)
        val WHITESPACE = Regex("\\s+")
        val BR_TAG = Regex("(?i)<br\\s*/?>")
        val BLOCK_START_TAG = Regex("(?i)<(?:div|p|li)[^>]*>")
        val BLOCK_END_TAG = Regex("(?i)</(?:div|p|li)\\s*>")
        val HTML_TAG = Regex("<[^>]+>")
    }
}
