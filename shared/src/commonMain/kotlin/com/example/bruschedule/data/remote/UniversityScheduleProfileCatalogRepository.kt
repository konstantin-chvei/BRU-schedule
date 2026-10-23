package com.example.bruschedule.data.remote

import com.example.bruschedule.domain.model.ProfileCandidate
import com.example.bruschedule.domain.model.ScheduleProfileType
import com.example.bruschedule.domain.model.UniversityIds
import com.example.bruschedule.domain.repository.ScheduleProfileCatalogRepository

/** Routes group search to the live catalogue for the selected university. */
class UniversityScheduleProfileCatalogRepository(
    private val catalogues: Map<String, ScheduleProfileCatalogRepository>,
) : ScheduleProfileCatalogRepository {
    override suspend fun search(type: ScheduleProfileType, query: String): List<ProfileCandidate> =
        search(UniversityIds.BRU, type, query)

    override suspend fun search(
        universityId: String,
        type: ScheduleProfileType,
        query: String,
    ): List<ProfileCandidate> {
        val catalogue = catalogues[universityId]
            ?: error("Поиск групп для выбранного вуза пока не подключён")
        return catalogue.search(universityId, type, query)
    }
}
