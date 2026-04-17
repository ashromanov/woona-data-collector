package com.example.myapplication.ble

enum class BleTransportProfile(
    val title: String,
    val shortDescription: String,
    val detailedDescription: List<String>,
    val requestsHighConnectionPriority: Boolean,
    val requestedMtu: Int?,
    val preferredPhy: PreferredPhyMode,
) {
    DEFAULT(
        title = "Default",
        shortDescription = "High priority, MTU 512 request, 2M PHY request.",
        detailedDescription = listOf(
            "Requests HIGH connection priority.",
            "Requests MTU 512 and continues even if the stack negotiates lower.",
            "Requests LE 2M PHY for maximum throughput on capable phones.",
        ),
        requestsHighConnectionPriority = true,
        requestedMtu = 512,
        preferredPhy = PreferredPhyMode.LE_2M,
    ),
    COMPATIBILITY(
        title = "Compatibility",
        shortDescription = "High priority, MTU 247 request, no PHY override.",
        detailedDescription = listOf(
            "Requests HIGH connection priority.",
            "Requests MTU 247 directly instead of 512.",
            "Does not request a preferred PHY, so the phone keeps its stack default.",
        ),
        requestsHighConnectionPriority = true,
        requestedMtu = 247,
        preferredPhy = PreferredPhyMode.SYSTEM_DEFAULT,
    ),
    CONSERVATIVE(
        title = "Conservative",
        shortDescription = "High priority only, no MTU request, no PHY override.",
        detailedDescription = listOf(
            "Requests HIGH connection priority only.",
            "Skips explicit MTU negotiation and uses the stack/device default.",
            "Skips preferred PHY request and keeps the system default link mode.",
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
