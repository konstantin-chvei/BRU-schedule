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
        require(url.startsWith("https://rasp.bru.by/")) {
            "Разрешены только страницы расписания rasp.bru.by"
        }
        val response = client.get(url) {
            header(HttpHeaders.UserAgent, "BRU-Schedule/1.0 (student timetable app)")
            header(HttpHeaders.Accept, "text/html,application/xhtml+xml")
        }
        if (!response.status.isSuccess()) {
            error("rasp.bru.by ответил кодом ${response.status.value} при запросе $url")
        }
        return response.bodyAsText()
    }
}
