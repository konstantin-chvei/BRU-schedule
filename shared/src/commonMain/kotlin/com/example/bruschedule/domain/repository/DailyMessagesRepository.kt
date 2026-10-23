package com.example.bruschedule.domain.repository

import kotlinx.coroutines.flow.Flow

/** Cached daily-message list, immediately available offline and refreshable from the network. */
interface DailyMessagesRepository {
    fun observeMessages(): Flow<List<String>>
    suspend fun refresh()
}

/** Outbound port for the public GitHub text file. */
interface DailyMessagesRemoteDataSource {
    suspend fun fetchMessages(): List<String>
}
