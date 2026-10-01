package com.p2premotefiles.app.network.lan

data class LanPeer(
    val serviceName: String,
    val deviceId: String,
    val host: String,
    val port: Int,
    val protocolVersion: Int,
    val fingerprintHint: String?,
)
