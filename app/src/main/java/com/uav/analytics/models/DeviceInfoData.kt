package com.uav.analytics.models

data class DeviceInfoData(
    // Memory Information
    var thresholdRamBytes: Long = 0,
    var totalRamBytes: Long = 0,
    var totalRomBytes: Long = 0,
    var usedRamBytes: Long = 0,
    var usedRomBytes: Long = 0,

    // Additional Device Info
    var androidId: String = "",
    var storageTotal: Long = 0,
    var storageAvailable: Long = 0,
)