package com.example.bruschedule.data.remote

import com.example.bruschedule.data.scraper.BruScheduleDirectoryParser
import com.example.bruschedule.data.scraper.FacultyDirectory
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
        if (discovery.candidates.isEmpty() && discovery.sources.any { it.requestFailures > 0 || it.sourceUnavailable }) {
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
        } catch (failure: Exception) {
            ScheduleCatalogDiscoveryResult(
                candidates = emptyList(),
                sources = listOf(
                    ScheduleCatalogSourceReport(
                        name = "Каталог преподавателей",
                        profilesDiscovered = 0,
                        pagesLoaded = 0,
                        requestFailures = 1,
                        sourceUnavailable = true,
                        requestFailureDetails = listOf(failure.message ?: failure.toString()),
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
            } catch (failure: Exception) {
                sources += ScheduleCatalogSourceReport(
                    rootName,
                    0,
                    0,
                    1,
                    sourceUnavailable = true,
                    requestFailureDetails = listOf(failure.message ?: failure.toString()),
                )
                continue
            }
            val rootCandidates = parser.groupCandidates(rootHtml, root)
            candidates += rootCandidates
            sources += ScheduleCatalogSourceReport(rootName, rootCandidates.size, 1, 0)

            val visited = mutableSetOf(root)
            val pending = mutableListOf<GroupDirectoryWork>()
            pending += parser.facultyDirectories(rootHtml, root, ScheduleProfileType.GROUP).map { directory ->
                GroupDirectoryWork(directory, depth = 1, facultyName = directory.name)
            }
            var nextPage = 0
            var crawlLimitReached = false
            while (nextPage < pending.size && visited.size < MAX_GROUP_CATALOG_PAGES_PER_FORM) {
                val work = pending[nextPage++]
                if (!visited.add(work.directory.url)) continue
                val reportName = listOf(work.facultyName, work.directory.name)
                    .distinct()
                    .joinToString(" / ")
                val html = try {
                    transport.get(work.directory.url)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    sources += ScheduleCatalogSourceReport(
                        name = reportName,
                        profilesDiscovered = 0,
                        pagesLoaded = 0,
                        requestFailures = 1,
                        sourceUnavailable = true,
                        requestFailureDetails = listOf(failure.message ?: failure.toString()),
                    )
                    continue
                }
                val pageCandidates = parser.groupCandidates(
                    html = html,
                    facultyPageUrl = work.directory.url,
                    facultyNameHint = work.facultyName,
                )
                candidates += pageCandidates
                sources += ScheduleCatalogSourceReport(reportName, pageCandidates.size, 1, 0)

                val nestedDirectories = parser.facultyDirectories(
                    html = html,
                    pageUrl = work.directory.url,
                    type = ScheduleProfileType.GROUP,
                ).filter { directory -> directory.url !in visited }
                if (work.depth < MAX_GROUP_DIRECTORY_DEPTH) {
                    pending += nestedDirectories.map { directory ->
                        GroupDirectoryWork(directory, work.depth + 1, work.facultyName)
                    }
                } else if (nestedDirectories.isNotEmpty()) {
                    crawlLimitReached = true
                }
            }
            if (nextPage < pending.size && pending.drop(nextPage).any { it.directory.url !in visited }) {
                crawlLimitReached = true
            }
            if (crawlLimitReached) {
                sources += ScheduleCatalogSourceReport(
                    name = "$rootName · обход остановлен по лимиту",
                    profilesDiscovered = 0,
                    pagesLoaded = 0,
                    requestFailures = 0,
                    crawlTruncated = true,
                )
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

            val visited = mutableSetOf(root)
            val pending = mutableListOf<GroupDirectoryWork>()
            pending += parser.facultyDirectories(rootHtml, root, ScheduleProfileType.GROUP).map { directory ->
                GroupDirectoryWork(directory, depth = 1, facultyName = directory.name)
            }
            var nextPage = 0
            while (nextPage < pending.size && visited.size < MAX_GROUP_SEARCH_PAGES_PER_FORM) {
                val work = pending[nextPage++]
                if (!visited.add(work.directory.url)) continue
                val facultyHtml = fetchOrNull(work.directory.url) ?: continue
                val facultyMatches = parser.groupCandidates(
                    html = facultyHtml,
                    facultyPageUrl = work.directory.url,
                    facultyNameHint = work.facultyName,
                ).filter { parser.matchesQuery(it, query) }
                val exactMatches = facultyMatches.filter { parser.isExactMatch(it, query) }
                if (exactMatches.isNotEmpty()) return exactMatches
                results += facultyMatches

                if (work.depth < MAX_GROUP_SEARCH_DIRECTORY_DEPTH) {
                    pending += parser.facultyDirectories(
                        html = facultyHtml,
                        pageUrl = work.directory.url,
                        type = ScheduleProfileType.GROUP,
                    ).map { directory ->
                        GroupDirectoryWork(directory, work.depth + 1, work.facultyName)
                    }
                }
            }
        }
        if (results.isEmpty()) {
            lastNetworkError?.let { detail -> error("Не удалось загрузить каталог групп rasp.bru.by: $detail") }
        }
        return results
    }

    private data class GroupDirectoryWork(
        val directory: FacultyDirectory,
        val depth: Int,
        val facultyName: String,
    )

    private companion object {
        const val MAX_GROUP_DIRECTORY_DEPTH = 5
        const val MAX_GROUP_CATALOG_PAGES_PER_FORM = 500
        const val MAX_GROUP_SEARCH_DIRECTORY_DEPTH = 4
        const val MAX_GROUP_SEARCH_PAGES_PER_FORM = 200
    }
}
