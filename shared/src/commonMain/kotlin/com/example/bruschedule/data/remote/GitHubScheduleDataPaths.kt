package com.example.bruschedule.data.remote

import com.example.bruschedule.domain.model.ScheduleProfileType

/** Paths shared by the app, static web client, and schedule-data generator. */
object GitHubScheduleDataPaths {
    const val OWNER = "konstantin-chvei"
    const val REPOSITORY = "BRU-schedule"
    const val DATA_DIRECTORY = "schedule-data"

    const val PAGES_URL = "https://$OWNER.github.io/$REPOSITORY/"
    const val UNIVERSITIES_ROOT = "${PAGES_URL}${DATA_DIRECTORY}/universities"

    fun indexUrl(universityId: String, type: ScheduleProfileType): String {
        require(isSafeSlug(universityId)) { "Идентификатор вуза в GitHub-каталоге некорректен" }
        val profileFolder = if (type == ScheduleProfileType.GROUP) "groups" else "teachers"
        return "$UNIVERSITIES_ROOT/$universityId/$profileFolder/index.csv"
    }

    fun scheduleUrl(universityId: String, type: ScheduleProfileType, relativePath: String): String {
        require(isSafeSlug(universityId)) { "Идентификатор вуза в GitHub-каталоге некорректен" }
        val path = relativePath.trim().removePrefix("./")
        val requiredPrefix = if (type == ScheduleProfileType.GROUP) "schedules/groups/" else "schedules/teachers/"
        require(path.startsWith(requiredPrefix) && isSafeRelativePath(path)) {
            "Путь расписания должен находиться в папке $requiredPrefix"
        }
        return "$UNIVERSITIES_ROOT/$universityId/$path"
    }

    fun isPublishedDataUrl(url: String): Boolean {
        if (!url.startsWith("$UNIVERSITIES_ROOT/", ignoreCase = true)) return false
        val relativePath = url.substringAfter("$UNIVERSITIES_ROOT/")
        val segments = relativePath.split('/')
        val isProfileIndex = segments.size == 3 &&
            isSafeSlug(segments[0]) &&
            segments[1] in setOf("groups", "teachers") &&
            segments[2] == "index.csv"
        return isProfileIndex || isScheduleFileUrl(url)
    }

    fun isScheduleFileUrl(url: String): Boolean {
        if (!url.startsWith("$UNIVERSITIES_ROOT/", ignoreCase = true)) return false
        val relativePath = url.substringAfter("$UNIVERSITIES_ROOT/")
        val segments = relativePath.split('/')
        return segments.size >= 4 &&
            isSafeSlug(segments.first()) &&
            segments[1] == "schedules" &&
            segments[2] in setOf("groups", "teachers") &&
            isSafeRelativePath(relativePath)
    }

    fun isCsvScheduleUrl(url: String?): Boolean =
        url != null && isScheduleFileUrl(url) && url.endsWith(".csv", ignoreCase = true)

    private fun isSafeRelativePath(path: String): Boolean =
        path.isNotBlank() &&
            !path.startsWith('/') &&
            path.split('/').all { segment ->
                segment.isNotBlank() && segment != "." && segment != ".." &&
                    segment.all { it.isLetterOrDigit() || it in "-_." }
            }

    private fun isSafeSlug(value: String): Boolean =
        value.isNotBlank() && value.length <= 80 &&
            value.all { it.isLetterOrDigit() || it in "-_" }
}
