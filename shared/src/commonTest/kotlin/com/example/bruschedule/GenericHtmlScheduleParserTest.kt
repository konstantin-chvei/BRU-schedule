package com.example.bruschedule

import com.example.bruschedule.data.scraper.GenericHtmlScheduleParser
import com.example.bruschedule.domain.model.ScheduleProfile
import com.example.bruschedule.domain.model.ScheduleProfileType
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class GenericHtmlScheduleParserTest {
    private val parser = GenericHtmlScheduleParser()
    private val profile = ScheduleProfile(
        id = "bsu-is-241",
        type = ScheduleProfileType.GROUP,
        name = "ИС-241",
        universityId = "bsu",
    )

    @Test
    fun parsesTimetableWithDaysAsColumnsAndExtractsTimeTeacherAndRoom() {
        val html = """
            <table>
              <thead><tr><th>Время</th><th>Понедельник 19.10.2026</th><th>Вторник 20.10.2026</th></tr></thead>
              <tbody>
                <tr>
                  <td>1 пара 08:30–10:05</td>
                  <td>Математический анализ<br>Преподаватель: Иванова Н.В.<br>Ауд. 530<br>Лекция</td>
                  <td></td>
                </tr>
              </tbody>
            </table>
        """.trimIndent()

        val lesson = parser.parse(profile, html, LocalDate(2026, 10, 19)).single()

        assertEquals("2026-10-19", lesson.lessonDate)
        assertEquals(1, lesson.dayOfWeek)
        assertEquals("Математический анализ", lesson.subjectName)
        assertEquals("Иванова Н.В.", lesson.teacherName)
        assertEquals("530", lesson.classroom)
        assertEquals("08:30", lesson.startTime)
        assertEquals("10:05", lesson.endTime)
        assertEquals(1, lesson.slotNumber)
    }

    @Test
    fun parsesVerticalTableWithDayTimeAndSubjectRows() {
        val html = """
            <table>
              <thead><tr><th>День</th><th>Время</th><th>Дисциплина</th></tr></thead>
              <tbody>
                <tr>
                  <td>Понедельник, 19.10.2026</td>
                  <td>09:40-11:15</td>
                  <td>Физика<br>Преподаватель: Петров П.П.<br>Кабинет 12<br>Практика</td>
                </tr>
              </tbody>
            </table>
        """.trimIndent()

        val lesson = parser.parse(profile, html, LocalDate(2026, 10, 19)).single()

        assertEquals("Физика", lesson.subjectName)
        assertEquals("2026-10-19", lesson.lessonDate)
        assertEquals("Петров П.П.", lesson.teacherName)
        assertEquals("12", lesson.classroom)
        assertEquals("09:40", lesson.startTime)
        assertEquals("11:15", lesson.endTime)
    }

    @Test
    fun recognizesWeeklyTemplatesOnlyForNonBruSources() {
        val html = """
            <table>
              <thead><tr><th>Время</th><th>Понедельник</th><th>Вторник</th></tr></thead>
              <tbody><tr><td>08:30-10:05</td><td>Математика</td><td>Физика</td></tr></tbody>
            </table>
        """.trimIndent()

        assertEquals(true, parser.hasWeeklyScheduleTemplate(profile, html))
        assertEquals(false, parser.hasWeeklyScheduleTemplate(profile.copy(universityId = "bru"), html))
    }

    @Test
    fun rejectsHtmlWithoutRecognizableTimetableInsteadOfReturningAnEmptySchedule() {
        assertFailsWith<IllegalArgumentException> {
            parser.parse(profile, "<html><body>Занятий нет</body></html>", LocalDate(2026, 10, 19))
        }
    }
}
