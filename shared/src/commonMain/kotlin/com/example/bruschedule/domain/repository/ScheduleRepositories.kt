package com.example.bruschedule.domain.repository

import com.example.bruschedule.domain.model.AcademicTask
import com.example.bruschedule.domain.model.AppearancePreferences
import com.example.bruschedule.domain.model.SemesterSchedulePreferences
import com.example.bruschedule.domain.model.TypographyPreferences
import com.example.bruschedule.domain.model.Lesson
import com.example.bruschedule.domain.model.LessonType
import com.example.bruschedule.domain.model.ScheduleAppTheme
import com.example.bruschedule.domain.model.ProfileCandidate
import com.example.bruschedule.domain.model.ProfileSyncState
import com.example.bruschedule.domain.model.RefreshCommit
import com.example.bruschedule.domain.model.ScheduleProfile
import com.example.bruschedule.domain.model.ScheduleProfileType
import kotlinx.coroutines.flow.Flow

/** Local, observable source of truth. Screens read this repository before attempting the network. */
interface ScheduleRepository {
    fun observeProfiles(): Flow<List<ScheduleProfile>>
    fun observeAllLessons(): Flow<List<Lesson>>
    fun observeLessons(profileId: String): Flow<List<Lesson>>

    suspend fun getProfile(profileId: String): ScheduleProfile?
    suspend fun getLesson(lessonId: String): Lesson?
    suspend fun getLessons(profileId: String): List<Lesson>
    suspend fun saveProfile(profile: ScheduleProfile)
    suspend fun deleteProfile(profileId: String)

    /** All user-authored edits, including a newly-added pair, are persisted as custom overrides. */
    suspend fun saveCustomLesson(lesson: Lesson)
    suspend fun saveCustomLessons(lessons: List<Lesson>)
    /** Updates a lesson note without turning a website lesson into a custom schedule override. */
    suspend fun saveLessonNotes(lessonId: String, notes: String): Boolean

    /** Deletes a custom lesson atomically, detaching but preserving its linked deadlines. */
    suspend fun deleteCustomLessonAndPreserveTasks(lessonId: String): Boolean
}

/** Transactional website refresh, V-1 snapshots, error journal, and rollback. */
interface ScheduleSyncRepository {
    fun observeSyncState(profileId: String): Flow<ProfileSyncState?>
    suspend fun applyWebsiteSchedule(
        profileId: String,
        websiteLessons: List<Lesson>,
        successfulAtEpochMillis: Long,
    ): RefreshCommit
    suspend fun recordRefreshFailure(profileId: String, attemptedAtEpochMillis: Long, message: String)
    suspend fun rollbackToPrevious(profileId: String): Boolean
}

interface AcademicTaskRepository {
    fun observeTasks(): Flow<List<AcademicTask>>
    fun observeTasksForLesson(lessonId: String): Flow<List<AcademicTask>>
    suspend fun saveTask(task: AcademicTask)
    /** Applies a related set of deadline changes atomically. */
    suspend fun saveTasks(tasks: List<AcademicTask>)
    suspend fun setTaskCompleted(taskId: String, completed: Boolean)
    suspend fun deleteTask(taskId: String)
}

/** University-specific searchable catalogue of group and, where available, teacher profiles. */
interface ScheduleProfileCatalogRepository {
    suspend fun search(type: ScheduleProfileType, query: String): List<ProfileCandidate>

    /** University-aware extension keeps the original BRU call source-compatible. */
    suspend fun search(universityId: String, type: ScheduleProfileType, query: String): List<ProfileCandidate> =
        search(type, query)
}

/** Persisted appearance settings for the Compose shell and lesson-type accents. */
interface AppearancePreferencesRepository {
    fun observe(): Flow<AppearancePreferences>
    suspend fun setTheme(theme: ScheduleAppTheme)
    suspend fun setLessonColor(type: LessonType, colorHex: String)
    suspend fun resetLessonColors()
}

/** Persisted session track and global/per-profile final dates used by the semester calendar. */
interface SemesterSchedulePreferencesRepository {
    fun observe(): Flow<SemesterSchedulePreferences>
    suspend fun update(preferences: SemesterSchedulePreferences)
}

/** Persisted per-role font-size multipliers used by Compose text. */
interface TypographyPreferencesRepository {
    fun observe(): Flow<TypographyPreferences>
    suspend fun update(preferences: TypographyPreferences)
}
