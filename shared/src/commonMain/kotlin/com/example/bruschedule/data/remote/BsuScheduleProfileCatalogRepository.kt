package com.example.bruschedule.data.remote

import com.example.bruschedule.data.scraper.BsuBiologyScheduleHtmlParser
import com.example.bruschedule.data.scraper.BsuScheduleHtmlParser
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
import kotlinx.coroutines.supervisorScope
import com.fleeksoft.ksoup.Ksoup
import kotlin.time.Clock
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.TimeZone
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.minus
import kotlinx.datetime.toLocalDateTime

/**
 * Discovers selectable groups from public BSU faculty pages, HTML timetables and Google Sheets.
 * The Biology faculty adapter follows its public study-mode/course/week filters and binds each
 * group to the exact data-group-id used by the server-rendered timetable grid.
 */
class BsuScheduleProfileCatalogRepository(
    private val transport: HtmlTransport,
    private val parser: BsuScheduleHtmlParser = BsuScheduleHtmlParser(),
    private val clock: Clock = Clock.System,
    private val biologyParser: BsuBiologyScheduleHtmlParser = BsuBiologyScheduleHtmlParser(),
    private val documentAdapter: ScheduleDocumentHtmlAdapter = ScheduleDocumentHtmlAdapter(),
) : ScheduleProfileCatalogRepository {
    override suspend fun search(type: ScheduleProfileType, query: String): List<ProfileCandidate> =
        search(UniversityIds.BSU, type, query)

    override suspend fun search(
        universityId: String,
        type: ScheduleProfileType,
        query: String,
    ): List<ProfileCandidate> {
        require(universityId == UniversityIds.BSU) { "Каталог расписаний БГУ не подходит выбранному вузу" }
        require(type == ScheduleProfileType.GROUP) { "В каталоге БГУ пока доступен поиск учебных групп" }
        val normalizedQuery = normalize(query)
        require(normalizedQuery.length >= MIN_QUERY_LENGTH) { "Введите не менее двух символов для поиска" }

        val results = supervisorScope {
            FACULTIES.map { faculty ->
                async(start = CoroutineStart.UNDISPATCHED) {
                    try {
                        discoverFaculty(faculty, normalizedQuery)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        FacultyResult(candidates = emptyList(), pagesLoaded = 0, requestFailures = 1)
                    }
                }
            }.awaitAll()
        }
        val successfulPages = results.sumOf { it.pagesLoaded }
        if (successfulPages == 0 && results.any { it.requestFailures > 0 }) {
            error("Не удалось открыть сайты факультетов БГУ. Проверьте подключение и попробуйте ещё раз")
        }
        return results.flatMap { it.candidates }
            .distinctBy { "${it.sourceUrl}|${it.sourceKey}|${it.name}" }
            .sortedWith(
                compareBy<ProfileCandidate> { normalize(it.name) != normalizedQuery }
                    .thenBy { normalize(it.name) }
                    .thenBy { it.subtitle.orEmpty() },
            )
            .take(MAX_RESULTS)
    }

    private suspend fun discoverFaculty(
        faculty: FacultySource,
        normalizedQuery: String,
    ): FacultyResult {
        if (faculty.url == BIOLOGY_ROOT) return discoverBiologyFaculty(faculty, normalizedQuery)
        val candidates = mutableListOf<ProfileCandidate>()
        val visited = mutableSetOf<String>()
        var pagesLoaded = 0
        var requestFailures = 0

        suspend fun fetch(url: String): String? = try {
            transport.get(url).also { pagesLoaded++ }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            requestFailures++
            null
        }

        suspend fun inspectPage(
            pageUrl: String,
            html: String,
            depth: Int,
            inheritedGroupLabels: List<String> = emptyList(),
            sourceKind: String = "HTML",
        ) {
            if (!visited.add(pageUrl) || visited.size > MAX_PAGES_PER_FACULTY) return

            val discoveredLabels = (parser.discoverTableGroupLabels(html) + inheritedGroupLabels)
                .distinctBy(::normalize)
            if (isTimetablePage(html)) {
                discoveredLabels.filter { matchesQuery(it, normalizedQuery) }.forEach { group ->
                    candidates += candidate(faculty, group, pageUrl, sourceKind)
                }
            }
            if (depth >= MAX_LINK_DEPTH || pageUrl.contains("docs.google.com/spreadsheets/")) return

            val document = Ksoup.parse(html)
            val linkSources = document.select("a[href]").toList().map { element ->
                LinkSource(element.attr("href"), element.text())
            } + document.select("iframe[src]").toList().map { element ->
                LinkSource(element.attr("src"), "embedded timetable")
            }
            val links = linkSources.mapNotNull { source ->
                val href = source.url.trim()
                if (href.isBlank() || href.startsWith("#") || href.startsWith("mailto:") ||
                    href.startsWith("javascript:")
                ) return@mapNotNull null
                val target = resolveUrl(pageUrl, href) ?: return@mapNotNull null
                if (!target.startsWith("https://", ignoreCase = true)) return@mapNotNull null
                val labelGroups = parser.discoverGroupLabelsFromText(source.label)
                val isSheet = GoogleSheetsScheduleUrl.toHtmlTableUrl(target) != null
                val documentFormat = documentAdapter.formatForUrl(target)
                val isDocument = documentFormat != null
                if (!isSheet && !isDocument &&
                    (!isHtmlPage(target) || (!sameHost(pageUrl, target) && !isDropboxHost(target)))
                ) return@mapNotNull null
                val routeText = "${source.label} $target".lowercase()
                val routeLooksRelevant = ROUTE_HINTS.any { hint -> routeText.contains(hint) }
                if (!isSheet && labelGroups.isEmpty() && !routeLooksRelevant) return@mapNotNull null
                LinkTarget(target, labelGroups, isSheet, documentFormat, routeLooksRelevant)
            }
                .distinctBy(LinkTarget::url)
                .sortedWith(
                    compareBy<LinkTarget> { !matchesQuery(it.groupLabels.joinToString(" "), normalizedQuery) }
                        .thenBy { !it.isSheet }
                        .thenBy { !it.looksRelevant },
                )
                .take(MAX_LINKS_PER_PAGE)

            links.forEach { link ->
                val documentFormat = link.documentFormat
                if (documentFormat != null) {
                    if (!documentFormat.isSupported) {
                        requestFailures++
                        return@forEach
                    }
                    val binaryTransport = transport as? BinaryResourceTransport
                    if (binaryTransport == null) {
                        requestFailures++
                        return@forEach
                    }
                    val documentHtml = try {
                        val bytes = binaryTransport.getBytes(documentAdapter.downloadUrl(link.url))
                        pagesLoaded++
                        documentAdapter.toHtml(documentFormat, bytes)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        requestFailures++
                        null
                    } ?: return@forEach
                    val documentLabels = (parser.discoverTableGroupLabels(documentHtml) + link.groupLabels)
                        .distinctBy(::normalize)
                    if (isTimetablePage(documentHtml)) {
                        documentLabels.filter { matchesQuery(it, normalizedQuery) }.forEach { group ->
                            candidates += candidate(faculty, group, link.url, documentFormat.displayName)
                        }
                    }
                } else if (link.isSheet) {
                    val htmlUrl = GoogleSheetsScheduleUrl.toHtmlTableUrl(link.url) ?: return@forEach
                    val sheetHtml = fetch(htmlUrl) ?: return@forEach
                    val sheetLabels = (parser.discoverTableGroupLabels(sheetHtml) + link.groupLabels)
                        .distinctBy(::normalize)
                    if (isTimetablePage(sheetHtml)) {
                        sheetLabels.filter { matchesQuery(it, normalizedQuery) }.forEach { group ->
                            candidates += candidate(faculty, group, link.url, "Google Sheets")
                        }
                    }
                } else {
                    val childHtml = fetch(link.url) ?: return@forEach
                    inspectPage(
                        pageUrl = link.url,
                        html = childHtml,
                        depth = depth + 1,
                        inheritedGroupLabels = link.groupLabels,
                    )
                }
            }
        }

        val rootHtml = fetch(faculty.url) ?: return FacultyResult(candidates, pagesLoaded, requestFailures)
        inspectPage(faculty.url, rootHtml, depth = 0)
        return FacultyResult(candidates, pagesLoaded, requestFailures)
    }

    private suspend fun discoverBiologyFaculty(
        faculty: FacultySource,
        normalizedQuery: String,
    ): FacultyResult {
        val today = clock.now().toLocalDateTime(TimeZone.currentSystemDefault()).date
        val weekDate = today.minus(DatePeriod(days = today.dayOfWeek.isoDayNumber - 1)).toString()
        val requests = BIOLOGY_STUDY_MODES.flatMap { studyMode ->
            (1..BIOLOGY_MAX_COURSES).map { course ->
                BiologyRequest(
                    studyMode = studyMode,
                    course = course,
                    url = "$BIOLOGY_ROOT?study_mode=${encodeQueryComponent(studyMode)}&course=$course&week_date=$weekDate",
                )
            }
        }
        val responses = supervisorScope {
            requests.map { request ->
                async(start = CoroutineStart.UNDISPATCHED) {
                    try {
                        BiologyPageResult(request, transport.get(request.url), failed = false)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        BiologyPageResult(request, html = null, failed = true)
                    }
                }
            }.awaitAll()
        }
        val candidates = responses.flatMap { response ->
            val html = response.html ?: return@flatMap emptyList()
            biologyParser.discoverGroups(html).mapNotNull { group ->
                val searchable = "${group.name} ${group.programme} ${response.request.studyMode} ${response.request.course}"
                if (!matchesQuery(searchable, normalizedQuery)) return@mapNotNull null
                candidate(
                    faculty = faculty,
                    group = group.name,
                    sourceUrl = response.request.url,
                    sourceKind = "HTML",
                    sourceKey = "${BsuBiologyScheduleHtmlParser.BIO_GROUP_SOURCE_KEY_PREFIX}${group.id}",
                    detail = listOfNotNull(
                        response.request.studyMode,
                        "${response.request.course} курс",
                        group.programme.takeIf(String::isNotBlank),
                    ).joinToString(" · "),
                )
            }
        }
        return FacultyResult(
            candidates = candidates,
            pagesLoaded = responses.count { it.html != null },
            requestFailures = responses.count(BiologyPageResult::failed),
        )
    }

    private fun candidate(
        faculty: FacultySource,
        group: String,
        sourceUrl: String,
        sourceKind: String,
        sourceKey: String = group,
        detail: String? = null,
    ) = ProfileCandidate(
        type = ScheduleProfileType.GROUP,
        name = group,
        sourceUrl = sourceUrl,
        sourceKey = sourceKey,
        subtitle = listOfNotNull(faculty.name, detail, sourceKind).joinToString(" · "),
        universityId = UniversityIds.BSU,
    )

    private fun isTimetablePage(html: String): Boolean {
        val text = Ksoup.parse(html).text().lowercase()
        val hasTime = TIME_RANGE.containsMatchIn(text)
        val hasDayOrDate = DAY_OR_DATE_HINTS.any { hint -> text.contains(hint) }
        return hasTime && hasDayOrDate
    }

    private fun isHtmlPage(url: String): Boolean {
        val path = url.substringBefore('?').substringBefore('#').lowercase()
        return !FILE_EXTENSIONS.any { extension -> path.endsWith(extension) }
    }

    private fun sameHost(first: String, second: String): Boolean =
        host(first).equals(host(second), ignoreCase = true)

    private fun isDropboxHost(url: String): Boolean {
        val host = host(url)
        return host.equals("dropbox.com", ignoreCase = true) || host.endsWith(".dropbox.com", ignoreCase = true)
    }

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

    private data class FacultyResult(
        val candidates: List<ProfileCandidate>,
        val pagesLoaded: Int,
        val requestFailures: Int,
    )

    private data class BiologyRequest(
        val studyMode: String,
        val course: Int,
        val url: String,
    )

    private data class BiologyPageResult(
        val request: BiologyRequest,
        val html: String?,
        val failed: Boolean,
    )

    private data class FacultySource(val name: String, val url: String)

    private companion object {
        const val MIN_QUERY_LENGTH = 2
        const val MAX_RESULTS = 80
        const val MAX_LINK_DEPTH = 2
        const val MAX_LINKS_PER_PAGE = 6
        const val MAX_PAGES_PER_FACULTY = 10
        const val BIOLOGY_ROOT = "https://bio.bsu.by/schedule/"
        const val BIOLOGY_MAX_COURSES = 5

        val BIOLOGY_STUDY_MODES = listOf("Дневная", "Заочная", "Магистратура")

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
        )

        val ROUTE_HINTS = listOf(
            "raspis", "timetable", "schedule", "занят", "распис", "дневн", "заочн", "курс", "group", "групп", "dropbox",
        )
        val DAY_OR_DATE_HINTS = listOf(
            "понедельник", "вторник", "среда", "четверг", "пятница", "суббота", "воскресенье",
            "дата", "день недели", "день", "weekday",
        )
        val TIME_RANGE = Regex("\\b\\d{1,2}[:.]\\d{2}\\s*[-–—]\\s*\\d{1,2}[:.]\\d{2}\\b")
        val FILE_EXTENSIONS = listOf(
            ".pdf", ".doc", ".docx", ".xls", ".xlsx", ".ppt", ".pptx", ".zip", ".rar", ".jpg", ".png",
        )
    }
}
