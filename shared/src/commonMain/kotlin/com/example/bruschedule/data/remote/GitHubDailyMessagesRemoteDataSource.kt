package com.example.bruschedule.data.remote

import com.example.bruschedule.domain.repository.DailyMessagesRemoteDataSource

/** Reads the plain-text daily list through GitHub's Contents API; no app release is needed to edit it. */
class GitHubDailyMessagesRemoteDataSource(
    private val transport: HtmlTransport,
) : DailyMessagesRemoteDataSource {
    override suspend fun fetchMessages(): List<String> = transport.get(DAILY_MESSAGES_API_URL)
        .lineSequence()
        .map { it.removePrefix("\uFEFF").trim() }
        .filter { it.isNotEmpty() && !it.startsWith('#') }
        .take(100)
        .toList()

    companion object {
        const val DAILY_MESSAGES_API_URL =
            "https://api.github.com/repos/konstantin-chvei/BRU-schedule/contents/content/daily-messages.txt?ref=master"
    }
}
