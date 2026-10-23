package com.example.bruschedule.domain.usecase

import com.example.bruschedule.domain.model.Lesson

/**
 * User-authored lessons win over the server version by stable lesson id first, then sourceKey.
 * The sourceKey is based on profile + date + pair/time + parallel-card index, never on editable
 * text. Non-custom website rows get a monotonically increasing per-row version.
 */
object ScheduleMergeStrategy {
    fun merge(existing: List<Lesson>, incoming: List<Lesson>): List<Lesson> {
        val customById = existing.asSequence()
            .filter(Lesson::isCustom)
            .associateBy(Lesson::id)
        val customBySourceKey = existing.asSequence()
            .filter(Lesson::isCustom)
            .filter { it.sourceKey.isNotBlank() }
            .associateBy(Lesson::sourceKey)
        val existingWebsiteLessons = existing.filterNot(Lesson::isCustom)
        val previousWebById = existingWebsiteLessons.associateBy(Lesson::id)
        val previousWebByLessonIdentity = existingWebsiteLessons.groupBy(Lesson::noteIdentity)

        val distinctIncoming = incoming.distinctBy { lesson ->
            lesson.sourceKey.takeIf(String::isNotBlank) ?: lesson.id
        }
        val mergedWebsiteRows = distinctIncoming.map { webLesson ->
            val customOverride = customById[webLesson.id]
                ?: webLesson.sourceKey.takeIf(String::isNotBlank)?.let(customBySourceKey::get)
            if (customOverride != null) {
                customOverride
            } else {
                val previousWebsiteLesson = previousWebById[webLesson.id]
                    ?: previousWebByLessonIdentity[webLesson.noteIdentity()]?.singleOrNull()
                webLesson.copy(
                    isCustom = false,
                    version = (previousWebById[webLesson.id]?.version ?: 0) + 1,
                    notes = previousWebsiteLesson?.notes.orEmpty(),
                )
            }
        }

        // Custom-only lessons (manual additions in otherwise empty slots) must not disappear.
        val representedCustomIds = mergedWebsiteRows.asSequence()
            .filter(Lesson::isCustom)
            .map(Lesson::id)
            .toSet()
        val customOnlyRows = existing.filter { it.isCustom && it.id !in representedCustomIds }
        return (mergedWebsiteRows + customOnlyRows)
            .distinctBy(Lesson::id)
            .sortedWith(compareBy<Lesson>({ it.lessonDate }, { it.startTime }, { it.slotNumber }, { it.id }))
    }

    /** Restore an exact snapshot without incrementing website versions; current manual edits win. */
    fun restoreSnapshot(snapshot: List<Lesson>, current: List<Lesson>): List<Lesson> {
        val currentCustom = current.filter(Lesson::isCustom)
        val customById = currentCustom.associateBy(Lesson::id)
        val customBySourceKey = currentCustom
            .filter { it.sourceKey.isNotBlank() }
            .associateBy(Lesson::sourceKey)
        val restoredSnapshotRows = snapshot.map { snapshotLesson ->
            customById[snapshotLesson.id]
                ?: snapshotLesson.sourceKey.takeIf(String::isNotBlank)?.let(customBySourceKey::get)
                ?: snapshotLesson
        }
        val representedCustomIds = restoredSnapshotRows.asSequence()
            .filter(Lesson::isCustom)
            .map(Lesson::id)
            .toSet()
        val customOnlyRows = currentCustom.filter { it.id !in representedCustomIds }

        return (restoredSnapshotRows + customOnlyRows)
            .distinctBy(Lesson::id)
            .sortedWith(compareBy<Lesson>({ it.lessonDate }, { it.startTime }, { it.slotNumber }, { it.id }))
    }
}

private fun Lesson.noteIdentity(): String =
    listOf(profileId, lessonDate, slotNumber.toString(), subjectName.trim().lowercase()).joinToString("|")
