package com.example.demoapplication.utils

// package com.adx.shared
object Shared {
    const val PKG_DPC    = "com.example.customtvlauncher"
    const val PKG_PLAYER = "com.example.signage"
    const val PKG_VISION = "com.example.demoapplication"

    // Signature-level permission each app declares/uses
    const val PERM_ADMIN = "com.adx.permission.ADMIN"

    // Broadcast actions – DPC listens, Player/Vision send
    const val ACT_REQUEST_TEMP_UNLOCK = "com.adx.dpc.REQUEST_TEMP_UNLOCK"
    const val ACT_REQUEST_RELOCK      = "com.adx.dpc.REQUEST_RELOCK"
    const val ACT_HEARTBEAT_VISION    = "com.adx.vision.HEARTBEAT"
}