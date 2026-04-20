package com.example.myapplication.ble

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BleTransportProfilePreferencesTest {
    @Test
    fun selectedTransportProfile_roundTripsStoredValue() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteSharedPreferences("ble_transport_profile_preferences")
        val preferences = BleTransportProfilePreferences(context)

        preferences.setSelectedTransportProfile(BleTransportProfile.MAXIMUM_PERFORMANCE)

        assertEquals(
            BleTransportProfile.MAXIMUM_PERFORMANCE,
            preferences.selectedTransportProfile(),
        )
    }
}
