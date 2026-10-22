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

/**
 * First-phase BSU adapters: ordinary timetable tables (for example, MMF) and public Google Sheets.
 * Document formats and JavaScript-only calendars remain outside this parser by design.
 */
class BsuScheduleHtmlParser(
    private val genericParser: GenericHtmlScheduleParser = GenericHtmlScheduleParser(),
) : ScheduleHtmlParser {
    override val formatVersion: Int = 1

    override fun parse(profile: ScheduleProfile, html: String, referenceDate: LocalDate): List<Lesson> {
        require(html.isNotBlank()) { "Страница расписания пуста" }
        val document = Ksoup.parse(html)
        val pageText = normalize(document.text()).lowercase().replace('ё', 'е')
        val hasDateAnchor = document.select("table th, table td").any { EXPLICIT_DATE.containsMatchIn(it.text()) }
        require(!hasSingleParityLabel(pageText) || hasDateAnchor) {
            "Для чётного или нечётного расписания нужны даты в таблице, чтобы определить повтор недели"
        }
        return if (profile.sourceUrl.isGoogleSheetsUrl()) {
            parseGoogleSheet(profile, html, referenceDate)
        } else {
            genericParser.parse(profile, html, referenceDate)
        }
    }

    fun discoverGroupLabelsFromText(text: String): List<String> = groupLabelsIn(text)

    /** Returns group labels found in matrix table headers. */
    fun discoverTableGroupLabels(html: String): List<String> {
        val document = Ksoup.parse(html)
        val labels = linkedSetOf<String>()
        document.select("table").forEach { table ->
            val rows = expandTableRows(table).take(MAX_HEADER_ROWS)
            rows.forEach { row ->
                val texts = row.map { cell -> cell?.text()?.let(::normalize).orEmpty() }
                texts.forEachIndexed { index, text ->
                    groupLabelsIn(text).forEach { labels += it }
                    val descriptor = parseGroupDescriptor(text) ?: return@forEachIndexed
                    if (descriptor.subgroup != null) return@forEachIndexed
                    texts.drop(index + 1).take(2)
                        .filter { SUBGROUP_HEADER.matches(it) }
                        .map(::normalizeSubgroup)
                        .map(::displaySubgroup)
                        .forEach { subgroup -> labels += "${descriptor.number} группа $subgroup" }
                }
            }
        }
        return labels.toList()
    }

    /** Returns group labels found in matrix headers and group links for the picker. */
    fun discoverGroupLabels(html: String): List<String> {
        val document = Ksoup.parse(html)
        val labels = linkedSetOf<String>()
        discoverTableGroupLabels(html).forEach { labels += it }
        document.select("a[href]").forEach { link ->
            groupLabelsIn(link.text()).forEach { labels += it }
        }
        return labels.toList()
    }

    override fun hasAlternatingTwoWeekCycle(html: String): Boolean {
        val document = Ksoup.parse(html)
        val pageText = normalize(document.text()).lowercase().replace('ё', 'е')
        val hasOneParity = hasSingleParityLabel(pageText)
        val timetableCells = document.select("table th, table td")
        val hasTime = timetableCells.any { TIME_RANGE.containsMatchIn(it.text()) }
        val hasDateAnchor = timetableCells.any { EXPLICIT_DATE.containsMatchIn(it.text()) }
        return (hasOneParity && hasTime && hasDateAnchor) || genericParser.hasAlternatingTwoWeekCycle(html)
    }

    override fun hasWeeklyScheduleTemplate(profile: ScheduleProfile, html: String): Boolean {
        if (!profile.sourceUrl.isGoogleSheetsUrl()) {
            return genericParser.hasWeeklyScheduleTemplate(profile, html)
        }
        val pageText = normalize(Ksoup.parse(html).text()).lowercase().replace('ё', 'е')
        if ("нечетн" in pageText || "четн" in pageText) return false
        val layout = findLayout(profile, html) ?: return false
        val timedRows = layout.rows.drop(layout.dataStart).filter { row ->
            TIME_RANGE.containsMatchIn(cellText(row, layout.timeColumn))
        }
        if (timedRows.isEmpty()) return false
        val dates = layout.rows.drop(layout.dataStart)
            .mapNotNull { row -> EXPLICIT_DATE.find(cellText(row, layout.dateColumn))?.value }
            .distinct()
        return dates.size <= 7
    }

    private fun hasSingleParityLabel(text: String): Boolean {
        val normalized = normalize(text).lowercase().replace('ё', 'е')
        val hasOddLabel = "нечетн" in normalized
        val hasEvenLabel = "четн" in normalized.replace("нечетн", "")
        return hasOddLabel.xor(hasEvenLabel)
    }

    private fun parseGoogleSheet(
        profile: ScheduleProfile,
        html: String,
        referenceDate: LocalDate,
    ): List<Lesson> {
        val layout = findLayout(profile, html)
            ?: error("Не найден столбец группы «${profile.sourceKey ?: profile.name}» в Google Sheets. Проверьте код группы и лист ссылки")
        val monday = referenceDate.minus(DatePeriod(days = referenceDate.dayOfWeek.isoDayNumber - 1))
        val headerDayText = cellText(layout.rows.getOrNull(layout.headerRow).orEmpty(), layout.dayColumn)
        val headerDateText = cellText(layout.rows.getOrNull(layout.headerRow).orEmpty(), layout.dateColumn)
        val headerWeekday = weekdayNumber(headerDayText)
        var currentDate = explicitDate("$headerDayText $headerDateText", referenceDate)
            ?: headerWeekday?.let { weekdayDate(monday, it) }
        var previousWeekday = headerWeekday

        val result = mutableListOf<Lesson>()
        val slotsByDate = mutableMapOf<String, Int>()
        var pendingDate: LocalDate? = null
        var pendingStart = ""
        var pendingEnd = ""
        var pendingSlot = 0
        var pendingRowIndex = -1
        val pendingLines = mutableListOf<String>()

        fun flushPending() {
            val date = pendingDate
            if (date != null) {
                val roomLines = pendingLines.filter { roomFromLine(it) != null }
                val content = pendingLines.filterNot { roomFromLine(it) != null }
                val onlyIndex = content.indexOfFirst { ONLY_DATE.containsMatchIn(it) }
                val recurringContent = if (onlyIndex >= 0) content.take(onlyIndex) else content
                val oneOffContent = if (onlyIndex >= 0) content.drop(onlyIndex) else emptyList()
                val excludedDate = conditionDate(recurringContent, EXCEPT_DATE, date)

                if (recurringContent.isNotEmpty()) {
                    createMatrixLesson(
                        profile = profile,
                        lessonDate = date,
                        start = pendingStart,
                        end = pendingEnd,
                        slot = pendingSlot,
                        rowIndex = pendingRowIndex,
                        contentLines = cleanConditionLines(recurringContent + roomLines),
                        excludedDate = excludedDate,
                    )?.let(result::add)
                }
                if (oneOffContent.isNotEmpty()) {
                    val oneOffDate = conditionDate(oneOffContent, ONLY_DATE, date) ?: date
                    createMatrixLesson(
                        profile = profile,
                        lessonDate = oneOffDate,
                        start = pendingStart,
                        end = pendingEnd,
                        slot = pendingSlot,
                        rowIndex = pendingRowIndex,
                        contentLines = cleanConditionLines(oneOffContent + roomLines),
                        isOneOff = true,
                    )?.let(result::add)
                }
            }
            pendingDate = null
            pendingStart = ""
            pendingEnd = ""
            pendingSlot = 0
            pendingRowIndex = -1
            pendingLines.clear()
        }

        layout.rows.drop(layout.dataStart).forEachIndexed { offset, row ->
            val rowIndex = layout.dataStart + offset
            val dayText = cellText(row, layout.dayColumn)
            val dateText = cellText(row, layout.dateColumn)
            val rowWeekday = weekdayNumber(dayText)
            val explicitRowDate = explicitDate("$dayText $dateText", referenceDate)
            when {
                explicitRowDate != null -> currentDate = explicitRowDate
                rowWeekday != null -> currentDate = weekdayDate(monday, rowWeekday)
                currentDate == null -> currentDate = explicitDate(dateText, referenceDate)
            }
            if (rowWeekday != null && previousWeekday != null && rowWeekday != previousWeekday) {
                flushPending()
            }
            if (rowWeekday != null) previousWeekday = rowWeekday

            val timeText = cellText(row, layout.timeColumn)
            val time = TIME_RANGE.find(timeText)
            if (time != null) {
                flushPending()
                val content = row.getOrNull(layout.groupColumn)
                val lines = content?.let(::contentLines).orEmpty()
                val roomLines = row.getOrNull(layout.roomColumn)
                    ?.let(::contentLines)
                    .orEmpty()
                    .map { "Ауд. $it" }
                if (currentDate != null && lines.isNotEmpty()) {
                    val date = currentDate ?: return@forEachIndexed
                    val parsedSlot = PAIR_NUMBER.find(timeText)?.groupValues?.getOrNull(1)?.toIntOrNull()
                    val slot = parsedSlot ?: ((slotsByDate[date.toString()] ?: 0) + 1)
                    slotsByDate[date.toString()] = maxOf(slotsByDate[date.toString()] ?: 0, slot)
                    pendingDate = date
                    pendingStart = normalizeTime(time.groupValues[1])
                    pendingEnd = normalizeTime(time.groupValues[2])
                    pendingSlot = slot
                    pendingRowIndex = rowIndex
                    pendingLines += lines
                    pendingLines += roomLines
                }
            } else {
                val continuation = row.getOrNull(layout.groupColumn)?.let(::contentLines).orEmpty()
                val continuationRooms = row.getOrNull(layout.roomColumn)
                    ?.let(::contentLines)
                    .orEmpty()
                    .map { "Ауд. $it" }
                if (pendingDate != null && (continuation.isNotEmpty() || continuationRooms.isNotEmpty())) {
                    pendingLines += continuation
                    pendingLines += continuationRooms
                }
            }
        }
        flushPending()

        val parsed = result.distinctBy(Lesson::sourceKey)
            .sortedWith(compareBy<Lesson>({ it.lessonDate }, { it.startTime }, { it.slotNumber }, { it.id }))
        require(parsed.isNotEmpty()) {
            "Не найдены пары группы «${profile.sourceKey ?: profile.name}» в Google Sheets"
        }
        return parsed
    }

    private fun createMatrixLesson(
        profile: ScheduleProfile,
        lessonDate: LocalDate,
        start: String,
        end: String,
        slot: Int,
        rowIndex: Int,
        contentLines: List<String>,
        isOneOff: Boolean = false,
        excludedDate: LocalDate? = null,
    ): Lesson? {
        val subjectLine = contentLines.firstOrNull { line ->
            !TIME_RANGE.containsMatchIn(line) &&
                EXPLICIT_DATE.find(line) == null &&
                weekdayNumber(line) == null &&
                teacherFromLine(line) == null &&
                roomFromLine(line) == null &&
                !TYPE_ONLY_LINE.matches(line)
        } ?: return null
        val inlineTeacher = splitInlineTeacher(subjectLine)
        val subject = (inlineTeacher?.first ?: subjectLine)
            .replaceFirst(SUBJECT_PREFIX, "")
            .trim(' ', ':', '–', '-')
            .takeIf(String::isNotBlank)
            ?: return null
        val teacher = contentLines.firstNotNullOfOrNull(::teacherFromLine)
            ?: inlineTeacher?.second
            ?: ""
        val room = contentLines.firstNotNullOfOrNull(::roomFromLine).orEmpty()
        val joined = contentLines.joinToString(" ").lowercase().replace('ё', 'е')
        val type = when {
            "лекц" in joined || "lecture" in joined || LECTURE_CODE.containsMatchIn(joined) -> LessonType.LECTURE
            "лаб" in joined || "laboratory" in joined -> LessonType.LABORATORY
            "практ" in joined || "practice" in joined || PRACTICE_CODE.containsMatchIn(joined) -> LessonType.PRACTICE
            else -> LessonType.UNKNOWN
        }
        val recurrenceMarker = when {
            isOneOff -> ONE_OFF_KEY_MARKER
            excludedDate != null -> "$EXCLUDED_DATE_KEY_MARKER$excludedDate"
            else -> ""
        }
        val sourceKey = "${profile.id}|$lessonDate|$slot|$start-$end|$rowIndex$recurrenceMarker"
        return Lesson(
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
            lessonDate = lessonDate.toString(),
            dayOfWeek = lessonDate.dayOfWeek.isoDayNumber,
            slotNumber = slot,
            sourceKey = sourceKey,
        )
    }

    private fun conditionDate(
        lines: List<String>,
        marker: Regex,
        referenceDate: LocalDate,
    ): LocalDate? {
        val dateText = lines.firstNotNullOfOrNull { line -> marker.find(line)?.groupValues?.getOrNull(1) }
            ?: return null
        return explicitDate(dateText, referenceDate)
    }

    private fun cleanConditionLines(lines: List<String>): List<String> = lines
        .map { line ->
            line.replace(DATE_CONDITION, "")
                .trim()
                .trimEnd('/')
                .trim()
        }
        .filter(String::isNotBlank)

    private fun findLayout(profile: ScheduleProfile, html: String): MatrixLayout? {
        val rawGroupKey = profile.sourceKey ?: profile.name
        val groupKey = normalizeGroupToken(rawGroupKey)
        val groupDescriptor = parseGroupDescriptor(rawGroupKey)
        if (groupKey.length < 2) return null
        val document = Ksoup.parse(html)

        return document.select("table").toList().mapNotNull { table ->
            val rows = expandTableRows(table)
            if (rows.isEmpty()) return@mapNotNull null
            val matchingGroupRows = rows.take(MAX_HEADER_ROWS).mapIndexedNotNull { rowIndex, row ->
                val matches = findGroupCells(groupKey, groupDescriptor, row)
                if (matches.isEmpty()) null else rowIndex to matches
            }
            val (groupRow, matches) = matchingGroupRows.firstOrNull() ?: return@mapNotNull null
            val exactMatches = matches.filter { normalizeGroupToken(it.text) == groupKey }
            val selectedMatches = if (exactMatches.isNotEmpty()) exactMatches else matches
            val distinctMatches = selectedMatches.distinctBy { it.columnIndex }
            if (distinctMatches.size != 1) return@mapNotNull null
            val groupColumn = distinctMatches.single().columnIndex

            val headerRow = rows.take(MAX_HEADER_ROWS).indexOfFirst { row ->
                row.any { cell -> cell?.text()?.let(::isTimeHeader) == true } &&
                    row.any { cell -> cell?.text()?.let(::isDayHeader) == true }
            }
            if (headerRow < 0) return@mapNotNull null
            val headerCells = rows[headerRow]
            val timeColumn = headerCells.indexOfFirst { cell -> cell?.text()?.let(::isTimeHeader) == true }
            val dayColumn = headerCells.indexOfFirst { cell -> cell?.text()?.let(::isWeekdayHeader) == true }
                .takeIf { it >= 0 }
                ?: headerCells.indexOfFirst { cell -> cell?.text()?.let(::isDateHeader) == true }
            val dateColumn = headerCells.indices.firstOrNull { column ->
                column != dayColumn && headerCells[column]?.text()?.let(::isDateHeader) == true
            } ?: dayColumn
            if (timeColumn < 0 || dayColumn < 0) return@mapNotNull null
            val groupHeaderCells = rows.getOrNull(groupRow).orEmpty()
            val nextGroupColumn = groupHeaderCells.indices.firstOrNull { column ->
                column > groupColumn && groupHeaderCells[column]?.text()?.let(::isGroupColumnHeader) == true
            }
            val sameSpanningHeader = groupHeaderCells.getOrNull(groupColumn + 1) === groupHeaderCells.getOrNull(groupColumn)
            val roomColumn = if (
                nextGroupColumn == groupColumn + 2 &&
                (groupHeaderCells.getOrNull(groupColumn + 1)?.text().isNullOrBlank() || sameSpanningHeader)
            ) {
                groupColumn + 1
            } else {
                -1
            }

            MatrixLayout(
                rows = rows,
                groupHeaderRow = groupRow,
                groupColumn = groupColumn,
                roomColumn = roomColumn,
                headerRow = headerRow,
                dayColumn = dayColumn,
                dateColumn = dateColumn,
                timeColumn = timeColumn,
                dataStart = maxOf(groupRow, headerRow) + 1,
            )
        }.firstOrNull()
    }

    private fun expandTableRows(table: Element): List<List<Element?>> {
        val carry = mutableMapOf<Int, MutableMap<Int, Element>>()
        return table.select("tr").toList().mapIndexed { rowIndex, row ->
            val expanded = mutableListOf<Element?>()
            fun place(column: Int, cell: Element) {
                while (expanded.size <= column) expanded += null
                if (expanded[column] == null) expanded[column] = cell
            }

            carry.remove(rowIndex)?.forEach { (column, cell) -> place(column, cell) }
            var column = 0
            row.select("th, td").forEach { cell ->
                while (column < expanded.size && expanded[column] != null) column++
                val colSpan = cell.attr("colspan").toIntOrNull()?.coerceAtLeast(1) ?: 1
                val rowSpan = cell.attr("rowspan").toIntOrNull()?.coerceAtLeast(1) ?: 1
                repeat(colSpan) { spanOffset ->
                    val targetColumn = column + spanOffset
                    place(targetColumn, cell)
                    if (rowSpan > 1) {
                        repeat(rowSpan - 1) { rowOffset ->
                            val targetRow = rowIndex + rowOffset + 1
                            carry.getOrPut(targetRow) { mutableMapOf() }[targetColumn] = cell
                        }
                    }
                }
                column += colSpan
            }
            expanded
        }
    }

    private fun contentLines(element: Element): List<String> {
        val html = element.html()
            .replace(BR_TAG, "\n")
            .replace(BLOCK_START_TAG, "\n")
            .replace(BLOCK_END_TAG, "\n")
            .replace(HTML_TAG, " ")
            .replace("&nbsp;", " ", ignoreCase = true)
            .replace("&#160;", " ", ignoreCase = true)
        return html.split('\n')
            .map(::normalize)
            .filter(String::isNotBlank)
            .ifEmpty { listOf(normalize(element.text())).filter(String::isNotBlank) }
    }

    private fun splitInlineTeacher(text: String): Pair<String, String>? {
        val match = INLINE_TEACHER_LINE.matchEntire(normalize(text)) ?: return null
        val subject = match.groupValues.getOrNull(1)?.trim().orEmpty()
        val teacher = match.groupValues.getOrNull(2)?.trim().orEmpty()
        return if (subject.isNotBlank() && teacher.isNotBlank()) subject to teacher else null
    }

    private fun teacherFromLine(line: String): String? =
        TEACHER_LINE.find(line)?.groupValues?.getOrNull(1)?.trim()?.takeIf(String::isNotBlank)
            ?: TEACHER_TITLE_LINE.matchEntire(line)?.groupValues?.getOrNull(1)?.trim()?.takeIf(String::isNotBlank)
            ?: PLAIN_TEACHER_LINE.matchEntire(line)?.value?.takeIf(String::isNotBlank)

    private fun roomFromLine(line: String): String? =
        ROOM_LINE.find(line)?.groupValues?.getOrNull(1)?.trim()?.takeIf(String::isNotBlank)
            ?: NUMERIC_ROOM_LINE.matchEntire(line)?.value?.takeIf(String::isNotBlank)

    private fun groupLabelsIn(text: String): List<String> {
        val codes = GROUP_CODE.findAll(text).map { it.value }.toList()
        val descriptor = parseGroupDescriptor(text)
        val descriptorLabel = descriptor?.let { group ->
            if (group.subgroup == null) "${group.number} группа"
            else "${group.number} группа ${displaySubgroup(group.subgroup)}"
        }
        return (codes + listOfNotNull(descriptorLabel)).distinct()
    }

    private fun parseGroupDescriptor(text: String): GroupDescriptor? {
        val match = GROUP_DESCRIPTOR.find(normalize(text)) ?: return null
        val number = match.groupValues[1].takeIf(String::isNotBlank) ?: return null
        val subgroup = match.groupValues.getOrNull(2)?.takeIf(String::isNotBlank)?.let(::normalizeSubgroup)
        return GroupDescriptor(number, subgroup)
    }

    private fun displaySubgroup(text: String): String = when (text) {
        "a" -> "А"
        "b" -> "Б"
        else -> text.uppercase()
    }

    private fun normalizeSubgroup(text: String): String = normalize(text)
        .lowercase()
        .replace('ё', 'е')
        .filter { it.isLetterOrDigit() }
        .let { value ->
            when (value) {
                "а", "a" -> "a"
                "б", "b" -> "b"
                else -> value
            }
        }

    private fun normalizeGroupToken(text: String): String = normalize(text)
        .lowercase()
        .replace('ё', 'е')
        .replace("группы", "групп")
        .replace("группа", "групп")
        .filter { it.isLetterOrDigit() }

    private fun findGroupCells(
        groupKey: String,
        wantedDescriptor: GroupDescriptor?,
        row: List<Element?>,
    ): List<GroupCell> {
        val texts = row.map { it?.text()?.let(::normalize).orEmpty() }
        val direct = texts.mapIndexedNotNull { columnIndex, text ->
            if (matchesGroup(groupKey, text)) GroupCell(columnIndex, text) else null
        }.distinctBy { normalizeGroupToken(it.text) }
        val splitHeaders = buildList {
            texts.forEachIndexed { groupIndex, groupText ->
                val headerDescriptor = parseGroupDescriptor(groupText) ?: return@forEachIndexed
                if (wantedDescriptor == null || headerDescriptor.number != wantedDescriptor.number) return@forEachIndexed
                val wantedSubgroup = wantedDescriptor.subgroup
                if (wantedSubgroup == null) {
                    add(GroupCell(groupIndex, groupText))
                    return@forEachIndexed
                }
                if (headerDescriptor.subgroup == wantedSubgroup) {
                    add(GroupCell(groupIndex, groupText))
                    return@forEachIndexed
                }
                val lastColumn = minOf(texts.lastIndex, groupIndex + 2)
                for (subgroupIndex in (groupIndex + 1)..lastColumn) {
                    if (normalizeSubgroup(texts[subgroupIndex]) == wantedSubgroup) {
                        add(GroupCell(subgroupIndex, "${wantedDescriptor.number} группа ${texts[subgroupIndex]}"))
                    }
                }
            }
        }
        return (direct + splitHeaders).distinctBy { "${it.columnIndex}:${normalizeGroupToken(it.text)}" }
    }

    private fun matchesGroup(groupKey: String, cellText: String): Boolean {
        val cellKey = normalizeGroupToken(cellText)
        return cellKey.length >= groupKey.length && cellKey.contains(groupKey)
    }

    private fun isTimeHeader(text: String): Boolean {
        val normalized = normalize(text).lowercase().replace('ё', 'е')
        return normalized.contains("время") || normalized.contains("час") || normalized.contains("time")
    }

    private fun isWeekdayHeader(text: String): Boolean {
        val normalized = normalize(text).lowercase().replace('ё', 'е')
        return normalized.contains("день") || normalized.contains("weekday") || weekdayNumber(normalized) != null
    }

    private fun isDateHeader(text: String): Boolean {
        val normalized = normalize(text).lowercase().replace('ё', 'е')
        return normalized.contains("дата") || normalized.contains("число") || normalized.contains("date")
    }

    private fun isDayHeader(text: String): Boolean = isWeekdayHeader(text) || isDateHeader(text)

    private fun isGroupColumnHeader(text: String): Boolean =
        GROUP_DESCRIPTOR.containsMatchIn(text) || GROUP_CODE.containsMatchIn(text)

    private fun cellText(row: List<Element?>, column: Int): String =
        row.getOrNull(column)?.text()?.let(::normalize).orEmpty()

    private fun weekdayNumber(text: String): Int? {
        val normalized = normalize(text).lowercase().replace('ё', 'е')
        return when {
            "понедельник" in normalized || "панядзел" in normalized || Regex("\\bпн\\b").containsMatchIn(normalized) -> 1
            "вторник" in normalized || "аўторак" in normalized || "ауторок" in normalized || Regex("\\bвт\\b").containsMatchIn(normalized) -> 2
            "среда" in normalized || "серада" in normalized || Regex("\\bср\\b").containsMatchIn(normalized) -> 3
            "четверг" in normalized || "чацвер" in normalized || Regex("\\bчт\\b").containsMatchIn(normalized) -> 4
            "пятниц" in normalized || "пятница" in normalized || Regex("\\bпт\\b").containsMatchIn(normalized) -> 5
            "суббот" in normalized || "субота" in normalized || Regex("\\bсб\\b").containsMatchIn(normalized) -> 6
            "воскресень" in normalized || "нядзел" in normalized || Regex("\\bвс\\b").containsMatchIn(normalized) -> 7
            else -> null
        }
    }

    private fun weekdayDate(monday: LocalDate, weekday: Int): LocalDate? =
        runCatching { monday.plus(DatePeriod(days = weekday - 1)) }.getOrNull()

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

    private fun normalizeTime(text: String): String {
        val parts = text.replace('.', ':').split(':', limit = 2)
        return "${parts[0].padStart(2, '0')}:${parts.getOrElse(1) { "00" }}"
    }

    private fun normalize(text: String): String = text
        .replace('\u00a0', ' ')
        .replace(WHITESPACE, " ")
        .trim()

    private fun String?.isGoogleSheetsUrl(): Boolean =
        this?.contains("docs.google.com/spreadsheets/d/", ignoreCase = true) == true

    private data class GroupCell(val columnIndex: Int, val text: String)

    private data class GroupDescriptor(val number: String, val subgroup: String?)

    private data class MatrixLayout(
        val rows: List<List<Element?>>,
        val groupHeaderRow: Int,
        val groupColumn: Int,
        val roomColumn: Int,
        val headerRow: Int,
        val dayColumn: Int,
        val dateColumn: Int,
        val timeColumn: Int,
        val dataStart: Int,
    )

    private companion object {
        const val MAX_HEADER_ROWS = 12
        const val ONE_OFF_KEY_MARKER = "|once"
        const val EXCLUDED_DATE_KEY_MARKER = "|except:"
        val TIME_RANGE = Regex("(\\d{1,2}[:.]\\d{2})\\s*[-–—−]\\s*(\\d{1,2}[:.]\\d{2})")
        val PAIR_NUMBER = Regex("(\\d+)\\s*(?:пара|занятие|pair)", RegexOption.IGNORE_CASE)
        val EXPLICIT_DATE = Regex("(\\d{1,2})\\.(\\d{1,2})(?:\\.(\\d{4}))?")
        val ONLY_DATE = Regex("только\\s+(\\d{1,2}\\.\\d{1,2}(?:\\.\\d{4})?)", RegexOption.IGNORE_CASE)
        val EXCEPT_DATE = Regex("кроме\\s+(\\d{1,2}\\.\\d{1,2}(?:\\.\\d{4})?)", RegexOption.IGNORE_CASE)
        val DATE_CONDITION = Regex("(?:только|кроме)\\s+\\d{1,2}\\.\\d{1,2}(?:\\.\\d{4})?\\.?", RegexOption.IGNORE_CASE)
        val GROUP_DESCRIPTOR = Regex("(\\d{1,2})\\s*(?:групп[аы]?|group)\\s*([а-яёa-z])?", RegexOption.IGNORE_CASE)
        val GROUP_CODE = Regex("\\b\\d{1,2}-\\d{1,3}\\b")
        val SUBGROUP_HEADER = Regex("^[а-яёa-z]$", RegexOption.IGNORE_CASE)
        val TEACHER_LINE = Regex("(?:преподаватель|преп\\.?|teacher|викладчык)\\s*[:–-]?\\s*(.+)", RegexOption.IGNORE_CASE)
        val LECTURE_CODE = Regex("(^|\\s)лк\\.?($|\\s)", RegexOption.IGNORE_CASE)
        val PRACTICE_CODE = Regex("(^|\\s)пз\\.?($|\\s)", RegexOption.IGNORE_CASE)
        val INLINE_TEACHER_LINE = Regex(
            "^(.+?)\\s+((?:(?:проф(?:ессор)?|доц(?:ент)?|ст\\.?\\s*преп\\.?|ассистент)\\s+)?[А-ЯЁ][а-яё-]+\\s+[А-ЯЁ]\\.\\s*[А-ЯЁ]\\.?)$",
            RegexOption.IGNORE_CASE,
        )
        val TEACHER_TITLE_LINE = Regex(
            "^(?:(?:проф(?:ессор)?\\.?|доц(?:ент)?\\.?|ст\\.?\\s*преп\\.?|ассистент|старший\\s+преподаватель)\\s+)?([А-ЯЁ][а-яё-]+\\s+[А-ЯЁ]\\.\\s*[А-ЯЁ]\\.?)$",
            RegexOption.IGNORE_CASE,
        )
        val PLAIN_TEACHER_LINE = Regex("^[А-ЯЁ][а-яё-]+\\s+[А-ЯЁ]\\.\\s*[А-ЯЁ]\\.?$", RegexOption.IGNORE_CASE)
        val ROOM_LINE = Regex("(?:ауд(?:итория)?\\.?|каб(?:инет)?\\.?|room|корпус)\\s*[:№–-]?\\s*(.+)", RegexOption.IGNORE_CASE)
        val NUMERIC_ROOM_LINE = Regex("^\\d{1,4}[a-zа-яё]?(?:\\s+[a-zа-яё-]+)?$", RegexOption.IGNORE_CASE)
        val TYPE_ONLY_LINE = Regex("^(?:лекц(?:ия)?\\.?|лаб(?:ораторн(?:ая)?)?\\.?|практ(?:ика)?\\.?|лк\\.?|пз\\.?|семинар|зачет|экзамен|lecture|laboratory|practice)$", RegexOption.IGNORE_CASE)
        val SUBJECT_PREFIX = Regex("^(?:лекция|лекц\\.?|лабораторная|лаб\\.?|практика|практ\\.?|лк\\.?|пз\\.?)\\s*[:–-]?\\s*", RegexOption.IGNORE_CASE)
        val WHITESPACE = Regex("\\s+")
        val BR_TAG = Regex("(?i)<br\\s*/?>")
        val BLOCK_START_TAG = Regex("(?i)<(?:div|p|li)[^>]*>")
        val BLOCK_END_TAG = Regex("(?i)</(?:div|p|li)\\s*>")
        val HTML_TAG = Regex("<[^>]+>")
    }
}
