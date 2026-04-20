package com.example.myapplication.ble

import android.content.Context

class BleTransportProfilePreferences(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun selectedTransportProfile(): BleTransportProfile {
        val storedProfileName = preferences.getString(KEY_TRANSPORT_PROFILE, null)
        return BleTransportProfile.entries.firstOrNull { it.name == storedProfileName }
            ?: BleTransportProfile.COMPATIBILITY
    }

    fun setSelectedTransportProfile(profile: BleTransportProfile) {
        preferences.edit()
            .putString(KEY_TRANSPORT_PROFILE, profile.name)
            .apply()
    }

    private companion object {
        const val PREFERENCES_NAME = "ble_transport_profile_preferences"
        const val KEY_TRANSPORT_PROFILE = "selected_transport_profile"
    }
}
