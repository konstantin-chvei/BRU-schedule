package com.example.bruschedule.data

import app.cash.sqldelight.db.SqlDriver
import com.example.bruschedule.data.local.SqlDelightScheduleRepository
import com.example.bruschedule.data.local.db.ScheduleDatabase
import com.example.bruschedule.data.remote.BruScheduleProfileCatalogRepository
import com.example.bruschedule.data.remote.BruScheduleRemoteDataSource
import com.example.bruschedule.data.remote.HtmlTransport
import com.example.bruschedule.data.remote.createPlatformHtmlTransport
import com.example.bruschedule.data.scraper.BruScheduleDirectoryParser
import com.example.bruschedule.data.scraper.BruScheduleScraper
import com.example.bruschedule.data.settings.KmpSettingsParserVersionFlags
import com.example.bruschedule.domain.repository.ParserVersionRepository
import com.example.bruschedule.domain.repository.AcademicTaskRepository
import com.example.bruschedule.domain.repository.ScheduleProfileCatalogRepository
import com.example.bruschedule.domain.repository.ScheduleRepository
import com.example.bruschedule.domain.repository.ScheduleSyncRepository
import com.example.bruschedule.domain.usecase.RefreshScheduleUseCase
import com.russhwolf.settings.Settings

/** Dependency boundary for Compose. Platform entry points provide a persistent SQLite driver. */
data class AppGraph(
    val scheduleRepository: ScheduleRepository,
    val syncRepository: ScheduleSyncRepository,
    val taskRepository: AcademicTaskRepository,
    val profileCatalog: ScheduleProfileCatalogRepository,
    val refreshSchedule: RefreshScheduleUseCase,
    val versionFlags: ParserVersionRepository,
)

fun createAppGraph(
    driver: SqlDriver,
    settings: Settings = Settings(),
    transport: HtmlTransport = createPlatformHtmlTransport(),
): AppGraph {
    val database = ScheduleDatabase(driver)
    val localRepository = SqlDelightScheduleRepository(database)
    val versionFlags = KmpSettingsParserVersionFlags(settings)

    // SQLDelight owns real migrations. This KMP Settings marker is useful for diagnostics/upgrade
    // gates and is intentionally not used as a replacement for database schema migrations.
    if (versionFlags.databaseMarker() < CURRENT_DATABASE_MARKER) {
        versionFlags.setDatabaseMarker(CURRENT_DATABASE_MARKER)
    }

    val directoryParser = BruScheduleDirectoryParser()
    val scraper = BruScheduleScraper()
    val remote = BruScheduleRemoteDataSource(transport, directoryParser)
    return AppGraph(
        scheduleRepository = localRepository,
        syncRepository = localRepository,
        taskRepository = localRepository,
        profileCatalog = BruScheduleProfileCatalogRepository(transport, directoryParser),
        refreshSchedule = RefreshScheduleUseCase(
            schedules = localRepository,
            syncRepository = localRepository,
            remote = remote,
            scraper = scraper,
            versionFlags = versionFlags,
        ),
        versionFlags = versionFlags,
    )
}

private const val CURRENT_DATABASE_MARKER = 1
