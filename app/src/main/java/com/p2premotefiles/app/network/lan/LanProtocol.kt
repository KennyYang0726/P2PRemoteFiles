package com.p2premotefiles.app.network.lan

internal object LanProtocol {
    const val VERSION = 5
    const val SERVICE_TYPE = "_p2premotefiles._tcp."
    const val SERVICE_NAME_PREFIX = "P2PRemoteFiles-"
    const val ATTRIBUTE_DEVICE_ID = "id"
    const val ATTRIBUTE_PROTOCOL_VERSION = "v"
    const val ATTRIBUTE_FINGERPRINT = "fp"
    const val FINGERPRINT_HINT_LENGTH = 16
    const val MAX_PLAIN_FRAME_BYTES = 256 * 1024
    const val MAX_SECURE_FRAME_BYTES = 8 * 1024 * 1024
    const val APPROVAL_TIMEOUT_MS = 60_000L
    const val CONNECT_TIMEOUT_MS = 8_000
    const val HANDSHAKE_TIMEOUT_MS = 65_000
}
