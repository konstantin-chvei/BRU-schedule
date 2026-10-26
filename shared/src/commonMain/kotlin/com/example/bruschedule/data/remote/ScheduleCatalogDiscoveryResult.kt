package com.example.bruschedule.data.remote

import com.example.bruschedule.domain.model.ProfileCandidate

/** Per-source accounting kept alongside exhaustive catalogue candidates for import diagnostics. */
data class ScheduleCatalogSourceReport(
    val name: String,
    val profilesDiscovered: Int,
    val pagesLoaded: Int,
    val requestFailures: Int,
)

data class ScheduleCatalogDiscoveryResult(
    val candidates: List<ProfileCandidate>,
    val sources: List<ScheduleCatalogSourceReport>,
)
