package com.p2premotefiles.app.network.vps

import java.nio.charset.StandardCharsets

/** Android和Go Server必須使用完全相同的簽章欄位及順序。 */
internal object VpsProtocol {
    const val VERSION = 1

    fun registerPayload(deviceId: String, identityKey: String, serverNonce: String): ByteArray =
        payload("P2PRF-REGISTER-v1", deviceId, identityKey, serverNonce)

    fun connectPayload(
        requestId: String,
        sourceId: String,
        targetId: String,
        requestNonce: String,
    ): ByteArray = payload("P2PRF-CONNECT-v1", requestId, sourceId, targetId, requestNonce)

    fun responsePayload(
        requestId: String,
        sourceId: String,
        targetId: String,
        accepted: Boolean,
        requestNonce: String,
        responseNonce: String,
    ): ByteArray = payload(
        "P2PRF-RESPONSE-v1",
        requestId,
        sourceId,
        targetId,
        if (accepted) "1" else "0",
        requestNonce,
        responseNonce,
    )

    private fun payload(vararg fields: String): ByteArray =
        fields.joinToString("\n").toByteArray(StandardCharsets.UTF_8)
}
