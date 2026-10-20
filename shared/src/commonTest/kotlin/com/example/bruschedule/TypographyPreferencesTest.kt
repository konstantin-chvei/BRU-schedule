package com.example.bruschedule

import com.example.bruschedule.domain.model.TypographyPreferences
import kotlin.test.Test
import kotlin.test.assertEquals

class TypographyPreferencesTest {
    @Test
    fun fontScaleIsClampedToSupportedRange() {
        assertEquals(TypographyPreferences.MIN_FONT_SCALE, TypographyPreferences.normalize(0.5f))
        assertEquals(TypographyPreferences.MAX_FONT_SCALE, TypographyPreferences.normalize(2f))
        assertEquals(1.2f, TypographyPreferences.normalize(1.2f))
    }

    @Test
    fun nonFiniteFontScaleFallsBackToDefault() {
        assertEquals(TypographyPreferences.DEFAULT_FONT_SCALE, TypographyPreferences.normalize(Float.NaN))
        assertEquals(TypographyPreferences.DEFAULT_FONT_SCALE, TypographyPreferences.normalize(Float.POSITIVE_INFINITY))
    }
}
