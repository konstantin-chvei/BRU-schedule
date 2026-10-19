package com.example.bruschedule

import com.example.bruschedule.data.scraper.BruScheduleDirectoryParser
import com.example.bruschedule.data.scraper.BruScheduleScraper
import com.example.bruschedule.domain.model.Lesson
import com.example.bruschedule.domain.model.LessonType
import com.example.bruschedule.domain.model.LessonTypePalette
import com.example.bruschedule.domain.model.ScheduleProfile
import com.example.bruschedule.domain.model.ScheduleProfileType
import com.example.bruschedule.domain.model.ScheduleWindow
import com.example.bruschedule.domain.usecase.ScheduleMergeStrategy
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ScheduleArchitectureTest {
    private val profile = ScheduleProfile(
        id = "group-mpr-261",
        type = ScheduleProfileType.GROUP,
        name = "МПР-261",
    )

    @Test
    fun parsesRealBruTableRowsAndMapsSiteTypesToPastelAccents() {
        val lessons = BruScheduleScraper().parse(profile, sampleWeekHtml(), LocalDate(2026, 10, 19))

        assertEquals(2, lessons.size)
        val lecture = lessons.first { it.type == LessonType.LECTURE }
        assertEquals("2026-10-19", lecture.lessonDate)
        assertEquals("Информатика", lecture.subjectName)
        assertEquals("Заровчатская Е.В", lecture.teacherName)
        assertEquals("1 корпус, ауд. 530", lecture.classroom)
        assertEquals("08:30", lecture.startTime)
        assertEquals(LessonTypePalette.LECTURE, lecture.colorHex)
        assertEquals("#3788D8", lecture.sourceColorHex)

        val laboratory = lessons.first { it.type == LessonType.LABORATORY }
        assertEquals(LessonTypePalette.LABORATORY, laboratory.colorHex)
        // The sample site currently uses amber for .lesson-lab. Semantic class beats CSS hue.
        assertEquals("#E0A800", laboratory.sourceColorHex)
    }

    @Test
    fun parsesTeacherOccupancyCardsAndTheirExplicitDates() {
        val teacher = ScheduleProfile("teacher-1", ScheduleProfileType.TEACHER, "Иванова Н.В.")
        val html = """
            <html><body>
              <div class="hero"><h2>👩‍🏫 Иванова Н.В.</h2></div>
              <div class="week-header">Неделя 1: 19.10 – 25.10.2026</div>
              <table class="schedule-table">
                <thead><tr><th class="time-col">Время</th><th>Пн<br><small>19.10</small></th></tr></thead>
                <tbody><tr><td class="time-col">1 пара 08:30–10:05</td>
                  <td><div class="occ-card occ-lab">
                    <div class="occ-line1">Лабораторная</div>
                    <div class="occ-line2">Экология</div>
                    <div class="occ-line3">БИОР-251</div>
                    <div class="occ-line4">🏛️ 7 корпус, ауд. 501</div>
                    <div class="occ-line5">📅 2026-10-19 08:30-10:05</div>
                  </div></td>
                </tr></tbody>
              </table>
            </body></html>
        """.trimIndent()

        val parsed = BruScheduleScraper().parse(teacher, html, LocalDate(2026, 10, 19)).single()

        assertEquals("Экология", parsed.subjectName)
        assertEquals("Иванова Н.В.", parsed.teacherName)
        assertEquals("7 корпус, ауд. 501", parsed.classroom)
        assertEquals("2026-10-19", parsed.lessonDate)
        assertEquals(LessonType.LABORATORY, parsed.type)
    }

    @Test
    fun parserHandlesDecemberToJanuaryHeaderAcrossTwoWeekCycle() {
        val html = """
            <html><body>
            <div class="text-muted">28.12.2026 — 10.01.2027 (2 недели)</div>
            <div class="week-header">Неделя 1: 28.12 – 03.01.2027</div>
            <table class="schedule-table">
              <thead><tr><th class="time-col">Время</th>
                <th>Пн<br><small>28.12</small></th><th>Пн<br><small>04.01</small></th>
              </tr></thead>
              <tbody><tr><td class="time-col">1 пара 08:30–10:05</td>
                <td><div class="lesson-card lesson-practice"><div class="title">Алгебра</div><div class="info">👤 Иванов И.И<br>📍 ауд. 10<br>Практика</div></div></td>
                <td><div class="lesson-card lesson-practice"><div class="title">Алгебра</div><div class="info">👤 Иванов И.И<br>📍 ауд. 10<br>Практика</div></div></td>
              </tr></tbody>
            </table></body></html>
        """.trimIndent()

        val lessons = BruScheduleScraper().parse(profile, html, LocalDate(2026, 12, 28))

        assertEquals(listOf("2026-12-28", "2027-01-04"), lessons.map { it.lessonDate })
        assertTrue(lessons.all { it.dayOfWeek == 1 })
    }

    @Test
    fun directoryResolverPrefersTheRollingWindowThatCoversTodayAndNextWeek() {
        val html = """
            <a href="https://rasp.bru.by/full_time/faculty/МПР-261_weeks_20261005_20261018.html">МПР-261</a>
            <a href="https://rasp.bru.by/full_time/faculty/МПР-261_weeks_20261019_20261101.html">МПР-261</a>
        """.trimIndent()
        val window = ScheduleWindow(LocalDate(2026, 10, 19), LocalDate(2026, 11, 1))

        val url = BruScheduleDirectoryParser().scheduleUrlFromIndex(
            html = html,
            pageUrl = "https://rasp.bru.by/full_time/faculty/index.html",
            profile = profile,
            window = window,
        )

        assertTrue(url.orEmpty().contains("20261019_20261101"))
    }

    @Test
    fun invalidMarkupFailsClosedInsteadOfProducingAnEmptySchedule() {
        assertFailsWith<IllegalArgumentException> {
            BruScheduleScraper().parse(profile, "<html><body>maintenance</body></html>", LocalDate(2026, 10, 19))
        }
    }

    @Test
    fun manualOverrideWinsOverWebsiteAndIsNotLostWhenSubjectChanges() {
        val manual = lesson(
            id = "local-lesson",
            sourceKey = "group-mpr-261|2026-10-19|1|08:30-10:05|0",
            subject = "Моя правка по информатике",
            isCustom = true,
            version = 2,
        )
        val web = lesson(
            id = "web-new-id",
            sourceKey = manual.sourceKey,
            subject = "Название на сайте",
            isCustom = false,
            version = 1,
        )

        val merged = ScheduleMergeStrategy.merge(existing = listOf(manual), incoming = listOf(web))

        assertEquals(1, merged.size)
        assertEquals("Моя правка по информатике", merged.single().subjectName)
        assertEquals("local-lesson", merged.single().id)
        assertTrue(merged.single().isCustom)
        assertEquals(2, merged.single().version)
    }

    @Test
    fun rollbackRestoresSnapshotVersionsButKeepsEditsMadeAfterTheSnapshot() {
        val oldOverriddenWeb = lesson(id = "web-1", sourceKey = "slot-1", subject = "Старая версия", isCustom = false, version = 7)
        val oldUntouchedWeb = lesson(id = "web-2", sourceKey = "slot-2", subject = "Физика", isCustom = false, version = 6)
        val currentOverride = oldOverriddenWeb.copy(subjectName = "Моя правка", isCustom = true, version = 8)
        val customAddedAfterSnapshot = lesson(
            id = "after-snapshot",
            sourceKey = "manual:after-snapshot",
            subject = "Новая ручная пара",
            isCustom = true,
        )

        val restored = ScheduleMergeStrategy.restoreSnapshot(
            snapshot = listOf(oldOverriddenWeb, oldUntouchedWeb),
            current = listOf(currentOverride, customAddedAfterSnapshot),
        )

        assertEquals("Моя правка", restored.first { it.id == "web-1" }.subjectName)
        assertTrue(restored.first { it.id == "web-1" }.isCustom)
        assertEquals(6, restored.first { it.id == "web-2" }.version)
        assertEquals("Новая ручная пара", restored.first { it.id == "after-snapshot" }.subjectName)
    }

    @Test
    fun websiteVersionIncrementsAndManualOnlyLessonsRemain() {
        val oldWeb = lesson(id = "web-1", sourceKey = "slot-A", subject = "Физика", isCustom = false, version = 4)
        val newWeb = oldWeb.copy(subjectName = "Физика · новая тема", version = 1)
        val customOnly = lesson(id = "my-extra", sourceKey = "manual:my-extra", subject = "Консультация", isCustom = true)

        val merged = ScheduleMergeStrategy.merge(listOf(oldWeb, customOnly), listOf(newWeb))

        assertEquals(2, merged.size)
        assertEquals(5, merged.first { it.id == "web-1" }.version)
        assertEquals("Консультация", merged.first { it.id == "my-extra" }.subjectName)
    }

    private fun lesson(
        id: String,
        sourceKey: String,
        subject: String,
        isCustom: Boolean,
        version: Int = 1,
    ) = Lesson(
        id = id,
        profileId = profile.id,
        subjectName = subject,
        teacherName = "Преподаватель",
        classroom = "ауд. 1",
        startTime = "08:30",
        endTime = "10:05",
        type = LessonType.LECTURE,
        colorHex = LessonTypePalette.LECTURE,
        isCustom = isCustom,
        version = version,
        lessonDate = "2026-10-19",
        dayOfWeek = 1,
        slotNumber = 1,
        sourceKey = sourceKey,
    )

    private fun sampleWeekHtml() = """
        <!doctype html>
        <html lang="ru"><head>
          <style>
            .lesson-lecture { background: #3788d8; }
            .lesson-lab { background-color: #e0a800; }
          </style>
        </head><body>
          <h2>МПР-261</h2>
          <div class="text-muted">19.10.2026 — 01.11.2026 (2 недели)</div>
          <div class="week-header">Неделя 1: 19.10 – 25.10.2026</div>
          <table class="schedule-table">
            <thead><tr>
              <th class="time-col">Время</th>
              <th>Пн<br><small>19.10</small></th>
              <th>Вт<br><small>20.10</small></th>
            </tr></thead>
            <tbody><tr>
              <td class="time-col"><div>1 пара</div><div>08:30–10:05</div></td>
              <td><div class="lesson-card lesson-lecture"><div class="title">Информатика</div><div class="info">👤 Заровчатская Е.В<br>📍 1 корпус, ауд. 530<br>Лекция</div></div></td>
              <td><div class="lesson-card lesson-lab"><div class="title">Программирование</div><div class="info">👤 Сидоров А.А<br>📍 2 корпус, ауд. 214<br>Лабораторная</div></div></td>
            </tr></tbody>
          </table>
        </body></html>
    """.trimIndent()
}
