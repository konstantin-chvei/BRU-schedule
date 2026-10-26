package com.example.bruschedule.data.remote

import com.example.bruschedule.data.scraper.BruScheduleDirectoryParser
import com.example.bruschedule.domain.model.ProfileCandidate
import com.example.bruschedule.domain.model.ScheduleProfileType
import com.example.bruschedule.domain.model.UniversityIds
import com.example.bruschedule.domain.repository.ScheduleProfileCatalogRepository
import kotlinx.coroutines.CancellationException

class BruScheduleProfileCatalogRepository(
    private val transport: HtmlTransport,
    private val parser: BruScheduleDirectoryParser = BruScheduleDirectoryParser(),
) : ScheduleProfileCatalogRepository {
    override suspend fun search(
        universityId: String,
        type: ScheduleProfileType,
        query: String,
    ): List<ProfileCandidate> {
        require(universityId == UniversityIds.BRU) { "Каталог расписаний БРУ не подходит выбранному вузу" }
        return search(type, query)
    }

    override suspend fun search(type: ScheduleProfileType, query: String): List<ProfileCandidate> {
        require(query.trim().length >= 2) { "Введите не менее двух символов для поиска" }
        val candidates = when (type) {
            ScheduleProfileType.TEACHER -> searchTeachers()
            ScheduleProfileType.GROUP -> searchGroups(query.trim())
        }
        return filterCandidates(candidates, query)
            .distinctBy { "${it.type}|${it.sourceUrl}|${it.sourceKey}" }
            .take(40)
    }

    /** Discovers the complete published BRU group/teacher directory for static GitHub export. */
    suspend fun discoverAll(type: ScheduleProfileType): List<ProfileCandidate> {
        val discovery = discoverAllWithReport(type)
        if (discovery.candidates.isEmpty() && discovery.sources.any { it.requestFailures > 0 }) {
            error("Не удалось загрузить каталог расписаний rasp.bru.by")
        }
        return discovery.candidates
            .distinctBy { "${it.type}|${it.sourceUrl}|${it.sourceKey}" }
            .sortedWith(compareBy({ it.name.lowercase() }, { it.subtitle }, { it.sourceUrl }))
    }

    /** Returns request/profile counts for every live BRU directory page. */
    suspend fun discoverAllWithReport(type: ScheduleProfileType): ScheduleCatalogDiscoveryResult = when (type) {
        ScheduleProfileType.TEACHER -> discoverAllTeachersWithReport()
        ScheduleProfileType.GROUP -> discoverAllGroupsWithReport()
    }

    private fun filterCandidates(candidates: List<ProfileCandidate>, query: String): List<ProfileCandidate> =
        candidates.filter { parser.matchesQuery(it, query) }
            .sortedBy { if (parser.isExactMatch(it, query)) 0 else 1 }

    private suspend fun searchTeachers(): List<ProfileCandidate> {
        val url = BruScheduleDirectoryParser.TEACHERS_INDEX
        val html = transport.get(url)
        return parser.teacherCandidates(html, url)
    }

    private suspend fun discoverAllTeachersWithReport(): ScheduleCatalogDiscoveryResult {
        val url = BruScheduleDirectoryParser.TEACHERS_INDEX
        return try {
            val candidates = parser.teacherCandidates(transport.get(url), url)
            ScheduleCatalogDiscoveryResult(
                candidates = candidates,
                sources = listOf(
                    ScheduleCatalogSourceReport(
                        name = "Каталог преподавателей",
                        profilesDiscovered = candidates.size,
                        pagesLoaded = 1,
                        requestFailures = 0,
                    ),
                ),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            ScheduleCatalogDiscoveryResult(
                candidates = emptyList(),
                sources = listOf(
                    ScheduleCatalogSourceReport(
                        name = "Каталог преподавателей",
                        profilesDiscovered = 0,
                        pagesLoaded = 0,
                        requestFailures = 1,
                    ),
                ),
            )
        }
    }

    private suspend fun discoverAllGroupsWithReport(): ScheduleCatalogDiscoveryResult {
        val candidates = mutableListOf<ProfileCandidate>()
        val sources = mutableListOf<ScheduleCatalogSourceReport>()
        val roots = listOf(
            BruScheduleDirectoryParser.FULL_TIME_INDEX to "Очная форма",
            BruScheduleDirectoryParser.PART_TIME_INDEX to "Заочная форма",
        )
        for ((root, rootName) in roots) {
            val rootHtml = try {
                transport.get(root)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                sources += ScheduleCatalogSourceReport(rootName, 0, 0, 1)
                continue
            }
            val rootCandidates = parser.groupCandidates(rootHtml, root)
            candidates += rootCandidates
            sources += ScheduleCatalogSourceReport(rootName, rootCandidates.size, 1, 0)

            val faculties = parser.facultyDirectories(rootHtml, root, ScheduleProfileType.GROUP)
            for (faculty in faculties) {
                val html = try {
                    transport.get(faculty.url)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    sources += ScheduleCatalogSourceReport(faculty.name, 0, 0, 1)
                    continue
                }
                val facultyCandidates = parser.groupCandidates(html, faculty.url, faculty.name)
                candidates += facultyCandidates
                sources += ScheduleCatalogSourceReport(faculty.name, facultyCandidates.size, 1, 0)
            }
        }
        val uniqueCandidates = candidates.distinctBy { "${it.type}|${it.sourceUrl}|${it.sourceKey}" }
            .sortedWith(compareBy({ it.name.lowercase() }, { it.subtitle }, { it.sourceUrl }))
        return ScheduleCatalogDiscoveryResult(candidates = uniqueCandidates, sources = sources)
    }

    private suspend fun searchGroups(query: String): List<ProfileCandidate> {
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

            val rootMatches = parser.groupCandidates(rootHtml, root).filter { parser.matchesQuery(it, query) }
            val exactRootMatches = rootMatches.filter { parser.isExactMatch(it, query) }
            if (exactRootMatches.isNotEmpty()) return exactRootMatches
            results += rootMatches

            for (faculty in parser.facultyDirectories(rootHtml, root, ScheduleProfileType.GROUP)) {
                val facultyHtml = fetchOrNull(faculty.url) ?: continue
                val facultyMatches = parser.groupCandidates(facultyHtml, faculty.url, faculty.name).filter {
                    parser.matchesQuery(it, query)
                }
                val exactMatches = facultyMatches.filter { parser.isExactMatch(it, query) }
                if (exactMatches.isNotEmpty()) {
                    return exactMatches
                }
                results += facultyMatches
            }
        }
        if (results.isEmpty()) {
            lastNetworkError?.let { detail -> error("Не удалось загрузить каталог групп rasp.bru.by: $detail") }
        }
        return results
    }
}
