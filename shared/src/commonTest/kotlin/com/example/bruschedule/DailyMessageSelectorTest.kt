package com.example.bruschedule

import com.example.bruschedule.domain.model.DEFAULT_DAILY_MESSAGES
import com.example.bruschedule.domain.usecase.DailyMessageSelector
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class DailyMessageSelectorTest {
    @Test
    fun bundledOfflineFallbackContainsThirtyMessages() {
        assertEquals(30, DEFAULT_DAILY_MESSAGES.size)
        assertTrue(DEFAULT_DAILY_MESSAGES.all { it.isNotBlank() })
    }

    @Test
    fun messageIsStableDuringTheDayAndRotatesOnTheNextDay() {
        val messages = listOf("Первая фраза", "Вторая фраза", "Третья фраза")
        val monday = LocalDate(2026, 10, 19)
        val tuesday = LocalDate(2026, 10, 20)

        assertEquals(DailyMessageSelector.forDate(monday, messages), DailyMessageSelector.forDate(monday, messages))
        assertNotEquals(DailyMessageSelector.forDate(monday, messages), DailyMessageSelector.forDate(tuesday, messages))
    }

    @Test
    fun blankRemoteEntriesAreIgnoredAndEmptyListsFallBackLocally() {
        val date = LocalDate(2026, 10, 19)
        assertEquals("Фраза", DailyMessageSelector.forDate(date, listOf("", " Фраза ")))
        assertEquals(
            DailyMessageSelector.forDate(date, DEFAULT_DAILY_MESSAGES),
            DailyMessageSelector.forDate(date, emptyList()),
        )
    }
}
