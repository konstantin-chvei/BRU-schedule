package com.example.bruschedule.domain.repository

import com.example.bruschedule.domain.model.AcademicTask
import com.example.bruschedule.domain.model.Lesson
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
    suspend fun setTaskCompleted(taskId: String, completed: Boolean)
    suspend fun deleteTask(taskId: String)
}

/** Searchable catalogue backed by the Group and Teachers directories on rasp.bru.by. */
interface ScheduleProfileCatalogRepository {
    suspend fun search(type: ScheduleProfileType, query: String): List<ProfileCandidate>
}
