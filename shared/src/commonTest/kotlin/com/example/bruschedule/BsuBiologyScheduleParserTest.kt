package com.example.bruschedule

import com.example.bruschedule.data.remote.BruScheduleRemoteDataSource
import com.example.bruschedule.data.remote.BsuScheduleProfileCatalogRepository
import com.example.bruschedule.data.remote.HtmlTransport
import com.example.bruschedule.data.scraper.BsuBiologyScheduleHtmlParser
import com.example.bruschedule.data.scraper.BsuScheduleHtmlParser
import com.example.bruschedule.data.scraper.UniversityScheduleHtmlParser
import com.example.bruschedule.domain.model.ScheduleProfile
import com.example.bruschedule.domain.model.ScheduleProfileType
import com.example.bruschedule.domain.model.ScheduleWindow
import com.example.bruschedule.domain.model.UniversityIds
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock

class BsuBiologyScheduleParserTest {
    private val profile = ScheduleProfile(
        id = "bsu-bio-group-42",
        type = ScheduleProfileType.GROUP,
        name = "Группа 42",
        sourceUrl = "https://bio.bsu.by/schedule/?study_mode=%D0%94%D0%BD%D0%B5%D0%B2%D0%BD%D0%B0%D1%8F&course=3&week_date=2026-10-19",
        sourceKey = "${BsuBiologyScheduleHtmlParser.BIO_GROUP_SOURCE_KEY_PREFIX}48",
        universityId = UniversityIds.BSU,
    )

    @Test
    fun biologyParserSelectsTheRequestedGroupByDataGroupIdAndReadsSlotMetadata() {
        val parsed = BsuScheduleHtmlParser().parse(profile, biologyWeekHtml("2026-10-19", "Биологическая статистика (ЛК)"), LocalDate(2026, 10, 19))

        assertEquals(1, parsed.size)
        assertEquals("Биологическая статистика (ЛК)", parsed.single().subjectName)
        assertEquals("Нестерова Оксана Львовна", parsed.single().teacherName)
        assertEquals("ауд. 3", parsed.single().classroom)
        assertEquals("2026-10-19", parsed.single().lessonDate)
        assertEquals(1, parsed.single().dayOfWeek)
        assertEquals(1, parsed.single().slotNumber)
        assertEquals("09:00", parsed.single().startTime)
        assertEquals("10:25", parsed.single().endTime)
        assertEquals(com.example.bruschedule.domain.model.LessonType.LECTURE, parsed.single().type)
        assertTrue(parsed.single().sourceKey.contains("bio:48"))
    }

    @Test
    fun biologyGroupPickerMapsVisibleGroupNumbersToServerGroupIds() {
        val groups = BsuBiologyScheduleHtmlParser().discoverGroups(biologyWeekHtml("2026-10-19", "Математика"))

        assertEquals(listOf("47", "48"), groups.map { it.id })
        assertEquals(listOf("Группа 41", "Группа 42"), groups.map { it.name })
        assertEquals("Биология 6-05-0511-01", groups.last().programme)
    }

    @Test
    fun multiWeekBiologyResponseKeepsTheServerDatesAndDoesNotGetExpandedAsAWeeklyTemplate() {
        val parser = BsuBiologyScheduleHtmlParser()
        val response = parser.combineWeeklyPages(
            listOf(
                LocalDate(2026, 10, 19) to biologyWeekHtml("2026-10-19", "Математика"),
                LocalDate(2026, 10, 26) to biologyWeekHtml("2026-10-26", "Биология"),
            ),
        )

        val lessons = UniversityScheduleHtmlParser().parse(profile, response, LocalDate(2026, 10, 19))

        assertEquals(listOf("2026-10-19", "2026-10-26"), lessons.map { it.lessonDate })
        assertEquals(listOf("Математика", "Биология"), lessons.map { it.subjectName })
        assertFalse(UniversityScheduleHtmlParser().hasWeeklyScheduleTemplate(profile, response))
        assertTrue(UniversityScheduleHtmlParser().hasWeeklyScheduleTemplate(profile, biologyWeekHtml("2026-10-19", "Математика")))
    }

    @Test
    fun biologyRemoteLoadsEachWeekThroughTheSemesterCoverageHorizon() {
        val requested = mutableListOf<String>()
        val transport = object : HtmlTransport {
            override suspend fun get(url: String): String {
                requested += url
                return "<html><body>$url</body></html>"
            }
        }
        val window = ScheduleWindow(
            startDate = LocalDate(2026, 10, 19),
            endDate = LocalDate(2026, 11, 1),
            semesterCoverageEndDate = LocalDate(2026, 11, 5),
        )

        val combined = runSuspending { BruScheduleRemoteDataSource(transport).fetchHtml(profile, window) }

        assertEquals(
            listOf(
                "2026-10-19",
                "2026-10-26",
                "2026-11-02",
            ),
            requested.map { it.substringAfter("week_date=").substringBefore('&') }.sorted(),
        )
        assertTrue(combined.contains("BSU-BIO-WEEK:2026-10-19"))
        assertTrue(combined.contains("BSU-BIO-WEEK:2026-10-26"))
        assertTrue(combined.contains("BSU-BIO-WEEK:2026-11-02"))
    }

    @Test
    fun biologyCatalogueSearchesTheActualStudyModeAndCourseFilters() {
        val expectedUrl = "https://bio.bsu.by/schedule/?study_mode=%D0%94%D0%BD%D0%B5%D0%B2%D0%BD%D0%B0%D1%8F&course=3&week_date=2026-10-19"
        val requested = mutableListOf<String>()
        val transport = object : HtmlTransport {
            override suspend fun get(url: String): String {
                requested += url
                return if (url == expectedUrl) biologyWeekHtml("2026-10-19", "Математика")
                else error("No fixture for $url")
            }
        }
        val fixedClock = object : Clock() {
            override fun now(): Instant = Instant.parse("2026-10-23T09:00:00Z")
        }

        val matches = runSuspending {
            BsuScheduleProfileCatalogRepository(transport, clock = fixedClock)
                .search(UniversityIds.BSU, ScheduleProfileType.GROUP, "42")
        }

        assertEquals(1, matches.size)
        assertEquals("Группа 42", matches.single().name)
        assertEquals(expectedUrl, matches.single().sourceUrl)
        assertEquals("bio-group-id:48", matches.single().sourceKey)
        assertTrue(matches.single().subtitle.contains("Дневная"))
        assertTrue(matches.single().subtitle.contains("3 курс"))
        assertTrue(requested.contains(expectedUrl))
    }

    private fun biologyWeekHtml(weekStart: String, subject: String): String {
        val parts = weekStart.split('-')
        val displayDate = "${parts[2]}.${parts[1]}.${parts[0]}"
        val endDate = LocalDate.parse(weekStart).plus(kotlinx.datetime.DatePeriod(days = 6))
        val endDisplay = "${endDate.dayOfMonth.toString().padStart(2, '0')}.${endDate.monthNumber.toString().padStart(2, '0')}.${endDate.year}"
        return """
            <div id="week-info">$displayDate — $endDisplay</div>
            <table class="grid-table">
              <thead id="schedule-header"><tr>
                <th class="day-header">День</th><th class="time-header">Время</th>
                <th class="group-header"><span class="group-header-number">Группа 41</span><span class="group-header-name">Экология</span></th>
                <th class="group-header"><span class="group-header-number">Группа 42</span><span class="group-header-name">Биология 6-05-0511-01</span></th>
              </tr></thead>
              <tbody id="schedule-body"><tr>
                <td class="time-header" data-day="0" data-slot-id="1"><strong>1 пара</strong><br><span>09:00-10:25</span></td>
                <td class="cell" data-day="0" data-slot="1" data-group-id="47"><div class="lesson-card lecture"><div class="lesson-title">Не выбранный курс</div></div></td>
                <td class="cell" data-day="0" data-slot="1" data-group-id="48">
                  <div class="lesson-card lecture is-readonly-preview">
                    <div class="lesson-title">$subject</div>
                    <div class="lesson-teacher"><a class="teacher-link">Нестерова Оксана Львовна</a></div>
                    <div class="lesson-room">ауд. 3</div>
                  </div>
                </td>
              </tr></tbody>
            </table>
            <div id="schedule-group-filter-list">
              <label class="schedule-group-option"><input type="checkbox" value="47"><span>Группа 41</span></label>
              <label class="schedule-group-option"><input type="checkbox" value="48"><span>Группа 42</span></label>
            </div>
        """.trimIndent()
    }

    private fun <T> runSuspending(block: suspend () -> T): T {
        var value: Result<T>? = null
        block.startCoroutine(object : Continuation<T> {
            override val context = EmptyCoroutineContext
            override fun resumeWith(result: Result<T>) {
                value = result
            }
        })
        return checkNotNull(value).getOrThrow()
    }
}
