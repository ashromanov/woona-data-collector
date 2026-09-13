package com.example.myapplication.ble

import androidx.annotation.StringRes
import com.example.myapplication.R

enum class BleTransportProfile(
    @param:StringRes val titleRes: Int,
    @param:StringRes val shortDescriptionRes: Int,
    val detailedDescriptionResIds: List<Int>,
    val requestsHighConnectionPriority: Boolean,
    val requestedMtu: Int?,
    val preferredPhy: PreferredPhyMode,
) {
    MAXIMUM_PERFORMANCE(
        titleRes = R.string.transport_profile_default,
        shortDescriptionRes = R.string.transport_profile_default_short,
        detailedDescriptionResIds = listOf(
            R.string.transport_profile_default_detail_1,
            R.string.transport_profile_default_detail_2,
            R.string.transport_profile_default_detail_3,
        ),
        requestsHighConnectionPriority = true,
        requestedMtu = 512,
        preferredPhy = PreferredPhyMode.LE_2M,
    ),
    COMPATIBILITY(
        titleRes = R.string.transport_profile_compatibility,
        shortDescriptionRes = R.string.transport_profile_compatibility_short,
        detailedDescriptionResIds = listOf(
            R.string.transport_profile_compatibility_detail_1,
            R.string.transport_profile_compatibility_detail_2,
            R.string.transport_profile_compatibility_detail_3,
        ),
        requestsHighConnectionPriority = true,
        requestedMtu = 247,
        preferredPhy = PreferredPhyMode.SYSTEM_DEFAULT,
    ),
    CONSERVATIVE(
        titleRes = R.string.transport_profile_conservative,
        shortDescriptionRes = R.string.transport_profile_conservative_short,
        detailedDescriptionResIds = listOf(
            R.string.transport_profile_conservative_detail_1,
            R.string.transport_profile_conservative_detail_2,
            R.string.transport_profile_conservative_detail_3,
        ),
        requestsHighConnectionPriority = true,
        requestedMtu = null,
        preferredPhy = PreferredPhyMode.SYSTEM_DEFAULT,
    ),
}

enum class PreferredPhyMode {
    LE_2M,
    SYSTEM_DEFAULT,
}
