package com.example.bruschedule.data.remote

import com.example.bruschedule.domain.model.ProfileCandidate

/** Per-source accounting kept alongside exhaustive catalogue candidates for import diagnostics. */
data class ScheduleCatalogSourceReport(
    val name: String,
    val profilesDiscovered: Int,
    val pagesLoaded: Int,
    val requestFailures: Int,
    /** True when a crawl/link cap left candidate pages unexplored. */
    val crawlTruncated: Boolean = false,
)

data class ScheduleCatalogDiscoveryResult(
    val candidates: List<ProfileCandidate>,
    val sources: List<ScheduleCatalogSourceReport>,
)
