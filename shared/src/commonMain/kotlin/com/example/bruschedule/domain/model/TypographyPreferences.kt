package com.example.bruschedule.domain.model

/** Global multiplier applied to every Compose text size, including captions and navigation labels. */
data class TypographyPreferences(
    val fontScale: Float = DEFAULT_FONT_SCALE,
) {
    companion object {
        const val MIN_FONT_SCALE = 1.0f
        const val DEFAULT_FONT_SCALE = 1.1f
        const val MAX_FONT_SCALE = 1.4f

        fun normalize(value: Float): Float =
            if (value.isNaN() || value.isInfinite()) DEFAULT_FONT_SCALE
            else value.coerceIn(MIN_FONT_SCALE, MAX_FONT_SCALE)
    }
}
