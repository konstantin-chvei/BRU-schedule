package com.example.bruschedule.domain.model

import kotlin.time.Clock
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime

/** GROUP uses a group (or faculty schedule page); TEACHER uses the teacher-occupancy section. */
enum class ScheduleProfileType {
    GROUP,
    TEACHER,
}

data class ScheduleProfile(
    val id: String,
    val type: ScheduleProfileType,
    /** Student-facing label, e.g. "МПР-261" or "Агеева Т.Н.". */
    val name: String,
    /** Stable rasp.bru.by directory/details page. Prefer this to a dated weeks_*.html URL. */
    val sourceUrl: String? = null,
    /** Exact label/slug used to find this item on its directory page. */
    val sourceKey: String? = null,
)

enum class LessonType {
    LECTURE,
    LABORATORY,
    PRACTICE,
    UNKNOWN,
}

/**
 * lessonDate is ISO-8601 local date (yyyy-MM-dd); dayOfWeek is ISO-8601 (Mon=1 ... Sun=7).
 * sourceKey is a stable identity for the website slot, deliberately independent of the subject,
 * teacher and room so a student can edit those fields without losing the override on refresh.
 */
data class Lesson(
    val id: String,
    val profileId: String,
    val subjectName: String,
    val teacherName: String,
    val classroom: String,
    val startTime: String,
    val endTime: String,
    val type: LessonType,
    /** Theme-mapped pastel accent used by the UI. */
    val colorHex: String,
    val isCustom: Boolean,
    val version: Int,
    val lessonDate: String,
    val dayOfWeek: Int,
    val slotNumber: Int = 0,
    val sourceKey: String = id,
    /** Original CSS color from rasp.bru.by, useful for parser diagnostics and future remapping. */
    val sourceColorHex: String? = null,
)

data class AcademicTask(
    val id: String,
    val lessonId: String,
    val taskTitle: String,
    val description: String,
    /** Unix epoch milliseconds. */
    val dueDate: Long,
    val isCompleted: Boolean,
    /** Kept as a display fallback if a lesson is later removed from the live schedule. */
    val profileId: String? = null,
    val subjectNameSnapshot: String = "",
)

data class ProfileCandidate(
    val type: ScheduleProfileType,
    val name: String,
    val sourceUrl: String,
    val sourceKey: String,
    val subtitle: String = "",
)

data class ProfileSyncState(
    val profileId: String,
    val lastAttemptAtEpochMillis: Long? = null,
    val lastSuccessfulAtEpochMillis: Long? = null,
    val lastError: String? = null,
    val previousSnapshotAtEpochMillis: Long? = null,
    val canRollback: Boolean = false,
)

data class ScheduleWindow(
    val startDate: LocalDate,
    val endDate: LocalDate,
) {
    companion object {
        /** Monday of this week through Sunday of next week (the site's rolling source view, later cycle-expanded). */
        fun currentAndNextWeek(clock: Clock = Clock.System): ScheduleWindow {
            val today = clock.now().toLocalDateTime(TimeZone.currentSystemDefault()).date
            val monday = today.minus(DatePeriod(days = today.dayOfWeek.isoDayNumber - 1))
            return ScheduleWindow(
                startDate = monday,
                endDate = monday.plus(DatePeriod(days = 13)),
            )
        }
    }
}

data class RefreshCommit(
    val appliedLessonCount: Int,
    val preservedCustomLessonCount: Int,
    val previousSnapshotAtEpochMillis: Long?,
)

object LessonTypePalette {
    const val LECTURE = "#A7B9FF" // cool pastel indigo
    const val LABORATORY = "#FF8998" // soft coral
    const val PRACTICE = "#FFC979" // muted amber
    const val UNKNOWN = "#A6B0C2"

    fun accent(type: LessonType): String = when (type) {
        LessonType.LECTURE -> LECTURE
        LessonType.LABORATORY -> LABORATORY
        LessonType.PRACTICE -> PRACTICE
        LessonType.UNKNOWN -> UNKNOWN
    }
}

object DomainIds {
    fun new(prefix: String): String = "$prefix-${kotlin.random.Random.Default.nextLong().toString(36)}-${kotlin.random.Random.Default.nextLong().toString(36)}"
}
