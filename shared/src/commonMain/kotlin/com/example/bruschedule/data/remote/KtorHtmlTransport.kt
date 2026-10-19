package com.example.bruschedule.data.remote

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess

class KtorHtmlTransport(
    private val client: HttpClient,
) : HtmlTransport {
    override suspend fun get(url: String): String {
        val isSchedulePage = url.startsWith("https://rasp.bru.by/")
        val isDailyMessagesFile = url == GitHubDailyMessagesRemoteDataSource.DAILY_MESSAGES_API_URL
        require(isSchedulePage || isDailyMessagesFile) {
            "Разрешены только страницы расписания rasp.bru.by и список фраз проекта на GitHub"
        }
        val response = client.get(url) {
            header(HttpHeaders.UserAgent, "BRU-Schedule/1.0 (student timetable app)")
            header(
                HttpHeaders.Accept,
                if (isDailyMessagesFile) "application/vnd.github.raw+json" else "text/html,application/xhtml+xml",
            )
            if (isDailyMessagesFile) header("X-GitHub-Api-Version", "2022-11-28")
        }
        if (!response.status.isSuccess()) {
            val host = if (isDailyMessagesFile) "GitHub" else "rasp.bru.by"
            error("$host ответил кодом ${response.status.value} при запросе $url")
        }
        return response.bodyAsText()
    }
}
