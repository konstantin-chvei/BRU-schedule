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

/** Exercises the production Ktor engine against the public Biology timetable endpoint. */
class BsuBiologyKtorLiveSmokeTest {
    @Test
    fun productionTransportDownloadsAndParsesTheLiveGroupPageWhenTlsIsValid() = runTest {
        val transport = createPlatformHtmlTransport()
        try {
            transport.primeBiologySession()
            val html = try {
                transport.get(LIVE_GROUP_URL)
            } catch (failure: Throwable) {
                // Keep TLS strict when the public host presents an invalid or expired certificate.
                assertTrue(
                    failure.isCertificateTrustFailure(),
                    "Unexpected live Ktor failure for $LIVE_GROUP_URL: ${failure.message}",
                )
                return@runTest
            }
            val parser = BsuBiologyScheduleHtmlParser()

            assertTrue(parser.hasScheduleGrid(html), "Ktor did not receive the live group timetable")
            val profile = ScheduleProfile(
                id = "live-bsu-biology-course-1-group-41",
                type = ScheduleProfileType.GROUP,
                name = "Группа 41",
                sourceUrl = LIVE_GROUP_URL,
                sourceKey = "${BsuBiologyScheduleHtmlParser.BIO_GROUP_NUMBER_SOURCE_KEY_PREFIX}41",
                universityId = UniversityIds.BSU,
            )
            val lessons = parser.parse(profile, html, LocalDate(2026, 10, 23))

            assertTrue(lessons.isNotEmpty(), "The live Ktor response contained no parsed Biology lessons")
            assertTrue(lessons.any { it.subjectName.contains("Физическая культура") })
        } finally {
            (transport as? KtorHtmlTransport)?.close()
        }
    }

    private fun Throwable.isCertificateTrustFailure(): Boolean =
        generateSequence(this) { it.cause }
            .mapNotNull { it.message?.lowercase() }
            .joinToString(" ")
            .let { details ->
                "certificate" in details || "certpath" in details || "sslpeertrust" in details
            }

    private companion object {
        const val LIVE_GROUP_URL =
            "https://bsu.bio/schedule.php?study=d&course=1&group=41&date=2026-10-23"
    }
}
