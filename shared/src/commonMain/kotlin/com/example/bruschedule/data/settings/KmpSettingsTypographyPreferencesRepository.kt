package com.example.bruschedule.data.settings

import com.example.bruschedule.domain.model.TypographyPreferences
import com.example.bruschedule.domain.repository.TypographyPreferencesRepository
import com.russhwolf.settings.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Stores the app-wide font multiplier in KMP Settings and emits changes immediately. */
class KmpSettingsTypographyPreferencesRepository(
    private val settings: Settings,
) : TypographyPreferencesRepository {
    private val mutablePreferences = MutableStateFlow(readPreferences())
    private val preferences = mutablePreferences.asStateFlow()

    override fun observe() = preferences

    override suspend fun update(preferences: TypographyPreferences) {
        val normalized = TypographyPreferences(
            fontScale = TypographyPreferences.normalize(preferences.fontScale),
        )
        settings.putString(FONT_SCALE_KEY, normalized.fontScale.toString())
        mutablePreferences.value = normalized
    }

    private fun readPreferences(): TypographyPreferences {
        val saved = settings.getString(
            FONT_SCALE_KEY,
            TypographyPreferences.DEFAULT_FONT_SCALE.toString(),
        ).toFloatOrNull() ?: TypographyPreferences.DEFAULT_FONT_SCALE
        return TypographyPreferences(fontScale = TypographyPreferences.normalize(saved))
    }

    private companion object {
        const val FONT_SCALE_KEY = "typography.font_scale"
    }
}
