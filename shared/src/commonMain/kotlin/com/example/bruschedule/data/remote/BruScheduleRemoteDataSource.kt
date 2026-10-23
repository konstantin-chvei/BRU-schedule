package com.example.bruschedule.data.remote

import com.example.bruschedule.data.scraper.BruScheduleDirectoryParser
import com.example.bruschedule.data.scraper.BsuBiologyScheduleHtmlParser
import com.example.bruschedule.data.scraper.ScheduleDocumentHtmlAdapter
import com.example.bruschedule.domain.model.ScheduleProfile
import com.example.bruschedule.domain.model.ScheduleProfileType
import com.example.bruschedule.domain.model.UniversityIds
import com.example.bruschedule.domain.model.ScheduleWindow
import com.example.bruschedule.domain.repository.ScheduleRemoteDataSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.minus
import kotlinx.datetime.plus

/**
 * Uses the configured direct HTML page for user-added universities. BRU profiles are resolved
 * through the live directory hierarchy; group profiles store a faculty page as a hint, but the group
 * code is authoritative if the hint is stale or points at an unrelated faculty.
 */
class BruScheduleRemoteDataSource(
    private val transport: HtmlTransport,
    private val directoryParser: BruScheduleDirectoryParser = BruScheduleDirectoryParser(),
    private val biologyScheduleParser: BsuBiologyScheduleHtmlParser = BsuBiologyScheduleHtmlParser(),
    private val documentAdapter: ScheduleDocumentHtmlAdapter = ScheduleDocumentHtmlAdapter(),
) : ScheduleRemoteDataSource {
    override suspend fun fetchHtml(profile: ScheduleProfile, window: ScheduleWindow): String {
        if (profile.universityId != UniversityIds.BRU) {
            if (profile.universityId == UniversityIds.BSU && biologyScheduleParser.isBiologyProfile(profile)) {
                return fetchBiologyWeeks(profile, window)
            }
            val timetableUrl = profile.sourceUrl?.trim()?.takeIf(String::isNotBlank)
                ?: error("Для этого вуза укажите прямую ссылку на страницу расписания")
            require(timetableUrl.startsWith("https://", ignoreCase = true)) {
                "Ссылка на расписание должна начинаться с https://"
            }
            val fetchUrl = if (profile.universityId == UniversityIds.BSU) {
                GoogleSheetsScheduleUrl.toHtmlTableUrl(timetableUrl) ?: timetableUrl
            } else {
                timetableUrl
            }
            val documentFormat = documentAdapter.formatForUrl(fetchUrl)
            if (documentFormat != null) {
                require(documentFormat.isSupported) {
                    "Формат ${documentFormat.extension} не поддерживается; используйте PDF, XLSX, DOCX, XLS или DOC"
                }
                val binaryTransport = transport as? BinaryResourceTransport
                    ?: error("Источник не поддерживает загрузку файлов расписания")
                val bytes = binaryTransport.getBytes(documentAdapter.downloadUrl(fetchUrl))
                return documentAdapter.toHtml(documentFormat, bytes)
            }
            return transport.get(fetchUrl)
        }

        var lastNetworkError: String? = null
        suspend fun fetchOrNull(url: String): String? = try {
            transport.get(url)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            lastNetworkError = failure.message?.takeIf(String::isNotBlank) ?: "Неизвестная ошибка сети"
            null
        }

        val configuredUrl = profile.sourceUrl?.takeIf(String::isNotBlank)?.let {
            directoryParser.absolutize(BruScheduleDirectoryParser.ROOT, it)
        }
        var configuredPage: Pair<String, String>? = null
        var failedConfiguredUrl: String? = null
        if (configuredUrl != null) {
            val configuredHtml = fetchOrNull(configuredUrl)
            if (configuredHtml != null) {
                configuredPage = configuredUrl to configuredHtml
                if (directoryParser.hasScheduleTable(configuredHtml)) return configuredHtml

                val scheduleUrl = directoryParser.scheduleUrlFromIndex(
                    html = configuredHtml,
                    pageUrl = configuredUrl,
                    profile = profile,
                    window = window,
                )
                if (scheduleUrl != null) {
                    val scheduleHtml = fetchOrNull(scheduleUrl)
                    if (scheduleHtml != null && directoryParser.hasScheduleTable(scheduleHtml)) return scheduleHtml
                }
            } else {
                failedConfiguredUrl = configuredUrl
            }
        }

        return when (profile.type) {
            ScheduleProfileType.GROUP -> {
                findGroupSchedule(profile, window, configuredPage, failedConfiguredUrl)?.let { return it }
                lastNetworkError?.let { detail ->
                    error("Не удалось найти расписание группы «${profile.name}». Последняя ошибка сайта: $detail")
                }
                error("Группа «${profile.name}» не найдена в каталогах дневной и заочной форм")
            }
            ScheduleProfileType.TEACHER -> {
                val indexUrl = BruScheduleDirectoryParser.TEACHERS_INDEX
                val indexHtml = if (configuredPage?.first == indexUrl) {
                    configuredPage.second
                } else {
                    fetchOrNull(indexUrl)
                } ?: error("Не удалось открыть каталог преподавателей rasp.bru.by: ${lastNetworkError ?: "ответ отсутствует"}")

                val teacherUrl = directoryParser.scheduleUrlFromIndex(
                    html = indexHtml,
                    pageUrl = indexUrl,
                    profile = profile,
                ) ?: error("Преподаватель «${profile.name}» не найден в каталоге rasp.bru.by")
                val teacherHtml = fetchOrNull(teacherUrl)
                    ?: error("Не удалось загрузить страницу преподавателя «${profile.name}»: ${lastNetworkError ?: "ответ отсутствует"}")
                if (!directoryParser.hasScheduleTable(teacherHtml)) {
                    error("На странице преподавателя «${profile.name}» не найдена таблица расписания")
                }
                teacherHtml
            }
        }
    }

    private suspend fun fetchBiologyWeeks(profile: ScheduleProfile, window: ScheduleWindow): String {
        val sourceUrl = profile.sourceUrl?.trim()?.takeIf(String::isNotBlank)
            ?: error("В профиле Биофака отсутствует ссылка на расписание")
        val firstMonday = window.startDate.minus(DatePeriod(days = window.startDate.dayOfWeek.isoDayNumber - 1))
        val throughDate = window.semesterCoverageEndDate ?: window.endDate
        val mondays = buildList {
            var week = firstMonday
            while (week <= throughDate && size < MAX_BIOLOGY_WEEKS) {
                add(week)
                week = week.plus(DatePeriod(days = 7))
            }
        }
        require(mondays.isNotEmpty()) { "Не удалось определить недели расписания Биофака" }
        require(mondays.last() <= throughDate || mondays.size < MAX_BIOLOGY_WEEKS) {
            "Период расписания Биофака превышает допустимый диапазон"
        }
        val responses = supervisorScope {
            mondays.map { week ->
                async(start = CoroutineStart.UNDISPATCHED) {
                    val result = try {
                        Result.success(transport.get(withWeekDate(sourceUrl, week.toString())))
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Throwable) {
                        Result.failure(failure)
                    }
                    week to result
                }
            }.awaitAll()
        }
        val failed = responses.firstOrNull { (_, result) -> result.isFailure }
        if (failed != null) {
            val detail = failed.second.exceptionOrNull()?.message?.takeIf(String::isNotBlank)
                ?: "сайт не вернул страницу"
            error("Не удалось загрузить расписание Биофака на неделю ${failed.first}: $detail")
        }
        return biologyScheduleParser.combineWeeklyPages(
            responses.map { (week, result) -> week to result.getOrThrow() },
        )
    }

    private fun withWeekDate(url: String, weekDate: String): String {
        val fragmentIndex = url.indexOf('#')
        val fragment = if (fragmentIndex >= 0) url.substring(fragmentIndex) else ""
        val withoutFragment = if (fragmentIndex >= 0) url.substring(0, fragmentIndex) else url
        val queryIndex = withoutFragment.indexOf('?')
        val base = if (queryIndex >= 0) withoutFragment.substring(0, queryIndex) else withoutFragment
        val parameters = if (queryIndex >= 0) {
            withoutFragment.substring(queryIndex + 1).split('&').filter(String::isNotBlank).toMutableList()
        } else {
            mutableListOf()
        }
        val dateParameterIndex = parameters.indexOfFirst { it.substringBefore('=') == "week_date" }
        if (dateParameterIndex >= 0) {
            parameters[dateParameterIndex] = "week_date=$weekDate"
        } else {
            parameters += "week_date=$weekDate"
        }
        return "$base?${parameters.joinToString("&")}$fragment"
    }

    private suspend fun findGroupSchedule(
        profile: ScheduleProfile,
        window: ScheduleWindow,
        configuredPage: Pair<String, String>? = null,
        failedConfiguredUrl: String? = null,
    ): String? {
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
            if (root == failedConfiguredUrl) continue
            val rootHtml = if (configuredPage?.first == root) configuredPage.second else fetchOrNull(root) ?: continue
            val rootLink = directoryParser.scheduleUrlFromIndex(rootHtml, root, profile, window)
            if (rootLink != null) {
                val rootSchedule = fetchOrNull(rootLink)
                if (rootSchedule != null && directoryParser.hasScheduleTable(rootSchedule)) return rootSchedule
            }

            val facultyPages = directoryParser.facultyDirectories(
                html = rootHtml,
                pageUrl = root,
                type = ScheduleProfileType.GROUP,
            )
            for (faculty in facultyPages) {
                if (faculty.url == failedConfiguredUrl) continue
                val facultyHtml = if (configuredPage?.first == faculty.url) {
                    configuredPage.second
                } else {
                    fetchOrNull(faculty.url) ?: continue
                }
                val scheduleUrl = directoryParser.scheduleUrlFromIndex(facultyHtml, faculty.url, profile, window) ?: continue
                val scheduleHtml = fetchOrNull(scheduleUrl) ?: continue
                if (directoryParser.hasScheduleTable(scheduleHtml)) return scheduleHtml
            }
        }
        lastNetworkError?.let { detail ->
            error("Ошибка доступа к каталогам расписаний rasp.bru.by: $detail")
        }
        return null
    }

    private companion object {
        const val MAX_BIOLOGY_WEEKS = 54
    }
}
