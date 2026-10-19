package com.example.bruschedule.data.remote

import com.example.bruschedule.data.scraper.BruScheduleDirectoryParser
import com.example.bruschedule.domain.model.ScheduleProfile
import com.example.bruschedule.domain.model.ScheduleProfileType
import com.example.bruschedule.domain.model.ScheduleWindow
import com.example.bruschedule.domain.repository.ScheduleRemoteDataSource
import kotlinx.coroutines.CancellationException

/**
 * Resolves a stable faculty/teacher page to the current dated timetable link on every refresh.
 * rasp.bru.by's directory pages are the rolling index: the weeks_YYYYMMDD_YYYYMMDD target changes
 * as the semester advances, so profiles selected from the catalogue store the directory URL.
 */
class BruScheduleRemoteDataSource(
    private val transport: HtmlTransport,
    private val directoryParser: BruScheduleDirectoryParser = BruScheduleDirectoryParser(),
) : ScheduleRemoteDataSource {
    override suspend fun fetchHtml(profile: ScheduleProfile, window: ScheduleWindow): String {
        val configuredUrl = profile.sourceUrl
        if (!configuredUrl.isNullOrBlank()) {
            val configuredHtml = transport.get(directoryParser.absolutize(BruScheduleDirectoryParser.ROOT, configuredUrl))
            if (directoryParser.hasScheduleTable(configuredHtml)) return configuredHtml
            directoryParser.scheduleUrlFromIndex(configuredHtml, configuredUrl, profile, window)?.let { scheduleUrl ->
                return transport.get(scheduleUrl)
            }
            if (profile.type == ScheduleProfileType.GROUP) {
                findGroupSchedule(profile, window, configuredPage = configuredUrl to configuredHtml)?.let { return it }
            }
            error("Не удалось найти расписание «${profile.name}» на странице каталога")
        }

        return when (profile.type) {
            ScheduleProfileType.TEACHER -> {
                val indexHtml = transport.get(BruScheduleDirectoryParser.TEACHERS_INDEX)
                val teacherUrl = directoryParser.scheduleUrlFromIndex(
                    html = indexHtml,
                    pageUrl = BruScheduleDirectoryParser.TEACHERS_INDEX,
                    profile = profile,
                ) ?: error("Преподаватель «${profile.name}» не найден в разделе преподавателей")
                transport.get(teacherUrl)
            }
            ScheduleProfileType.GROUP -> findGroupSchedule(profile, window)
                ?: error("Группа «${profile.name}» не найдена в дневной или заочной форме")
        }
    }

    private suspend fun findGroupSchedule(
        profile: ScheduleProfile,
        window: ScheduleWindow,
        configuredPage: Pair<String, String>? = null,
    ): String? {
        val roots = listOf(
            BruScheduleDirectoryParser.FULL_TIME_INDEX,
            BruScheduleDirectoryParser.PART_TIME_INDEX,
        )
        for (root in roots) {
            val rootHtml = if (configuredPage?.first == root) configuredPage.second else getOrNull(root) ?: continue
            val rootLink = directoryParser.scheduleUrlFromIndex(rootHtml, root, profile, window)
            if (rootLink != null) {
                getOrNull(rootLink)?.let { if (directoryParser.hasScheduleTable(it)) return it }
            }

            val facultyPages = directoryParser.facultyDirectoryUrls(
                html = rootHtml,
                pageUrl = root,
                type = ScheduleProfileType.GROUP,
            )
            for (facultyUrl in facultyPages) {
                val facultyHtml = if (configuredPage?.first == facultyUrl) configuredPage.second else getOrNull(facultyUrl) ?: continue
                val scheduleUrl = directoryParser.scheduleUrlFromIndex(facultyHtml, facultyUrl, profile, window) ?: continue
                val scheduleHtml = getOrNull(scheduleUrl) ?: continue
                if (directoryParser.hasScheduleTable(scheduleHtml)) return scheduleHtml
            }
        }
        return null
    }

    private suspend fun getOrNull(url: String): String? = try {
        transport.get(url)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Throwable) {
        null
    }
}
