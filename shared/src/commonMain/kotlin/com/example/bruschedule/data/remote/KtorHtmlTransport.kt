package com.example.bruschedule.data.remote

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess

class KtorHtmlTransport(
    private val client: HttpClient,
) : HtmlTransport, BinaryResourceTransport {
    fun close() = client.close()

    override suspend fun get(url: String): String {
        val isBruSchedulePage = url.startsWith("https://rasp.bru.by/", ignoreCase = true)
        val isBiologySite = url.startsWith("https://bsu.bio/", ignoreCase = true) ||
            url.startsWith("https://bio.bsu.by/", ignoreCase = true)
        val isDailyMessagesFile = url == GitHubDailyMessagesRemoteDataSource.DAILY_MESSAGES_API_URL
        val isDirectHttpsPage = HTTPS_PAGE_URL.matches(url)
        require(isBruSchedulePage || isDailyMessagesFile || isDirectHttpsPage) {
            "Для расписания принимаются только публичные HTTPS-ссылки"
        }
        val response = client.get(url) {
            header(HttpHeaders.UserAgent, "Schedule/1.0 (student timetable app)")
            header(
                HttpHeaders.Accept,
                if (isDailyMessagesFile) "application/vnd.github.raw+json" else "text/html,application/xhtml+xml",
            )
            header("Accept-Language", "ru-RU,ru;q=0.9,en;q=0.8")
            if (isBiologySite) header("Referer", "https://bsu.bio/")
            if (isDailyMessagesFile) header("X-GitHub-Api-Version", "2022-11-28")
        }
        if (!response.status.isSuccess()) {
            val host = if (isDailyMessagesFile) "GitHub" else url.substringAfter("://").substringBefore('/')
            error("$host ответил кодом ${response.status.value}")
        }
        return response.bodyAsText()
    }

    override suspend fun getBytes(url: String): ByteArray {
        require(HTTPS_PAGE_URL.matches(url)) {
            "Для файлов расписания принимаются только публичные HTTPS-ссылки"
        }
        val response = client.get(url) {
            header(HttpHeaders.UserAgent, "Schedule/1.0 (student timetable app)")
            header(HttpHeaders.Accept, "application/octet-stream,application/pdf,*/*")
        }
        if (!response.status.isSuccess()) {
            val host = url.substringAfter("://").substringBefore('/')
            error("$host ответил кодом ${response.status.value}")
        }
        return response.bodyAsBytes().also { bytes ->
            require(bytes.size <= MAX_BINARY_RESPONSE_BYTES) {
                "Файл расписания превышает допустимый размер 30 МБ"
            }
        }
    }

    private companion object {
        const val MAX_BINARY_RESPONSE_BYTES = 30 * 1024 * 1024
        val HTTPS_PAGE_URL = Regex(
            """^https://[A-Za-z0-9.-]+(?::\d{1,5})?(?:[/?#][^\s]*)?$""",
            RegexOption.IGNORE_CASE,
        )
    }
}
