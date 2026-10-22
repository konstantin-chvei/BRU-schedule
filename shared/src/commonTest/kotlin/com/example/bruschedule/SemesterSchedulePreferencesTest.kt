package com.example.bruschedule

import com.example.bruschedule.data.settings.ProfileSessionEndDateCodec
import kotlin.test.Test
import kotlin.test.assertEquals

class SemesterSchedulePreferencesTest {
    @Test
    fun profileSpecificEndDatesRoundTripThroughTheLocalSettingsCodec() {
        val overrides = mapOf(
            "bru-auto-profile" to "2027-02-05",
            "bsu-economics-profile" to "2027-01-20",
        )

        assertEquals(overrides, ProfileSessionEndDateCodec.decode(ProfileSessionEndDateCodec.encode(overrides)))
    }

    @Test
    fun malformedStoredProfileEndDatesAreIgnored() {
        val encoded = "bru-profile\t2027-02-05\nmissing-separator\ninvalid-date\tnot-a-date"

        assertEquals(
            mapOf("bru-profile" to "2027-02-05"),
            ProfileSessionEndDateCodec.decode(encoded),
        )
    }
}
