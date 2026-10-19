package com.example.bruschedule.data.scraper

import com.example.bruschedule.domain.model.ProfileCandidate
import com.example.bruschedule.domain.model.ScheduleProfile
import com.example.bruschedule.domain.model.ScheduleProfileType
import com.example.bruschedule.domain.model.ScheduleWindow
import com.fleeksoft.ksoup.Ksoup
import kotlinx.datetime.LocalDate

/** Parsers for /full_time, /part_time and /index1.html; selection results retain stable URLs. */
class BruScheduleDirectoryParser {
    fun hasScheduleTable(html: String): Boolean =
        Ksoup.parse(html).select("table.schedule-table").isNotEmpty()

    fun facultyDirectoryUrls(html: String, pageUrl: String, type: ScheduleProfileType): List<String> {
        if (type == ScheduleProfileType.TEACHER) return emptyList()
        val section = if ("part_time" in pageUrl) "part_time" else "full_time"
        val rootPath = "/$section/"
        return Ksoup.parse(html).select("a[href]")
            .mapNotNull { anchor ->
                val absolute = absoluteUrl(pageUrl, anchor.attr("href"))
                val path = absolute.substringAfter("rasp.bru.by", absolute)
                val appearsToBeFacultyDirectory =
                    path.endsWith("index.html") || path.endsWith('/') || !path.substringAfterLast('/').contains('.')
                if (path.startsWith(rootPath) && path != rootPath && appearsToBeFacultyDirectory) {
                    absolute
                } else {
                    null
                }
            }
            .filterNot { it.endsWith("/$section/index.html") }
            .distinct()
    }

    fun groupCandidates(html: String, facultyPageUrl: String): List<ProfileCandidate> {
        val document = Ksoup.parse(html)
        return document.select("a.group-link[href], a[href*=_weeks_], a[href*=_session_]")
            .mapNotNull { anchor ->
                val label = normalizeSpaces(anchor.text())
                val name = label.substringBefore('(').split(Regex("\\s+\\d{1,2}\\.\\d{1,2}"), limit = 2)
                    .first().trim().trimEnd('—', '-', '•')
                if (name.isBlank()) return@mapNotNull null
                ProfileCandidate(
                    type = ScheduleProfileType.GROUP,
                    name = name,
                    sourceUrl = facultyPageUrl,
                    sourceKey = name,
                    subtitle = label.removePrefix(name).trim().trim('(', ')'),
                )
            }
            .distinctBy { normalizeForMatch(it.name) + "|" + it.sourceUrl }
    }

    fun teacherCandidates(html: String, teachersIndexUrl: String): List<ProfileCandidate> {
        return Ksoup.parse(html).select("a[href]")
            .mapNotNull { anchor ->
                val href = anchor.attr("href")
                if (!href.contains("/teachers/")) return@mapNotNull null
                val label = normalizeSpaces(anchor.text())
                val name = label.replace(Regex("\\s+\\d+\\s*$"), "")
                    .replace(Regex("\\s+(доцент|профессор|ассистент|преподаватель|старший преподаватель).*", RegexOption.IGNORE_CASE), "")
                    .trim()
                if (name.isBlank()) return@mapNotNull null
                ProfileCandidate(
                    type = ScheduleProfileType.TEACHER,
                    name = name,
                    sourceUrl = absoluteUrl(teachersIndexUrl, href),
                    sourceKey = href,
                    subtitle = label.removePrefix(name).trim(),
                )
            }
            .distinctBy { it.sourceUrl }
    }

    /** Finds the rolling schedule link for a profile in a stable group/teacher index page. */
    fun scheduleUrlFromIndex(
        html: String,
        pageUrl: String,
        profile: ScheduleProfile,
        window: ScheduleWindow? = null,
    ): String? {
        val document = Ksoup.parse(html)
        val key = normalizeForMatch(profile.sourceKey?.takeIf(String::isNotBlank) ?: profile.name)
        val anchors = document.select("a[href]")
        val candidates = anchors.mapNotNull { anchor ->
            val href = anchor.attr("href")
            val isCandidate = when (profile.type) {
                ScheduleProfileType.GROUP ->
                    href.contains("_weeks_") || href.contains("_session_") ||
                        anchor.attr("class").split(Regex("\\s+")).contains("group-link")
                ScheduleProfileType.TEACHER -> href.contains("/teachers/")
            }
            if (!isCandidate) return@mapNotNull null
            val label = normalizeForMatch(anchor.text())
            val path = normalizeForMatch(percentDecode(href))
            val labelNameKey = normalizeForMatch(profile.name.trim().substringBefore(' '))
            val matches = key.isNotBlank() && (
                label.contains(key) || key.contains(label) || path.contains(key) ||
                    (profile.type == ScheduleProfileType.TEACHER && labelNameKey.length >= 2 && label.contains(labelNameKey))
            )
            if (matches) absoluteUrl(pageUrl, href) else null
        }
        return if (window == null) {
            candidates.firstOrNull()
        } else {
            candidates.firstOrNull { linkCoversWindow(it, window) } ?: candidates.firstOrNull()
        }
    }

    private fun linkCoversWindow(url: String, window: ScheduleWindow): Boolean {
        val match = WEEK_RANGE.find(percentDecode(url)) ?: return false
        val start = compactDate(match.groupValues[1]) ?: return false
        val end = compactDate(match.groupValues[2]) ?: return false
        return start <= window.endDate && end >= window.startDate
    }

    private fun compactDate(value: String): LocalDate? {
        if (value.length != 8) return null
        return runCatching {
            LocalDate(
                year = value.substring(0, 4).toInt(),
                monthNumber = value.substring(4, 6).toInt(),
                dayOfMonth = value.substring(6, 8).toInt(),
            )
        }.getOrNull()
    }

    fun matchesQuery(candidate: ProfileCandidate, query: String): Boolean {
        val normalizedQuery = normalizeForMatch(query)
        if (normalizedQuery.isBlank()) return true
        val normalizedName = normalizeForMatch(candidate.name)
        if (normalizedName.contains(normalizedQuery) || normalizedQuery.contains(normalizedName)) return true
        // Teacher indexes commonly display surname + initials while the detail page has the full name.
        val surname = normalizeForMatch(query.trim().substringBefore(' '))
        return surname.length >= 2 && normalizedName.contains(surname)
    }

    fun absolutize(pageUrl: String, href: String): String = absoluteUrl(pageUrl, href)

    private fun absoluteUrl(pageUrl: String, href: String): String {
        val cleanHref = href.trim()
        if (cleanHref.startsWith("https://") || cleanHref.startsWith("http://")) return cleanHref
        if (cleanHref.startsWith("//")) return "https:$cleanHref"
        if (cleanHref.startsWith('/')) return "$ROOT$cleanHref"
        val directory = pageUrl.substringBeforeLast('/', pageUrl)
        return "$directory/$cleanHref"
    }

    private fun percentDecode(value: String): String = value
        .replace("%20", " ", ignoreCase = true)
        .replace("%2D", "-", ignoreCase = true)
        .replace("%5F", "_", ignoreCase = true)
        .replace("%28", "(", ignoreCase = true)
        .replace("%29", ")", ignoreCase = true)

    private fun normalizeSpaces(value: String): String = value.replace(Regex("\\s+"), " ").trim()

    private fun normalizeForMatch(value: String): String = value.lowercase()
        .replace('ё', 'е')
        .filter { it.isLetterOrDigit() }

    companion object {
        private val WEEK_RANGE = Regex("_weeks_(20\\d{6})_(20\\d{6})", RegexOption.IGNORE_CASE)
        const val ROOT = "https://rasp.bru.by"
        const val FULL_TIME_INDEX = "$ROOT/full_time/index.html"
        const val PART_TIME_INDEX = "$ROOT/part_time/index.html"
        const val TEACHERS_INDEX = "$ROOT/index1.html"
    }
}
