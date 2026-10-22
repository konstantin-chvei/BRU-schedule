package com.example.bruschedule.domain.usecase

import com.example.bruschedule.domain.model.Lesson
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.minus
import kotlinx.datetime.plus

/**
 * Projects weekly or alternating two-week templates into the requested offline-first date range.
 * Occurrence identity is derived from the original stable slot key plus the occurrence date, so
 * edits remain attached to the same date/slot after every refresh.
 */
object ScheduleCycleExpander {
    private val ONE_WEEK = DatePeriod(days = 7)
    private val TWO_WEEKS = DatePeriod(days = 14)

    fun expandWeekly(
        templateLessons: List<Lesson>,
        fromDate: LocalDate,
        throughDate: LocalDate,
    ): List<Lesson> {
        if (templateLessons.isEmpty() || throughDate < fromDate) return emptyList()

        return buildList {
            templateLessons.forEach { lesson ->
                if (lesson.isCustom) {
                    add(lesson)
                    return@forEach
                }
                val templateDate = runCatching { LocalDate.parse(lesson.lessonDate) }.getOrNull() ?: run {
                    add(lesson)
                    return@forEach
                }
                var occurrenceDate = templateDate
                while (occurrenceDate.minus(ONE_WEEK) >= fromDate) occurrenceDate = occurrenceDate.minus(ONE_WEEK)
                while (occurrenceDate < fromDate) occurrenceDate = occurrenceDate.plus(ONE_WEEK)
                while (occurrenceDate <= throughDate) {
                    val occurrenceKey = if (occurrenceDate == templateDate) {
                        lesson.sourceKey
                    } else {
                        stableOccurrenceKey(lesson, templateDate, occurrenceDate)
                    }
                    add(
                        lesson.copy(
                            id = if (occurrenceDate == templateDate) lesson.id else "web:$occurrenceKey",
                            lessonDate = occurrenceDate.toString(),
                            dayOfWeek = occurrenceDate.dayOfWeek.isoDayNumber,
                            sourceKey = occurrenceKey,
                        ),
                    )
                    occurrenceDate = occurrenceDate.plus(ONE_WEEK)
                }
            }
        }.distinctBy { it.sourceKey }
            .sortedWith(compareBy<Lesson>({ it.lessonDate }, { it.startTime }, { it.slotNumber }, { it.id }))
    }

    fun expand(
        templateLessons: List<Lesson>,
        fromDate: LocalDate,
        throughDate: LocalDate,
    ): List<Lesson> {
        if (templateLessons.isEmpty() || throughDate < fromDate) return emptyList()

        return buildList {
            templateLessons.forEach { lesson ->
                if (lesson.isCustom) {
                    add(lesson)
                    return@forEach
                }
                val templateDate = runCatching { LocalDate.parse(lesson.lessonDate) }.getOrNull() ?: run {
                    add(lesson)
                    return@forEach
                }

                var occurrenceDate = templateDate
                while (occurrenceDate < fromDate) occurrenceDate = occurrenceDate.plus(TWO_WEEKS)
                while (occurrenceDate <= throughDate) {
                    val occurrenceKey = if (occurrenceDate == templateDate) {
                        lesson.sourceKey
                    } else {
                        stableOccurrenceKey(lesson, templateDate, occurrenceDate)
                    }
                    add(
                        lesson.copy(
                            id = if (occurrenceDate == templateDate) lesson.id else "web:$occurrenceKey",
                            lessonDate = occurrenceDate.toString(),
                            dayOfWeek = occurrenceDate.dayOfWeek.isoDayNumber,
                            sourceKey = occurrenceKey,
                        ),
                    )
                    occurrenceDate = occurrenceDate.plus(TWO_WEEKS)
                }
            }
        }.distinctBy { it.sourceKey }
            .sortedWith(compareBy<Lesson>({ it.lessonDate }, { it.startTime }, { it.slotNumber }, { it.id }))
    }

    private fun stableOccurrenceKey(lesson: Lesson, templateDate: LocalDate, occurrenceDate: LocalDate): String {
        val templatePrefix = "${lesson.profileId}|$templateDate|"
        val slotIdentity = lesson.sourceKey.removePrefix(templatePrefix)
        return if (slotIdentity != lesson.sourceKey) {
            "${lesson.profileId}|$occurrenceDate|$slotIdentity"
        } else {
            "${lesson.sourceKey}|cycle:$occurrenceDate"
        }
    }
}
