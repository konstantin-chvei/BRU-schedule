package com.example.bruschedule.data.settings

import com.example.bruschedule.domain.model.DEFAULT_DAILY_MESSAGES
import com.example.bruschedule.domain.repository.DailyMessagesRemoteDataSource
import com.example.bruschedule.domain.repository.DailyMessagesRepository
import com.russhwolf.settings.Settings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Displays the persisted list immediately, then replaces it after a successful GitHub fetch. */
class KmpSettingsDailyMessagesRepository(
    private val settings: Settings,
    private val remote: DailyMessagesRemoteDataSource,
) : DailyMessagesRepository {
    private val mutableMessages = MutableStateFlow(readCachedMessages())
    private val messages = mutableMessages.asStateFlow()

    override fun observeMessages() = messages

    override suspend fun refresh() {
        try {
            val fetched = remote.fetchMessages().normalizedMessages()
            if (fetched.isEmpty()) return
            settings.putString(CACHE_KEY, fetched.joinToString("\n"))
            mutableMessages.value = fetched
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            // Keep the last good cached list (or the built-in fallback on the very first launch).
        }
    }

    private fun readCachedMessages(): List<String> =
        settings.getString(CACHE_KEY, "")
            .split('\n')
            .normalizedMessages()
            .ifEmpty { DEFAULT_DAILY_MESSAGES }

    private fun List<String>.normalizedMessages(): List<String> = asSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith('#') }
        .take(MAX_CACHED_MESSAGES)
        .toList()

    private companion object {
        const val CACHE_KEY = "daily_messages.cache"
        const val MAX_CACHED_MESSAGES = 100
    }
}
