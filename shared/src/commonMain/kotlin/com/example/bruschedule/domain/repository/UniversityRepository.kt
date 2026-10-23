package com.example.bruschedule.domain.repository

import com.example.bruschedule.domain.model.University
import kotlinx.coroutines.flow.Flow

/** Available university sources and the institution currently selected on the home screen. */
interface UniversityRepository {
    fun observeUniversities(): Flow<List<University>>
    fun observeSelectedUniversityId(): Flow<String>

    suspend fun saveUniversity(university: University)
    suspend fun selectUniversity(universityId: String)
}
