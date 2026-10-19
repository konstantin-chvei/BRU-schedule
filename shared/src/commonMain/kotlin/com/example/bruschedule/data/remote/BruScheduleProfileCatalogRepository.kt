package com.example.bruschedule.data.remote

import com.example.bruschedule.data.scraper.BruScheduleDirectoryParser
import com.example.bruschedule.domain.model.ProfileCandidate
import com.example.bruschedule.domain.model.ScheduleProfileType
import com.example.bruschedule.domain.repository.ScheduleProfileCatalogRepository
import kotlinx.coroutines.CancellationException

/** Live profile search for multiple groups and for the University's teacher list. */
class BruScheduleProfileCatalogRepository(
    private val transport: HtmlTransport,
    private val parser: BruScheduleDirectoryParser = BruScheduleDirectoryParser(),
) : ScheduleProfileCatalogRepository {
    override suspend fun search(type: ScheduleProfileType, query: String): List<ProfileCandidate> {
        require(query.trim().length >= 2) { "Введите не менее двух символов для поиска" }
        val candidates = when (type) {
            ScheduleProfileType.TEACHER -> searchTeachers()
            ScheduleProfileType.GROUP -> searchGroups()
        }
        return candidates
            .filter { parser.matchesQuery(it, query) }
            .distinctBy { "${it.type}|${it.sourceUrl}|${it.sourceKey}" }
            .take(40)
    }

    private suspend fun searchTeachers(): List<ProfileCandidate> {
        val url = BruScheduleDirectoryParser.TEACHERS_INDEX
        val html = transport.get(url)
        return parser.teacherCandidates(html, url)
    }

    private suspend fun searchGroups(): List<ProfileCandidate> {
        val results = mutableListOf<ProfileCandidate>()
        var lastNetworkError: String? = null
        suspend fun fetchOrNull(url: String): String? = try {
            transport.get(url)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            lastNetworkError = failure.message?.takeIf(String::isNotBlank) ?: "Неизвестная ошибка сети"
            null
        }

        val roots = listOf(
            BruScheduleDirectoryParser.FULL_TIME_INDEX,
            BruScheduleDirectoryParser.PART_TIME_INDEX,
        )
        for (root in roots) {
            val rootHtml = fetchOrNull(root) ?: continue
            // Some site editions list every group directly on the root; others expose faculties.
            results += parser.groupCandidates(rootHtml, root)
            val facultyUrls = parser.facultyDirectoryUrls(rootHtml, root, ScheduleProfileType.GROUP)
            for (facultyUrl in facultyUrls) {
                val facultyHtml = fetchOrNull(facultyUrl) ?: continue
                results += parser.groupCandidates(facultyHtml, facultyUrl)
            }
        }
        if (results.isEmpty()) {
            lastNetworkError?.let { detail -> error("Не удалось загрузить каталог групп rasp.bru.by: $detail") }
        }
        return results
    }
}
