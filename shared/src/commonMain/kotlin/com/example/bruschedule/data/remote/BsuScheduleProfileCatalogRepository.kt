package com.example.bruschedule.data.remote

import com.example.bruschedule.data.scraper.BsuBiologyGroup
import com.example.bruschedule.data.scraper.BsuBiologyScheduleHtmlParser
import com.example.bruschedule.data.scraper.BsuScheduleHtmlParser
import com.example.bruschedule.data.scraper.BsuTeacherNameMatcher
import com.example.bruschedule.data.scraper.BsuTeacherProfileSourceKey
import com.example.bruschedule.data.scraper.ScheduleDocumentFormat
import com.example.bruschedule.data.scraper.ScheduleDocumentHtmlAdapter
import com.example.bruschedule.domain.model.ProfileCandidate
import com.example.bruschedule.domain.model.ScheduleProfileType
import com.example.bruschedule.domain.model.UniversityIds
import com.example.bruschedule.domain.repository.ScheduleProfileCatalogRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import com.fleeksoft.ksoup.Ksoup
import kotlin.time.Clock
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.TimeZone
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.minus
import kotlinx.datetime.toLocalDateTime

class BsuScheduleProfileCatalogRepository(
    private val transport: HtmlTransport,
    private val parser: BsuScheduleHtmlParser = BsuScheduleHtmlParser(),
    private val clock: Clock = Clock.System,
    private val biologyParser: BsuBiologyScheduleHtmlParser = BsuBiologyScheduleHtmlParser(),
    private val documentAdapter: ScheduleDocumentHtmlAdapter = ScheduleDocumentHtmlAdapter(),
) : ScheduleProfileCatalogRepository {
    internal val configuredFacultyNames: Set<String> get() = FACULTIES.map(FacultySource::name).toSet()
    internal val configuredFacultySourceUrls: Set<String> get() = FACULTIES.map(FacultySource::url).toSet()

    override suspend fun search(type: ScheduleProfileType, query: String): List<ProfileCandidate> =
        search(UniversityIds.BSU, type, query)

    override suspend fun search(
        universityId: String,
        type: ScheduleProfileType,
        query: String,
    ): List<ProfileCandidate> = search(universityId, type, query) { }

    override suspend fun search(
        universityId: String,
        type: ScheduleProfileType,
        query: String,
        onPartialResults: (List<ProfileCandidate>) -> Unit,
    ): List<ProfileCandidate> {
        require(universityId == UniversityIds.BSU) { "Каталог расписаний БГУ не подходит выбранному вузу" }
        val normalizedQuery = normalize(query)
        val singleSymbolBiologyQuery =
            type == ScheduleProfileType.GROUP && normalizedQuery.length == BIOLOGY_SINGLE_SYMBOL_QUERY_LENGTH
        require(normalizedQuery.length >= MIN_QUERY_LENGTH || singleSymbolBiologyQuery) {
            "Введите не менее двух символов для поиска преподавателя или группы; для Биофака допускается односимвольный код группы"
        }

        val facultiesToSearch = if (singleSymbolBiologyQuery) {
            FACULTIES.filter { it.url == BIOLOGY_ROOT }
        } else {
            FACULTIES
        }
        val progressLock = Mutex()
        val partialByFaculty = linkedMapOf<String, FacultyResult>()
        val results = supervisorScope {
            facultiesToSearch.map { faculty ->
                async(start = CoroutineStart.UNDISPATCHED) {
                    suspend fun publish(partial: FacultyResult) {
                        progressLock.withLock {
                            partialByFaculty[faculty.url] = partial
                            onPartialResults(sortedCandidates(partialByFaculty.values.toList(), type, normalizedQuery))
                        }
                    }

                    val result = try {
                        discoverFaculty(faculty, type, normalizedQuery, ::publish)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        FacultyResult(candidates = emptyList(), pagesLoaded = 0, requestFailures = 1)
                    }
                    publish(result)
                    result
                }
            }.awaitAll()
        }
        if (singleSymbolBiologyQuery && results.none { it.candidates.isNotEmpty() } &&
            results.any(FacultyResult::sourceUnavailable)
        ) {
            error("Не удалось получить список групп Биофака. Попробуйте позже; сохранённые данные не изменены")
        }
        val successfulPages = results.sumOf { it.pagesLoaded }
        if (successfulPages == 0 && results.any { it.requestFailures > 0 }) {
            error("Не удалось открыть сайты факультетов БГУ. Проверьте подключение и попробуйте ещё раз")
        }
        return sortedCandidates(results, type, normalizedQuery)
    }

    /** Uses a deeper crawl than interactive search so the manual dataset export can enumerate all linked schedules. */
    suspend fun discoverAll(type: ScheduleProfileType): List<ProfileCandidate> {
        val discovery = discoverAllWithReport(type)
        if (discovery.sources.sumOf { it.pagesLoaded } == 0 &&
            discovery.sources.any { it.requestFailures > 0 }
        ) {
            error("Не удалось открыть сайты факультетов БГУ при сборке GitHub-каталога")
        }
        return discovery.candidates
    }

    /** Returns per-faculty crawl statistics so a manual import can distinguish omissions from empty catalogues. */
    suspend fun discoverAllWithReport(type: ScheduleProfileType): ScheduleCatalogDiscoveryResult {
        val results = supervisorScope {
            val facultySemaphore = Semaphore(MAX_IMPORT_FACULTY_CRAWLS_IN_FLIGHT)
            FACULTIES.map { faculty ->
                async(start = CoroutineStart.UNDISPATCHED) {
                    facultySemaphore.withPermit {
                        try {
                            discoverFaculty(
                                faculty = faculty,
                                type = type,
                                normalizedQuery = "",
                                onProgress = { },
                                exhaustive = true,
                            )
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (failure: Exception) {
                            FacultyResult(
                                candidates = emptyList(),
                                pagesLoaded = 0,
                                requestFailures = 1,
                                sourceUnavailable = true,
                                requestFailureDetails = listOf(failure.message ?: failure.toString()),
                            )
                        }
                    }
                }
            }.awaitAll()
        }
        val candidates = sortedCandidates(results, type, normalizedQuery = "")
        val sources = FACULTIES.zip(results).map { (faculty, result) ->
            ScheduleCatalogSourceReport(
                name = faculty.name,
                profilesDiscovered = sortedCandidates(listOf(result), type, normalizedQuery = "").size,
                pagesLoaded = result.pagesLoaded,
                requestFailures = result.requestFailures,
                sourceUnavailable = result.sourceUnavailable,
                crawlTruncated = result.crawlTruncated,
                requestFailureDetails = result.requestFailureDetails,
            )
        }
        return ScheduleCatalogDiscoveryResult(candidates = candidates, sources = sources)
    }

    private fun sortedCandidates(
        results: List<FacultyResult>,
        type: ScheduleProfileType,
        normalizedQuery: String,
    ): List<ProfileCandidate> {
        val candidates = results.flatMap { it.candidates }
        val prepared = if (type == ScheduleProfileType.TEACHER) {
            val groups = mutableListOf<MutableList<ProfileCandidate>>()
            candidates.forEach { candidate ->
                val facultyName = normalize(candidate.subtitle.substringBefore(" · "))
                val matchingGroup = groups.firstOrNull { group ->
                    val first = group.first()
                    normalize(first.subtitle.substringBefore(" · ")) == facultyName &&
                        BsuTeacherNameMatcher.matches(first.name, candidate.name)
                }
                if (matchingGroup == null) groups += mutableListOf(candidate) else matchingGroup += candidate
            }
            groups.map { duplicates ->
                val first = duplicates.first()
                val urls = duplicates.map(ProfileCandidate::sourceUrl).distinct()
                first.copy(
                    sourceUrl = urls.first(),
                    sourceKey = BsuTeacherProfileSourceKey.encode(
                        teacherName = first.name,
                        urls = urls,
                        facultyName = first.subtitle.substringBefore(" ·"),
                    ),
                    subtitle = "${first.subtitle.substringBefore(" · ")} · расписаний: ${urls.size}",
                )
            }
        } else {
            candidates.distinctBy { "${it.sourceUrl}|${it.sourceKey}|${it.name}" }
        }
        return prepared.sortedWith(
            compareBy<ProfileCandidate> { normalize(it.name) != normalizedQuery }
                .thenBy { normalize(it.name) }
                .thenBy { it.subtitle.orEmpty() },
        )
    }

    private suspend fun discoverFaculty(
        faculty: FacultySource,
        type: ScheduleProfileType,
        normalizedQuery: String,
        onProgress: suspend (FacultyResult) -> Unit,
        exhaustive: Boolean = false,
    ): FacultyResult {
        if (faculty.url == BIOLOGY_ROOT) {
            return if (type == ScheduleProfileType.TEACHER) {
                discoverBiologyTeachers(faculty, normalizedQuery, onProgress)
            } else {
                discoverBiologyFaculty(faculty, normalizedQuery, onProgress)
            }
        }
        val candidates = mutableListOf<ProfileCandidate>()
        val visited = mutableSetOf<String>()
        val scheduledPages = mutableSetOf<String>()
        val requestFailureDetails = mutableListOf<String>()
        var pagesLoaded = 0
        var requestFailures = 0
        var crawlTruncated = false

        fun recordRequestFailure(detail: String?) {
            requestFailures++
            if (!detail.isNullOrBlank() && requestFailureDetails.size < MAX_FAILURE_DETAIL_SAMPLES) {
                requestFailureDetails += detail.trim().take(MAX_FAILURE_DETAIL_LENGTH)
            }
        }

        suspend fun fetch(url: String): String? = try {
            transport.get(url).also { pagesLoaded++ }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            recordRequestFailure(failure.message ?: failure.toString())
            null
        }

        suspend fun inspectPage(
            pageUrl: String,
            html: String,
            depth: Int,
            inheritedGroupLabels: List<String> = emptyList(),
            sourceKind: String = "HTML",
        ) {
            val pageLimit = when {
                exhaustive -> MAX_IMPORT_PAGES_PER_FACULTY
                type == ScheduleProfileType.TEACHER -> MAX_TEACHER_PAGES_PER_FACULTY
                else -> MAX_PAGES_PER_FACULTY
            }
            if (pageUrl in visited) return
            if (pageUrl !in scheduledPages && scheduledPages.size >= pageLimit) {
                crawlTruncated = true
                return
            }
            visited += pageUrl
            scheduledPages += pageUrl

            val discoveredLabels = (parser.discoverTableGroupLabels(html) + inheritedGroupLabels)
                .distinctBy(::normalize)
            if (isTimetablePage(html)) {
                when (type) {
                    ScheduleProfileType.GROUP -> discoveredLabels.filter { matchesQuery(it, normalizedQuery) }.forEach { group ->
                        candidates += candidate(faculty, group, pageUrl, sourceKind)
                    }
                    ScheduleProfileType.TEACHER -> parser.discoverTeacherNames(faculty.name, html)
                        .filter { matchesQuery(it, normalizedQuery) }
                        .forEach { teacher ->
                            candidates += candidate(
                                faculty = faculty,
                                group = teacher,
                                sourceUrl = pageUrl,
                                sourceKind = sourceKind,
                                type = ScheduleProfileType.TEACHER,
                                sourceKey = teacher,
                                detail = discoveredLabels.take(3).takeIf { it.isNotEmpty() }
                                    ?.joinToString(", ")?.let { "группы $it" },
                            )
                        }
                }
            }
            onProgress(
                FacultyResult(
                    candidates = candidates.toList(),
                    pagesLoaded = pagesLoaded,
                    requestFailures = requestFailures,
                    crawlTruncated = crawlTruncated,
                    requestFailureDetails = requestFailureDetails.toList(),
                ),
            )
            val maxLinkDepth = when {
                exhaustive -> MAX_IMPORT_LINK_DEPTH
                type == ScheduleProfileType.TEACHER -> MAX_TEACHER_LINK_DEPTH
                else -> MAX_LINK_DEPTH
            }
            if (pageUrl.contains("docs.google.com/spreadsheets/")) return

            val document = Ksoup.parse(html)
            val linkSources = document.select("a[href]").toList().map { element ->
                val labels = listOf(
                    element.text(),
                    element.attr("title"),
                    element.attr("aria-label"),
                    element.selectFirst("img")?.attr("alt").orEmpty(),
                ).filter(String::isNotBlank)
                LinkSource(element.attr("href"), labels.joinToString(" "))
            } + document.select("iframe[src]").toList().map { element ->
                LinkSource(element.attr("src"), "embedded timetable")
            }
            val eligibleLinks = linkSources.mapNotNull { source ->
                val href = source.url.trim()
                if (href.isBlank() || href.startsWith("#") || href.startsWith("mailto:") ||
                    href.startsWith("javascript:")
                ) return@mapNotNull null
                val resolvedTarget = resolveUrl(pageUrl, href) ?: return@mapNotNull null
                val target = resolvedTarget.secureBsuUrl()
                if (!target.startsWith("https://", ignoreCase = true)) return@mapNotNull null
                val labelGroups = parser.discoverGroupLabelsFromText(source.label)
                val isSheet = GoogleSheetsScheduleUrl.toHtmlTableUrl(target) != null
                val documentFormat = documentAdapter.formatForUrl(target)
                val isDocument = documentFormat != null
                if (!isSheet && !isDocument && target in scheduledPages) return@mapNotNull null
                if (!isSheet && !isDocument &&
                    (!isHtmlPage(target) ||
                        (!sameHost(pageUrl, target) && !isBsuHost(target) && !isDropboxHost(target)))
                ) return@mapNotNull null
                val routeText = "${source.label} $target".lowercase()
                val routeLooksRelevant = ROUTE_HINTS.any { hint -> routeText.contains(hint) }
                val unlabelledOfficialHtml = isBsuHost(target) && isOfficialHtmlPage(target) && !isDocument
                if (!isSheet && labelGroups.isEmpty() && !routeLooksRelevant && !unlabelledOfficialHtml) {
                    return@mapNotNull null
                }
                LinkTarget(target, labelGroups, isSheet, documentFormat, routeLooksRelevant)
            }
                .distinctBy(LinkTarget::url)
                .sortedWith(
                    compareBy<LinkTarget> { !matchesQuery(it.groupLabels.joinToString(" "), normalizedQuery) }
                        .thenBy { !it.isSheet }
                        .thenBy { !it.looksRelevant },
                )
            val maxLinksPerPage = when {
                exhaustive -> MAX_IMPORT_LINKS_PER_PAGE
                type == ScheduleProfileType.TEACHER -> MAX_TEACHER_LINKS_PER_PAGE
                else -> MAX_LINKS_PER_PAGE
            }
            if (eligibleLinks.size > maxLinksPerPage) crawlTruncated = true
            if (depth >= maxLinkDepth) {
                if (eligibleLinks.isNotEmpty()) crawlTruncated = true
                return
            }
            val links = eligibleLinks.take(maxLinksPerPage).filter { link ->
                if (link.isSheet || link.documentFormat != null) {
                    true
                } else if (link.url in scheduledPages) {
                    false
                } else if (scheduledPages.size < pageLimit) {
                    scheduledPages += link.url
                    true
                } else {
                    crawlTruncated = true
                    false
                }
            }

            suspend fun fetchLink(link: LinkTarget): LinkFetchResult = try {
                val documentFormat = link.documentFormat
                val html = when {
                    documentFormat != null -> {
                        if (!documentFormat.isSupported) {
                            return LinkFetchResult(
                                link,
                                html = null,
                                failed = true,
                                failureDetail = "Формат документа ${documentFormat.displayName} не поддерживается",
                            )
                        }
                        val binaryTransport = transport as? BinaryResourceTransport
                            ?: return LinkFetchResult(
                                link,
                                html = null,
                                failed = true,
                                failureDetail = "Транспорт не поддерживает загрузку документов",
                            )
                        val bytes = binaryTransport.getBytes(documentAdapter.downloadUrl(link.url))
                        documentAdapter.toHtml(documentFormat, bytes)
                    }
                    link.isSheet -> {
                        val htmlUrl = GoogleSheetsScheduleUrl.toHtmlTableUrl(link.url)
                            ?: return LinkFetchResult(
                                link,
                                html = null,
                                failed = true,
                                failureDetail = "Не удалось преобразовать ссылку Google Sheets",
                            )
                        transport.get(htmlUrl)
                    }
                    else -> transport.get(link.url)
                }
                LinkFetchResult(link, html = html, failed = false)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                LinkFetchResult(
                    link,
                    html = null,
                    failed = true,
                    failureDetail = failure.message ?: failure.toString(),
                )
            }

            val linkResponses = supervisorScope {
                val semaphore = Semaphore(MAX_LINK_REQUESTS_IN_FLIGHT_PER_FACULTY)
                links.map { link ->
                    async(start = CoroutineStart.UNDISPATCHED) {
                        semaphore.withPermit { fetchLink(link) }
                    }
                }.awaitAll()
            }
            linkResponses.forEach { response ->
                val link = response.link
                val html = response.html
                if (response.failed || html == null) {
                    recordRequestFailure(response.failureDetail)
                    return@forEach
                }
                pagesLoaded++
                val documentFormat = link.documentFormat
                when {
                    documentFormat != null -> {
                        val documentLabels = (parser.discoverTableGroupLabels(html) + link.groupLabels)
                            .distinctBy(::normalize)
                        if (isTimetablePage(html)) {
                            if (type == ScheduleProfileType.GROUP) {
                                documentLabels.filter { matchesQuery(it, normalizedQuery) }.forEach { group ->
                                    candidates += candidate(faculty, group, link.url, documentFormat.displayName)
                                }
                            } else {
                                parser.discoverTeacherNames(faculty.name, html).filter { matchesQuery(it, normalizedQuery) }
                                    .forEach { teacher ->
                                        candidates += candidate(
                                            faculty = faculty,
                                            group = teacher,
                                            sourceUrl = link.url,
                                            sourceKind = documentFormat.displayName,
                                            type = ScheduleProfileType.TEACHER,
                                            sourceKey = teacher,
                                            detail = documentLabels.take(3).takeIf { it.isNotEmpty() }
                                                ?.joinToString(", ")?.let { "группы $it" },
                                        )
                                    }
                            }
                        }
                    }
                    link.isSheet -> {
                        val sheetLabels = (parser.discoverTableGroupLabels(html) + link.groupLabels)
                            .distinctBy(::normalize)
                        if (isTimetablePage(html)) {
                            if (type == ScheduleProfileType.GROUP) {
                                sheetLabels.filter { matchesQuery(it, normalizedQuery) }.forEach { group ->
                                    candidates += candidate(faculty, group, link.url, "Google Sheets")
                                }
                            } else {
                                parser.discoverTeacherNames(faculty.name, html).filter { matchesQuery(it, normalizedQuery) }
                                    .forEach { teacher ->
                                        candidates += candidate(
                                            faculty = faculty,
                                            group = teacher,
                                            sourceUrl = link.url,
                                            sourceKind = "Google Sheets",
                                            type = ScheduleProfileType.TEACHER,
                                            sourceKey = teacher,
                                            detail = sheetLabels.take(3).takeIf { it.isNotEmpty() }
                                                ?.joinToString(", ")?.let { "группы $it" },
                                        )
                                    }
                            }
                        }
                    }
                    else -> inspectPage(
                        pageUrl = link.url,
                        html = html,
                        depth = depth + 1,
                        inheritedGroupLabels = link.groupLabels,
                    )
                }
            }
            onProgress(
                FacultyResult(
                    candidates = candidates.toList(),
                    pagesLoaded = pagesLoaded,
                    requestFailures = requestFailures,
                    crawlTruncated = crawlTruncated,
                    requestFailureDetails = requestFailureDetails.toList(),
                ),
            )
        }

        val rootHtml = fetch(faculty.url)
            ?: return FacultyResult(
                candidates,
                pagesLoaded,
                requestFailures,
                sourceUnavailable = true,
                crawlTruncated = crawlTruncated,
                requestFailureDetails = requestFailureDetails.toList(),
            )
        inspectPage(faculty.url, rootHtml, depth = 0)
        return FacultyResult(
            candidates,
            pagesLoaded,
            requestFailures,
            crawlTruncated = crawlTruncated,
            requestFailureDetails = requestFailureDetails.toList(),
        )
    }

    private fun biologyIndexRequests(root: String, weekDate: String): List<BiologyRequest> =
        BIOLOGY_STUDY_MODES.flatMap { studyMode ->
            (1..BIOLOGY_MAX_COURSES).map { course ->
                val url = if (root == BIOLOGY_ROOT) {
                    "$root?study_mode=${encodeQueryComponent(studyMode.label)}&course=$course&week_date=$weekDate"
                } else {
                    "$root?study=${studyMode.legacyCode}&course=$course&date=$weekDate"
                }
                BiologyRequest(
                    studyMode = studyMode,
                    course = course,
                    url = url,
                    sourceRoot = root,
                )
            }
        }

    private fun biologyGroupScheduleUrl(request: BiologyRequest, groupId: String, weekDate: String): String =
        if (request.sourceRoot == BIOLOGY_ROOT) {
            "$BIOLOGY_ROOT?study_mode=${encodeQueryComponent(request.studyMode.label)}" +
                "&course=${request.course}&group=${encodeQueryComponent(groupId)}&week_date=$weekDate"
        } else {
            "${request.sourceRoot}?study=${request.studyMode.legacyCode}&course=${request.course}" +
                "&group=${encodeQueryComponent(groupId)}&date=$weekDate"
        }

    private suspend fun fetchBiologyIndexPages(requests: List<BiologyRequest>): List<BiologyPageResult> =
        supervisorScope {
            val semaphore = Semaphore(MAX_BIOLOGY_REQUESTS_IN_FLIGHT)
            requests.map { request ->
                async(start = CoroutineStart.UNDISPATCHED) {
                    semaphore.withPermit {
                        try {
                            BiologyPageResult(request, transport.get(request.url), failed = false)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (failure: Exception) {
                            BiologyPageResult(
                                request,
                                html = null,
                                failed = true,
                                failureDetail = failure.message ?: failure.toString(),
                            )
                        }
                    }
                }
            }.awaitAll()
        }

    private suspend fun discoverBiologyFaculty(
        faculty: FacultySource,
        normalizedQuery: String,
        onProgress: suspend (FacultyResult) -> Unit,
    ): FacultyResult {
        transport.primeBiologySession(BIOLOGY_ROOT)
        val today = clock.now().toLocalDateTime(TimeZone.currentSystemDefault()).date
        val weekDate = today.minus(DatePeriod(days = today.dayOfWeek.isoDayNumber - 1)).toString()
        val officialRequests = biologyIndexRequests(BIOLOGY_ROOT, weekDate)
        val officialResponses = mutableListOf<BiologyPageResult>()
        val candidates = mutableListOf<ProfileCandidate>()
        var hasGroupCatalogue = false

        suspend fun collectCandidates(response: BiologyPageResult, reportResponses: List<BiologyPageResult>) {
            val html = response.html ?: return
            val groups = biologyParser.discoverGroups(html)
            if (groups.isNotEmpty()) hasGroupCatalogue = true
            val matches = groups.mapNotNull { group ->
                val mode = response.request.studyMode
                val searchable = "${group.name} ${group.programme} ${mode.label} ${response.request.course}"
                if (!matchesBiologyGroup(group, searchable, normalizedQuery)) return@mapNotNull null
                candidate(
                    faculty = faculty,
                    group = group.name,
                    sourceUrl = biologyGroupScheduleUrl(response.request, group.id, weekDate),
                    sourceKind = if (response.request.sourceRoot == BIOLOGY_ROOT) "HTML" else "HTML (legacy fallback)",
                    sourceKey = "${BsuBiologyScheduleHtmlParser.BIO_GROUP_NUMBER_SOURCE_KEY_PREFIX}${group.id}",
                    detail = listOfNotNull(
                        mode.label,
                        "${response.request.course} курс",
                        group.programme.takeIf(String::isNotBlank),
                    ).joinToString(" · "),
                )
            }
            if (matches.isNotEmpty()) {
                candidates += matches
                onProgress(
                    FacultyResult(
                        candidates = candidates.toList(),
                        pagesLoaded = reportResponses.count { it.html != null },
                        requestFailures = reportResponses.count(BiologyPageResult::failed),
                        requestFailureDetails = reportResponses.mapNotNull(BiologyPageResult::failureDetail)
                            .take(MAX_FAILURE_DETAIL_SAMPLES),
                        sourceUnavailable = !hasGroupCatalogue,
                    ),
                )
            }
        }

        supervisorScope {
            val completedPages = Channel<BiologyPageResult>(capacity = officialRequests.size)
            val semaphore = Semaphore(MAX_BIOLOGY_REQUESTS_IN_FLIGHT)
            val workers = officialRequests.map { request ->
                launch(start = CoroutineStart.UNDISPATCHED) {
                    val response = semaphore.withPermit {
                        try {
                            BiologyPageResult(request, transport.get(request.url), failed = false)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (failure: Exception) {
                            BiologyPageResult(
                                request,
                                html = null,
                                failed = true,
                                failureDetail = failure.message ?: failure.toString(),
                            )
                        }
                    }
                    completedPages.send(response)
                }
            }
            repeat(officialRequests.size) {
                val response = completedPages.receive()
                officialResponses += response
                collectCandidates(response, officialResponses)
            }
            workers.joinAll()
            completedPages.close()
        }

        val legacyResponses = if (hasGroupCatalogue) {
            emptyList()
        } else {
            transport.primeBiologySession(LEGACY_BIOLOGY_ROOT)
            val fetchedPages = fetchBiologyIndexPages(biologyIndexRequests(LEGACY_BIOLOGY_ROOT, weekDate))
            val reportResponses = officialResponses + fetchedPages
            fetchedPages.forEach { response -> collectCandidates(response, reportResponses) }
            fetchedPages
        }
        val reportResponses = officialResponses + legacyResponses
        return FacultyResult(
            candidates = candidates,
            pagesLoaded = reportResponses.count { it.html != null },
            requestFailures = reportResponses.count(BiologyPageResult::failed),
            requestFailureDetails = reportResponses.mapNotNull(BiologyPageResult::failureDetail)
                .take(MAX_FAILURE_DETAIL_SAMPLES),
            sourceUnavailable = !hasGroupCatalogue,
        )
    }

    private suspend fun discoverBiologyTeachers(
        faculty: FacultySource,
        normalizedQuery: String,
        onProgress: suspend (FacultyResult) -> Unit,
    ): FacultyResult {
        transport.primeBiologySession(BIOLOGY_ROOT)
        val today = clock.now().toLocalDateTime(TimeZone.currentSystemDefault()).date
        val weekDate = today.minus(DatePeriod(days = today.dayOfWeek.isoDayNumber - 1)).toString()
        val officialIndexResponses = fetchBiologyIndexPages(biologyIndexRequests(BIOLOGY_ROOT, weekDate))
        val officialHasGroupCatalogue = officialIndexResponses.any { response ->
            response.html?.let { biologyParser.discoverGroups(it).isNotEmpty() } == true
        }
        val legacyIndexResponses = if (officialHasGroupCatalogue) {
            emptyList()
        } else {
            transport.primeBiologySession(LEGACY_BIOLOGY_ROOT)
            fetchBiologyIndexPages(biologyIndexRequests(LEGACY_BIOLOGY_ROOT, weekDate))
        }
        val catalogueIndexResponses = if (officialHasGroupCatalogue) officialIndexResponses else legacyIndexResponses
        val reportIndexResponses = officialIndexResponses + legacyIndexResponses
        val allTeacherRequests = catalogueIndexResponses.flatMap { response ->
            val html = response.html ?: return@flatMap emptyList()
            biologyParser.discoverGroups(html).map { group ->
                BiologyTeacherRequest(
                    studyMode = response.request.studyMode,
                    course = response.request.course,
                    group = group,
                    url = biologyGroupScheduleUrl(response.request, group.id, weekDate),
                )
            }
        }.distinctBy(BiologyTeacherRequest::url)
        val crawlTruncated = allTeacherRequests.size > MAX_BIOLOGY_TEACHER_GROUP_PAGES
        val teacherRequests = allTeacherRequests.take(MAX_BIOLOGY_TEACHER_GROUP_PAGES)

        val timetableResponses = supervisorScope {
            val semaphore = Semaphore(MAX_BIOLOGY_REQUESTS_IN_FLIGHT)
            teacherRequests.map { request ->
                async(start = CoroutineStart.UNDISPATCHED) {
                    semaphore.withPermit {
                        try {
                            BiologyTeacherPageResult(request, transport.get(request.url), failed = false)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (failure: Exception) {
                            BiologyTeacherPageResult(
                                request,
                                html = null,
                                failed = true,
                                failureDetail = failure.message ?: failure.toString(),
                            )
                        }
                    }
                }
            }.awaitAll()
        }
        val candidates = mutableListOf<ProfileCandidate>()
        timetableResponses.forEach { response ->
            val html = response.html
            if (html != null) {
                biologyParser.discoverTeacherNames(html)
                    .filter { matchesQuery(it, normalizedQuery) }
                    .forEach { teacher ->
                        candidates += candidate(
                            faculty = faculty,
                            group = teacher,
                            sourceUrl = response.request.url,
                            sourceKind = if (response.request.url.startsWith(LEGACY_BIOLOGY_ROOT)) {
                                "HTML (legacy fallback)"
                            } else {
                                "HTML"
                            },
                            type = ScheduleProfileType.TEACHER,
                            sourceKey = teacher,
                            detail = listOf(
                                response.request.studyMode.label,
                                "${response.request.course} курс",
                                response.request.group.name,
                            ).joinToString(" · "),
                        )
                    }
            }
            onProgress(
                FacultyResult(
                    candidates = candidates.toList(),
                    pagesLoaded = reportIndexResponses.count { it.html != null } +
                        timetableResponses.count { it.html != null },
                    requestFailures = reportIndexResponses.count(BiologyPageResult::failed) +
                        timetableResponses.count(BiologyTeacherPageResult::failed),
                    requestFailureDetails = (
                        reportIndexResponses.mapNotNull(BiologyPageResult::failureDetail) +
                            timetableResponses.mapNotNull(BiologyTeacherPageResult::failureDetail)
                        ).take(MAX_FAILURE_DETAIL_SAMPLES),
                    sourceUnavailable = teacherRequests.isEmpty(),
                    crawlTruncated = crawlTruncated,
                ),
            )
        }
        return FacultyResult(
            candidates = candidates,
            pagesLoaded = reportIndexResponses.count { it.html != null } +
                timetableResponses.count { it.html != null },
            requestFailures = reportIndexResponses.count(BiologyPageResult::failed) +
                timetableResponses.count(BiologyTeacherPageResult::failed),
            requestFailureDetails = (
                reportIndexResponses.mapNotNull(BiologyPageResult::failureDetail) +
                    timetableResponses.mapNotNull(BiologyTeacherPageResult::failureDetail)
            ).take(MAX_FAILURE_DETAIL_SAMPLES),
            sourceUnavailable = teacherRequests.isEmpty(),
            crawlTruncated = crawlTruncated,
        )
    }

    private fun candidate(
        faculty: FacultySource,
        group: String,
        sourceUrl: String,
        sourceKind: String,
        type: ScheduleProfileType = ScheduleProfileType.GROUP,
        sourceKey: String = group,
        detail: String? = null,
    ) = ProfileCandidate(
        type = type,
        name = group,
        sourceUrl = sourceUrl,
        sourceKey = sourceKey,
        subtitle = listOfNotNull(faculty.name, detail, sourceKind).joinToString(" · "),
        universityId = UniversityIds.BSU,
    )

    private fun isTimetablePage(html: String): Boolean {
        val text = Ksoup.parse(html).text().lowercase().replace('ё', 'е')
        val hasTime = TIME_RANGE.containsMatchIn(text)
        val hasDayOrDate = DAY_OR_DATE_HINTS.any { hint -> text.contains(hint) } ||
            ABBREVIATED_WEEKDAY.containsMatchIn(text) || EXPLICIT_SCHEDULE_DATE.containsMatchIn(text)
        return hasTime && hasDayOrDate
    }

    private fun isHtmlPage(url: String): Boolean {
        val path = url.substringBefore('?').substringBefore('#').lowercase()
        return !FILE_EXTENSIONS.any { extension -> path.endsWith(extension) }
    }

    private fun isOfficialHtmlPage(url: String): Boolean {
        val fileName = url.substringBefore('?').substringBefore('#').substringAfterLast('/')
        val extension = fileName.substringAfterLast('.', missingDelimiterValue = "").lowercase()
        return extension.isBlank() || extension in OFFICIAL_HTML_EXTENSIONS
    }

    private fun sameHost(first: String, second: String): Boolean =
        host(first).equals(host(second), ignoreCase = true)

    private fun isDropboxHost(url: String): Boolean {
        val host = host(url)
        return host.equals("dropbox.com", ignoreCase = true) || host.endsWith(".dropbox.com", ignoreCase = true)
    }

    private fun isBsuHost(url: String): Boolean {
        val host = host(url)
        return host.equals("bsu.by", ignoreCase = true) || host.endsWith(".bsu.by", ignoreCase = true)
    }

    private fun String.secureBsuUrl(): String =
        if (startsWith("http://", ignoreCase = true) && isBsuHost(this)) "https://${substringAfter("://")}"
        else this

    private fun host(url: String): String = url.substringAfter("://", "").substringBefore('/').substringBefore(':')

    private fun resolveUrl(baseUrl: String, href: String): String? {
        if (href.startsWith("https://", ignoreCase = true) || href.startsWith("http://", ignoreCase = true)) {
            return href
        }
        val scheme = baseUrl.substringBefore("://", "https")
        val authority = baseUrl.substringAfter("://", "").substringBefore('/')
        if (authority.isBlank()) return null
        val targetPath = when {
            href.startsWith("//") -> "$scheme:$href"
            href.startsWith('/') -> href
            else -> {
                val basePath = baseUrl.substringAfter("://", "").substringAfter('/', "")
                    .substringBefore('?').substringBefore('#').substringBeforeLast('/', "")
                "/${if (basePath.isBlank()) "" else "$basePath/"}$href"
            }
        }
        if (targetPath.startsWith("http://") || targetPath.startsWith("https://")) return targetPath
        val pathOnly = targetPath.substringBefore('?').substringBefore('#')
        val suffix = targetPath.removePrefix(pathOnly)
        val hasTrailingSlash = pathOnly.endsWith('/')
        val stack = mutableListOf<String>()
        pathOnly.split('/').forEach { part ->
            when (part) {
                "", "." -> Unit
                ".." -> if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex)
                else -> stack += part
            }
        }
        val normalizedPath = stack.joinToString("/")
        val trailingSlash = if (hasTrailingSlash && normalizedPath.isNotEmpty()) "/" else ""
        return "$scheme://$authority/$normalizedPath$trailingSlash$suffix"
    }

    private fun matchesBiologyGroup(
        group: BsuBiologyGroup,
        searchable: String,
        normalizedQuery: String,
    ): Boolean {
        if (normalizedQuery.length != BIOLOGY_SINGLE_SYMBOL_QUERY_LENGTH) {
            return matchesQuery(searchable, normalizedQuery)
        }
        val normalizedNumber = normalize(group.number)
        val visibleCode = when {
            normalizedNumber.length == BIOLOGY_SINGLE_SYMBOL_QUERY_LENGTH -> normalizedNumber
            else -> BIOLOGY_GROUP_LABEL_CODE.find(group.number)?.groupValues?.getOrNull(1)?.let(::normalize)
        }
        return visibleCode == normalizedQuery ||
            visibleCode == null && normalize(group.id) == normalizedQuery
    }

    private fun matchesQuery(value: String, normalizedQuery: String): Boolean =
        normalize(value).contains(normalizedQuery)

    private fun encodeQueryComponent(value: String): String = buildString {
        val hex = "0123456789ABCDEF"
        value.encodeToByteArray().forEach { byte ->
            val code = byte.toInt() and 0xff
            val safe = code in 'a'.code..'z'.code || code in 'A'.code..'Z'.code ||
                code in '0'.code..'9'.code || code == '-'.code || code == '_'.code ||
                code == '.'.code || code == '~'.code
            if (safe) {
                append(code.toChar())
            } else {
                append('%').append(hex[code shr 4]).append(hex[code and 0x0f])
            }
        }
    }

    private fun normalize(value: String): String = value.lowercase()
        .replace('ё', 'е')
        .replace('а', 'a')
        .replace('б', 'b')
        .filter(Char::isLetterOrDigit)

    private data class LinkSource(val url: String, val label: String)

    private data class LinkTarget(
        val url: String,
        val groupLabels: List<String>,
        val isSheet: Boolean,
        val documentFormat: ScheduleDocumentFormat?,
        val looksRelevant: Boolean,
    )

    private data class LinkFetchResult(
        val link: LinkTarget,
        val html: String?,
        val failed: Boolean,
        val failureDetail: String? = null,
    )

    private data class FacultyResult(
        val candidates: List<ProfileCandidate>,
        val pagesLoaded: Int,
        val requestFailures: Int,
        val sourceUnavailable: Boolean = false,
        val crawlTruncated: Boolean = false,
        val requestFailureDetails: List<String> = emptyList(),
    )

    private data class BiologyStudyMode(val label: String, val legacyCode: String)

    private data class BiologyRequest(
        val studyMode: BiologyStudyMode,
        val course: Int,
        val url: String,
        val sourceRoot: String,
    )

    private data class BiologyPageResult(
        val request: BiologyRequest,
        val html: String?,
        val failed: Boolean,
        val failureDetail: String? = null,
    )

    private data class BiologyTeacherRequest(
        val studyMode: BiologyStudyMode,
        val course: Int,
        val group: BsuBiologyGroup,
        val url: String,
    )

    private data class BiologyTeacherPageResult(
        val request: BiologyTeacherRequest,
        val html: String?,
        val failed: Boolean,
        val failureDetail: String? = null,
    )

    private data class FacultySource(val name: String, val url: String)

    private companion object {
        const val MIN_QUERY_LENGTH = 2
        const val BIOLOGY_SINGLE_SYMBOL_QUERY_LENGTH = 1
        const val MAX_LINK_DEPTH = 2
        const val MAX_TEACHER_LINK_DEPTH = 3
        const val MAX_LINKS_PER_PAGE = 12
        const val MAX_TEACHER_LINKS_PER_PAGE = 40
        const val MAX_PAGES_PER_FACULTY = 10
        const val MAX_TEACHER_PAGES_PER_FACULTY = 30
        const val MAX_IMPORT_LINK_DEPTH = 4
        const val MAX_IMPORT_FACULTY_CRAWLS_IN_FLIGHT = 3
        const val MAX_FAILURE_DETAIL_SAMPLES = 5
        const val MAX_FAILURE_DETAIL_LENGTH = 300
        const val MAX_IMPORT_LINKS_PER_PAGE = 100
        const val MAX_IMPORT_PAGES_PER_FACULTY = 500
        const val MAX_LINK_REQUESTS_IN_FLIGHT_PER_FACULTY = 3
        const val BIOLOGY_ROOT = BsuBiologyScheduleHtmlParser.BIOLOGY_SERVER_RENDERED_SCHEDULE_URL
        const val LEGACY_BIOLOGY_ROOT = BsuBiologyScheduleHtmlParser.BIOLOGY_SCHEDULE_URL
        const val BIOLOGY_MAX_COURSES = 5
        const val MAX_BIOLOGY_REQUESTS_IN_FLIGHT = 5
        const val MAX_BIOLOGY_TEACHER_GROUP_PAGES = 200

        val BIOLOGY_GROUP_LABEL_CODE = Regex(
            "^группа\\s*(?:№|#)?\\s*([0-9]+[a-zа-яё]?|[a-zа-яё])$",
            RegexOption.IGNORE_CASE,
        )
        val BIOLOGY_STUDY_MODES = listOf(
            BiologyStudyMode("Дневная", "d"),
            BiologyStudyMode("Заочная", "z"),
            BiologyStudyMode("Магистратура", "m"),
        )

        val FACULTIES = listOf(
            FacultySource(
                "Экономический факультет",
                "https://economy.bsu.by/studentam/dnevnaya-forma-obucheniya/raspisanie/",
            ),
            FacultySource("ФПМИ", "https://fpmi.bsu.by/timetable"),
            FacultySource("Механико-математический факультет", "https://mmf.bsu.by/ru/raspisanie-zanyatij/"),
            FacultySource(
                "Географический факультет",
                "https://geo.bsu.by/index.php/uchebnyj-protsess/grafik-uchebnogo-protsessa/raspisanie-zanyatij.html",
            ),
            FacultySource("ФМО", "https://fir.bsu.by/students/timetable-in-menu"),
            FacultySource("ФСК", "https://fsc.bsu.by/schedules/general-higher-education-lessons/"),
            FacultySource("Биологический факультет", BIOLOGY_ROOT),
            FacultySource("Институт бизнеса", "https://sb.bsu.by/raspisanie"),
            FacultySource(
                "Исторический факультет",
                "https://hist.bsu.by/uchebnyj-protsess/grafik-uchebnogo-protsessa/raspisanie-zanyatij.html",
            ),
            FacultySource("Юридический факультет", "https://law.bsu.by/index.php?option=com_content&view=article&id=2350&Itemid=7"),
            FacultySource(
                "Химический факультет",
                "https://chemistry.bsu.by/index.php/ru/studentam/grafik-uchebnogo-protsessa/raspisanie",
            ),
            FacultySource("Физический факультет", "https://physics.bsu.by/raspisanie/studentam/"),
            FacultySource("Факультет журналистики", "https://journ.bsu.by/raspisanie.html"),
            FacultySource("Филологический факультет", "https://philology.bsu.by/ru/studjentu/raspisanie"),
            FacultySource(
                "Факультет философии и социальных наук",
                "https://ffsn.bsu.by/ru/dfo-raspisanie-zanyatij.html",
            ),
            FacultySource("Факультет радиофизики и компьютерных технологий", "https://rct.bsu.by/education"),
        )

        val ROUTE_HINTS = listOf(
            "raspis", "timetable", "schedule", "занят", "распис", "дневн", "заочн", "курс", "group", "групп", "dropbox",
        )
        val DAY_OR_DATE_HINTS = listOf(
            "понедельник", "вторник", "среда", "четверг", "пятница", "суббота", "воскресенье",
            "панядзел", "аўторак", "серада", "чацвер", "пятніц", "субота", "нядзел",
            "дата", "день недели", "день", "weekday",
        )
        val TIME_RANGE = Regex("\\b\\d{1,2}[:.]\\d{2}\\s*[-–—−]\\s*\\d{1,2}[:.]\\d{2}\\b")
        val ABBREVIATED_WEEKDAY = Regex("\\b(?:пн|вт|ср|чт|пт|сб|вс|mon|tue|wed|thu|fri|sat|sun)\\b")
        val EXPLICIT_SCHEDULE_DATE = Regex("\\b\\d{1,2}[./]\\d{1,2}(?:[./]\\d{2,4})?\\b")
        val OFFICIAL_HTML_EXTENSIONS = setOf("html", "htm", "php", "asp", "aspx", "jsp", "do")
        val FILE_EXTENSIONS = listOf(
            ".pdf", ".doc", ".docx", ".xls", ".xlsx", ".ppt", ".pptx", ".zip", ".rar", ".jpg", ".png",
        )
    }
}
