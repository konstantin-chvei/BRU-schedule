package com.example.bruschedule.data.remote

import com.example.bruschedule.domain.repository.DailyMessagesRemoteDataSource

/** Reads the public static daily-message list from GitHub Pages; no app release is needed to edit it. */
class GitHubDailyMessagesRemoteDataSource(
    private val transport: HtmlTransport,
) : DailyMessagesRemoteDataSource {
    override suspend fun fetchMessages(): List<String> = transport.get(DAILY_MESSAGES_URL)
        .lineSequence()
        .map { it.removePrefix("\uFEFF").trim() }
        .filter { it.isNotEmpty() && !it.startsWith('#') }
        .take(100)
        .toList()

    companion object {
        const val DAILY_MESSAGES_URL =
            "https://konstantin-chvei.github.io/BRU-schedule/content/daily-messages.txt"
    }
}
