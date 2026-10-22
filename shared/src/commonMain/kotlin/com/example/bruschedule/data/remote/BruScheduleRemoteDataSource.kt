package com.example.bruschedule.data.remote

import com.example.bruschedule.data.scraper.BruScheduleDirectoryParser
import com.example.bruschedule.domain.model.ScheduleProfile
import com.example.bruschedule.domain.model.ScheduleProfileType
import com.example.bruschedule.domain.model.UniversityIds
import com.example.bruschedule.domain.model.ScheduleWindow
import com.example.bruschedule.domain.repository.ScheduleRemoteDataSource
import kotlinx.coroutines.CancellationException

/**
 * Uses the configured direct HTML page for user-added universities. BRU profiles are resolved
 * through the live directory hierarchy; group profiles store a faculty page as a hint, but the group
 * code is authoritative if the hint is stale or points at an unrelated faculty.
 */
class BruScheduleRemoteDataSource(
    private val transport: HtmlTransport,
    private val directoryParser: BruScheduleDirectoryParser = BruScheduleDirectoryParser(),
) : ScheduleRemoteDataSource {
    override suspend fun fetchHtml(profile: ScheduleProfile, window: ScheduleWindow): String {
        if (profile.universityId != UniversityIds.BRU) {
            val timetableUrl = profile.sourceUrl?.trim()?.takeIf(String::isNotBlank)
                ?: error("Для этого вуза укажите прямую ссылку на страницу расписания")
            require(timetableUrl.startsWith("https://", ignoreCase = true)) {
                "Ссылка на расписание должна начинаться с https://"
            }
            return transport.get(timetableUrl)
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
}
