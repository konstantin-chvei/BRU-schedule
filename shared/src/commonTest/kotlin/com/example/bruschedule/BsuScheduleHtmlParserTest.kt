package com.example.bruschedule

import com.example.bruschedule.data.scraper.BsuScheduleHtmlParser
import com.example.bruschedule.domain.model.ScheduleProfile
import com.example.bruschedule.domain.model.ScheduleProfileType
import com.example.bruschedule.domain.usecase.ScheduleCycleExpander
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BsuScheduleHtmlParserTest {
    private val parser = BsuScheduleHtmlParser()

    @Test
    fun filtersGoogleSheetMatrixToTheSelectedGroupAndCarriesMergedDayDateAndMetadataRows() {
        val profile = ScheduleProfile(
            id = "bsu-econ-1-11",
            type = ScheduleProfileType.GROUP,
            name = "1-11",
            sourceUrl = "https://docs.google.com/spreadsheets/d/example-sheet/edit?gid=0",
            sourceKey = "1-11",
            universityId = "bsu",
        )
        val html = """
            <html><body>
              <table class="google-visualization-table-table">
                <thead><tr><th>День</th><th>Дата</th><th>Время</th><th>1-11</th><th></th><th>1-12</th><th></th></tr></thead>
                <tbody>
                  <tr><td>Понедельник</td><td>19.10.2026</td><td>08.15–09.40</td><td>Математический анализ</td><td>515</td><td>Физика</td><td>521</td></tr>
                  <tr><td></td><td></td><td></td><td>Преподаватель: Иванова Н.В.</td><td></td><td>Петров П.П.</td><td></td></tr>
                  <tr><td></td><td></td><td></td><td></td><td>515</td><td></td><td>521</td></tr>
                  <tr><td></td><td></td><td></td><td>Лекция</td><td></td><td>Лабораторная</td><td></td></tr>
                  <tr><td>Вторник</td><td>20.10.2026</td><td>09.50-<br>11.15</td><td>История Беларуси</td><td>527</td><td>Химия</td><td>532</td></tr>
                </tbody>
              </table>
            </body></html>
        """.trimIndent()

        val lessons = parser.parse(profile, html, LocalDate(2026, 10, 19))

        assertEquals(2, lessons.size)
        assertEquals(listOf("Математический анализ", "История Беларуси"), lessons.map { it.subjectName })
        assertEquals(listOf("2026-10-19", "2026-10-20"), lessons.map { it.lessonDate })
        assertEquals("Иванова Н.В.", lessons.first().teacherName)
        assertEquals("515", lessons.first().classroom)
        assertEquals("08:15", lessons.first().startTime)
        assertEquals("09:40", lessons.first().endTime)
        assertEquals("09:50", lessons.last().startTime)
        assertEquals("527", lessons.last().classroom)
        assertTrue(parser.hasWeeklyScheduleTemplate(profile, html))
    }

    @Test
    fun parsesEconomyGoogleSheetStyleHeadersRoomsAbbreviationsAndInlineTeachers() {
        val profile = ScheduleProfile(
            id = "bsu-econ-1-11-real-layout",
            type = ScheduleProfileType.GROUP,
            name = "1-11",
            sourceUrl = "https://docs.google.com/spreadsheets/d/example/edit",
            sourceKey = "1-11",
            universityId = "bsu",
        )
        val html = """
            <html><head><title>ЧЕТНАЯ</title></head><body>
              <h2>ЧЕТНАЯ</h2>
              <table>
                <tr>
                  <th>1 курс (бел) День</th><th>Дата</th><th>Время</th>
                  <th>Специальность 6-05-0412-01 Менеджмент, группа 1-11</th><th></th>
                  <th>Специальность 6-05-0412-01 Менеджмент, группа 1-12</th><th></th>
                </tr>
                <tr>
                  <td>ПОНЕДЕЛЬНИК</td><td>С 19.10.2026</td><td>09.45-<br>11.05</td>
                  <td>ЛК Экономическая теория<br>Зарожная Е.С.</td><td>404</td>
                  <td>ПЗ Физика<br>Петров П.П.</td><td>520</td>
                </tr>
                <tr>
                  <td></td><td></td><td>11.25-12.45</td>
                  <td>ПЗ Тайм-менеджмент Рассеко Ю.Ю.</td><td>316</td>
                  <td>ЛК Химия</td><td>521</td>
                </tr>
              </table>
            </body></html>
        """.trimIndent()

        val lessons = parser.parse(profile, html, LocalDate(2026, 10, 19))

        assertEquals(2, lessons.size)
        assertEquals("Экономическая теория", lessons[0].subjectName)
        assertEquals("Зарожная Е.С.", lessons[0].teacherName)
        assertEquals("404", lessons[0].classroom)
        assertEquals("LECTURE", lessons[0].type.name)
        assertEquals("Тайм-менеджмент", lessons[1].subjectName)
        assertEquals("Рассеко Ю.Ю.", lessons[1].teacherName)
        assertEquals("316", lessons[1].classroom)
        assertEquals("PRACTICE", lessons[1].type.name)
        assertTrue(parser.hasAlternatingTwoWeekCycle(html))
        assertFalse(parser.hasWeeklyScheduleTemplate(profile, html))
    }

    @Test
    fun separatesOnlyDateAndExceptDateRowsBeforeAlternatingExpansion() {
        val profile = ScheduleProfile(
            id = "bsu-econ-1-11-exceptions",
            type = ScheduleProfileType.GROUP,
            name = "1-11",
            sourceUrl = "https://docs.google.com/spreadsheets/d/example/edit",
            sourceKey = "1-11",
            universityId = "bsu",
        )
        val html = """
            <html><body><h2>ЧЕТНАЯ</h2>
              <table>
                <tr><th>1 курс День</th><th>Дата</th><th>Время</th><th>Группа 1-11</th><th></th><th>Группа 1-12</th><th></th></tr>
                <tr>
                  <td>Понедельник</td><td>19.10.2026</td><td>11.25-12.45</td>
                  <td>КРОМЕ 19.10. ЛК Компьютерная графика<br>Макаревич В.А. /<br>ПЗ Психология управления Василевич Т.В. ТОЛЬКО 19.10.</td>
                  <td>79</td><td>Математика</td><td>80</td>
                </tr>
              </table>
            </body></html>
        """.trimIndent()

        val parsed = parser.parse(profile, html, LocalDate(2026, 10, 19))
        val expanded = ScheduleCycleExpander.expand(parsed, LocalDate(2026, 10, 19), LocalDate(2026, 11, 16))

        assertEquals(2, parsed.size)
        assertEquals("Компьютерная графика", parsed.first { it.type.name == "LECTURE" }.subjectName)
        assertEquals("Макаревич В.А.", parsed.first { it.type.name == "LECTURE" }.teacherName)
        assertEquals("Психология управления", parsed.first { it.type.name == "PRACTICE" }.subjectName)
        assertEquals("Василевич Т.В.", parsed.first { it.type.name == "PRACTICE" }.teacherName)
        assertEquals(
            listOf("2026-11-02", "2026-11-16"),
            expanded.filter { it.type.name == "LECTURE" }.map { it.lessonDate },
        )
        assertEquals(listOf("2026-10-19"), expanded.filter { it.type.name == "PRACTICE" }.map { it.lessonDate })
    }

    @Test
    fun routesMmfHtmlTableThroughTheSharedVerticalTableAdapter() {
        val profile = ScheduleProfile(
            id = "bsu-mmf-1",
            type = ScheduleProfileType.GROUP,
            name = "1 группа",
            sourceUrl = "https://mmf.bsu.by/ru/raspisanie-zanyatij/dnevnoe-otdelenie/1-kurs/1-gruppa/",
            sourceKey = "1 группа",
            universityId = "bsu",
        )
        val html = """
            <table>
              <tr><th>День</th><th>Время</th><th>Группа</th><th>Предмет, преподаватель</th><th>Тип</th><th>Аудитория</th></tr>
              <tr><td>Понедельник</td><td>09.45–11.05</td><td>1 группа</td><td>Теория вероятностей<br>доц. Иванова Н.В.</td><td>ЛК</td><td>530</td></tr>
            </table>
        """.trimIndent()

        val lesson = parser.parse(profile, html, LocalDate(2026, 10, 19)).single()

        assertEquals("Теория вероятностей", lesson.subjectName)
        assertEquals("Иванова Н.В.", lesson.teacherName)
        assertEquals("530", lesson.classroom)
        assertEquals("LECTURE", lesson.type.name)
        assertEquals("09:45", lesson.startTime)
    }

    @Test
    fun selectsSubgroupWhenItsLabelIsSplitAcrossAdjacentHeaderCells() {
        val profile = ScheduleProfile(
            id = "bsu-fpmi-1b",
            type = ScheduleProfileType.GROUP,
            name = "1 группа Б",
            sourceUrl = "https://docs.google.com/spreadsheets/d/example-sheet/edit?gid=123",
            sourceKey = "1 группа Б",
            universityId = "bsu",
        )
        val html = """
            <table>
              <tr><th>День</th><th>Время</th><th>1 группа</th><th>А</th><th>Б</th></tr>
              <tr><td>Понедельник</td><td>09.00–10.20</td><td></td><td>Математика А</td><td>Физика Б</td></tr>
            </table>
        """.trimIndent()

        val lesson = parser.parse(profile, html, LocalDate(2026, 10, 19)).single()

        assertEquals("Физика Б", lesson.subjectName)
        assertEquals("09:00", lesson.startTime)
        assertEquals("2026-10-19", lesson.lessonDate)
    }

    @Test
    fun treatsAnExplicitOddOrEvenWorksheetAsAnAlternatingTwoWeekTemplate() {
        val html = """
            <h2>Нечётная неделя</h2>
            <table>
              <tr><th>День</th><th>Время</th><th>Группа 1-11</th></tr>
              <tr><td>Понедельник, 19.10.2026</td><td>08.15–09.40</td><td>Математика</td></tr>
            </table>
        """.trimIndent()

        assertTrue(parser.hasAlternatingTwoWeekCycle(html))
    }
}
