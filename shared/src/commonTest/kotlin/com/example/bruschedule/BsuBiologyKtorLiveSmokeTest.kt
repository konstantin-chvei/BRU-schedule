package com.example.bruschedule

import com.example.bruschedule.data.remote.KtorHtmlTransport
import com.example.bruschedule.data.remote.createPlatformHtmlTransport
import com.example.bruschedule.data.remote.primeBiologySession
import com.example.bruschedule.data.scraper.BsuBiologyScheduleHtmlParser
import com.example.bruschedule.domain.model.ScheduleProfile
import com.example.bruschedule.domain.model.ScheduleProfileType
import com.example.bruschedule.domain.model.UniversityIds
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertTrue

/** Exercises the production Ktor engine against the current public Biology timetable endpoint. */
class BsuBiologyKtorLiveSmokeTest {
    @Test
    fun productionTransportDownloadsAndParsesTheLiveGroupPage() = runTest {
        val transport = createPlatformHtmlTransport()
        try {
            transport.primeBiologySession()
            val html = transport.get(LIVE_GROUP_URL)
            val parser = BsuBiologyScheduleHtmlParser()

            assertTrue(parser.hasScheduleGrid(html), "Ktor did not receive the live group timetable")
            val profile = ScheduleProfile(
                id = "live-bsu-biology-group-42",
                type = ScheduleProfileType.GROUP,
                name = "Группа 42",
                sourceUrl = LIVE_GROUP_URL,
                sourceKey = "${BsuBiologyScheduleHtmlParser.BIO_GROUP_NUMBER_SOURCE_KEY_PREFIX}42",
                universityId = UniversityIds.BSU,
            )
            val lessons = parser.parse(profile, html, LocalDate(2026, 10, 23))

            assertTrue(lessons.isNotEmpty(), "The live Ktor response contained no parsed Biology lessons")
            assertTrue(lessons.any { it.subjectName.contains("Физическая культура") })
        } finally {
            (transport as? KtorHtmlTransport)?.close()
        }
    }

    private companion object {
        const val LIVE_GROUP_URL =
            "https://bsu.bio/schedule.php?study=d&course=3&group=42&date=2026-10-23"
    }
}
