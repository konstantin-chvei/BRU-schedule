package com.example.bruschedule.domain.usecase

import com.example.bruschedule.domain.model.ScheduleProfile
import com.example.bruschedule.domain.model.ScheduleWindow
import com.example.bruschedule.domain.repository.ScheduleRepository
import com.example.bruschedule.domain.repository.ScheduleSyncRepository
import com.example.bruschedule.domain.repository.ParserVersionRepository
import com.example.bruschedule.domain.repository.ScheduleHtmlParser
import com.example.bruschedule.domain.repository.ScheduleRemoteDataSource
import kotlin.time.Clock
import kotlinx.coroutines.CancellationException

sealed interface RefreshOutcome {
    data class Success(
        val profileId: String,
        val lessonCount: Int,
        val preservedCustomLessonCount: Int,
        val completedAtEpochMillis: Long,
    ) : RefreshOutcome

    data class Failure(val message: String) : RefreshOutcome
}

/**
 * Coordinates network -> Ksoup parser -> validation -> one database transaction. No cache is ever
 * deleted on fetch/parse failure, so the app remains useful while rasp.bru.by is unavailable.
 */
class RefreshScheduleUseCase(
    private val schedules: ScheduleRepository,
    private val syncRepository: ScheduleSyncRepository,
    private val remote: ScheduleRemoteDataSource,
    private val scraper: ScheduleHtmlParser,
    private val versionFlags: ParserVersionRepository,
    private val clock: Clock = Clock.System,
) {
    suspend operator fun invoke(profileId: String): RefreshOutcome {
        val attemptedAt = clock.now().toEpochMilliseconds()
        return try {
            val profile: ScheduleProfile = schedules.getProfile(profileId)
                ?: error("Профиль расписания не найден")
            val window = ScheduleWindow.currentAndNextWeek(clock)
            val html = remote.fetchHtml(profile, window)
            val parsed = scraper.parse(
                profile = profile,
                html = html,
                referenceDate = window.startDate,
            )
            if (parsed.isEmpty()) {
                error("На странице не найдено ни одной пары. Локальное расписание не изменено.")
            }
            val commit = syncRepository.applyWebsiteSchedule(
                profileId = profile.id,
                websiteLessons = parsed,
                successfulAtEpochMillis = clock.now().toEpochMilliseconds(),
            )
            versionFlags.markParserVersion(profile.id, scraper.formatVersion)
            RefreshOutcome.Success(
                profileId = profile.id,
                lessonCount = commit.appliedLessonCount,
                preservedCustomLessonCount = commit.preservedCustomLessonCount,
                completedAtEpochMillis = clock.now().toEpochMilliseconds(),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            val message = failure.message?.takeIf(String::isNotBlank)
                ?: "Не удалось обновить расписание. Проверьте подключение и попробуйте ещё раз."
            runCatching {
                syncRepository.recordRefreshFailure(profileId, attemptedAt, message)
            }
            RefreshOutcome.Failure(message)
        }
    }
}

