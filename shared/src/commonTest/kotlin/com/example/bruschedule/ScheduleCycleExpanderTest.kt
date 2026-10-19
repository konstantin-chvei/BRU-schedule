package com.example.bruschedule

import com.example.bruschedule.data.scraper.BruScheduleScraper
import com.example.bruschedule.domain.model.Lesson
import com.example.bruschedule.domain.model.LessonType
import com.example.bruschedule.domain.model.ScheduleProfile
import com.example.bruschedule.domain.model.ScheduleProfileType
import com.example.bruschedule.domain.usecase.ScheduleCycleExpander
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ScheduleCycleExpanderTest {
    @Test
    fun onlyExplicitRegularWeekOneAndWeekTwoHeadersEnableCycleExpansion() {
        val scraper = BruScheduleScraper()
        val regularPage = """
            <section>
              <h3>Неделя 1: 19.10 – 25.10.2026</h3>
              <h3>Неделя 2: 26.10 – 01.11.2026</h3>
            </section>
        """.trimIndent()
        val examSessionPage = """
            <div class="week-header">Зимняя экзаменационная сессия</div>
            <div class="week-header">Консультации и экзамены</div>
        """.trimIndent()

        assertTrue(scraper.hasAlternatingTwoWeekCycle(regularPage))
        assertFalse(scraper.hasAlternatingTwoWeekCycle(examSessionPage))
        assertFalse(scraper.hasAlternatingTwoWeekCycle("<div class=week-header>Неделя 1</div>"))
    }

    @Test
    fun repeatsBothWeeksForTwentyEightWeeksWithStableDateAndSlotKeys() {
        val start = LocalDate(2026, 10, 19)
        val through = start.plus(DatePeriod(days = ScheduleCycleExpander.SEMESTER_HORIZON_DAYS - 1))
        val template = listOf(
            lesson("2026-10-19", "group-mpr-261|2026-10-19|1|08:30-10:05|0"),
            lesson("2026-10-26", "group-mpr-261|2026-10-26|1|08:30-10:05|0"),
        )

        val expanded = ScheduleCycleExpander.expand(template, start, through)
        val repeated = ScheduleCycleExpander.expand(template, start, through)

        assertEquals(28, expanded.size)
        assertEquals(expanded.map { it.sourceKey }, repeated.map { it.sourceKey })
        assertEquals(expanded.size, expanded.map { it.id }.distinct().size)
        assertEquals(start.toString(), expanded.first().lessonDate)
        assertEquals(start.plus(DatePeriod(days = 189)).toString(), expanded.last().lessonDate)
        assertTrue(expanded.all { it.sourceKey.contains("|${it.lessonDate}|1|08:30-10:05|0") })
        assertTrue(expanded.all { !it.isCustom })
    }

    @Test
    fun customLessonsAreNeverRepeatedWithTheWebsiteCycle() {
        val start = LocalDate(2026, 10, 19)
        val custom = lesson("2026-10-19", "manual:one-off").copy(isCustom = true)

        val expanded = ScheduleCycleExpander.expand(
            listOf(custom),
            start,
            start.plus(DatePeriod(days = ScheduleCycleExpander.SEMESTER_HORIZON_DAYS - 1)),
        )

        assertEquals(listOf(custom), expanded)
    }

    private fun lesson(date: String, sourceKey: String) = Lesson(
        id = "web:$sourceKey",
        profileId = "group-mpr-261",
        subjectName = "Информатика",
        teacherName = "Преподаватель",
        classroom = "ауд. 530",
        startTime = "08:30",
        endTime = "10:05",
        type = LessonType.LECTURE,
        colorHex = "#A7B9FF",
        isCustom = false,
        version = 1,
        lessonDate = date,
        dayOfWeek = 1,
        slotNumber = 1,
        sourceKey = sourceKey,
    )
}
