package com.p2premotefiles.app.remote

import com.p2premotefiles.app.app.DeviceIdentityRepository
import com.p2premotefiles.app.domain.model.FileAccessException
import com.p2premotefiles.app.domain.model.FileEntry
import com.p2premotefiles.app.domain.model.FilePermissions
import com.p2premotefiles.app.domain.model.StorageVolumeInfo
import com.p2premotefiles.app.network.lan.LanCrypto
import com.p2premotefiles.app.network.lan.LanPeer
import com.p2premotefiles.app.network.lan.LanProtocol
import com.p2premotefiles.app.network.lan.LanRpcCodec
import com.p2premotefiles.app.network.lan.PlainJsonChannel
import com.p2premotefiles.app.network.lan.SecureJsonChannel
import com.p2premotefiles.app.network.lan.createClientHello
import com.p2premotefiles.app.network.transport.ByteDuplexConnection
import com.p2premotefiles.app.network.transport.SocketByteDuplexConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicLong

class LanRemoteFileClient private constructor(
    private val connection: ByteDuplexConnection,
    private val channel: SecureJsonChannel,
    val remoteDeviceId: String,
    val remoteFingerprint: String,
) : RemoteFileClient {
    private val requestIds = AtomicLong(1L)
    private val requestMutex = Mutex()

    override suspend fun listVolumes(): List<StorageVolumeInfo> = rpc("LIST_VOLUMES") { result ->
        LanRpcCodec.volumes(result as org.json.JSONArray)
    }

    override suspend fun listDirectory(path: String): List<FileEntry> = rpc(
        "LIST_DIRECTORY",
        JSONObject().put("path", path),
    ) { result -> LanRpcCodec.fileEntries(result as org.json.JSONArray) }

    override suspend fun stat(path: String): FileEntry = rpc(
        "STAT",
        JSONObject().put("path", path),
    ) { result -> LanRpcCodec.fileEntry(result as JSONObject) }

    override suspend fun createDirectory(path: String) {
        rpc<Unit>("MKDIR", JSONObject().put("path", path)) { Unit }
    }

    override suspend fun copy(source: String, destination: String) {
        rpc<Unit>(
            "COPY",
            JSONObject().put("source", source).put("destination", destination),
        ) { Unit }
    }

    override suspend fun move(source: String, destination: String) {
        rpc<Unit>(
            "MOVE",
            JSONObject().put("source", source).put("destination", destination),
        ) { Unit }
    }

    override suspend fun rename(source: String, destination: String) {
        rpc<Unit>(
            "RENAME",
            JSONObject().put("source", source).put("destination", destination),
        ) { Unit }
    }

    override suspend fun delete(path: String) {
        rpc<Unit>("DELETE", JSONObject().put("path", path)) { Unit }
    }

    override suspend fun thumbnail(path: String, maxSizePx: Int): ByteArray? = rpc(
        "THUMBNAIL",
        JSONObject().put("path", path).put("maxSizePx", maxSizePx),
    ) { result ->
        if (result == null || result == JSONObject.NULL) null else LanCrypto.decode(result as String)
    }

    override suspend fun permissions(path: String): FilePermissions? = rpc(
        "PERMISSIONS",
        JSONObject().put("path", path),
    ) { result ->
        if (result == null || result == JSONObject.NULL) null else LanRpcCodec.permissions(result as JSONObject)
    }

    override suspend fun setPermissions(
        path: String,
        mode: Int,
        applyToFiles: Boolean,
        applyToDirectories: Boolean,
    ) {
        rpc<Unit>(
            "SET_PERMISSIONS",
            JSONObject()
                .put("path", path)
                .put("mode", mode and 0x1FF)
                .put("applyToFiles", applyToFiles)
                .put("applyToDirectories", applyToDirectories),
        ) { Unit }
    }

    override suspend fun downloadFile(path: String, output: OutputStream, onProgress: (Long, Long) -> Unit) {
        val begin = rpc("DOWNLOAD_BEGIN", JSONObject().put("path", path)) { it as JSONObject }
        val transferId = begin.getString("transferId")
        val total = begin.getLong("size")
        var done = 0L
        try {
            while (done < total) {
                val chunk = rpc(
                    "DOWNLOAD_CHUNK",
                    JSONObject().put("transferId", transferId).put("maxBytes", TRANSFER_CHUNK_BYTES),
                ) { it as JSONObject }
                val bytes = LanCrypto.decode(chunk.getString("data"))
                if (bytes.isEmpty() && !chunk.optBoolean("eof")) error("Empty remote transfer chunk")
                output.write(bytes)
                done += bytes.size
                onProgress(done, total)
                if (chunk.optBoolean("eof")) break
            }
            output.flush()
            rpc<Unit>("DOWNLOAD_END", JSONObject().put("transferId", transferId)) { Unit }
        } catch (t: Throwable) {
            runCatching { rpc<Unit>("TRANSFER_CANCEL", JSONObject().put("transferId", transferId)) { Unit } }
            throw t
        }
    }

    override suspend fun uploadFile(input: InputStream, size: Long, destination: String, onProgress: (Long, Long) -> Unit) {
        val begin = rpc(
            "UPLOAD_BEGIN",
            JSONObject().put("path", destination).put("size", size),
        ) { it as JSONObject }
        val transferId = begin.getString("transferId")
        val buffer = ByteArray(TRANSFER_CHUNK_BYTES)
        var done = 0L
        try {
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                val bytes = if (read == buffer.size) buffer else buffer.copyOf(read)
                rpc<Unit>(
                    "UPLOAD_CHUNK",
                    JSONObject().put("transferId", transferId).put("data", LanCrypto.encode(bytes)),
                ) { Unit }
                done += read
                onProgress(done, size)
            }
            rpc<Unit>("UPLOAD_END", JSONObject().put("transferId", transferId)) { Unit }
        } catch (t: Throwable) {
            runCatching { rpc<Unit>("TRANSFER_CANCEL", JSONObject().put("transferId", transferId)) { Unit } }
            throw t
        }
    }


    override suspend fun compress(paths: List<String>, destination: String, storeOnly: Boolean) {
        require(paths.isNotEmpty())
        val array = org.json.JSONArray()
        paths.forEach(array::put)
        rpc<Unit>(
            "COMPRESS",
            JSONObject()
                .put("paths", array)
                .put("destination", destination)
                .put("storeOnly", storeOnly),
        ) { Unit }
    }

    override suspend fun healthCheck() {
        rpc<Unit>("PING") { Unit }
    }

    override fun close() {
        runCatching { connection.close() }
    }

    private suspend fun <T> rpc(
        operation: String,
        args: JSONObject = JSONObject(),
        decode: (Any?) -> T,
    ): T = withContext(Dispatchers.IO) {
        requestMutex.withLock {
            try {
                val requestId = requestIds.getAndIncrement()
                channel.send(
                    JSONObject()
                        .put("id", requestId)
                        .put("op", operation)
                        .put("args", args),
                )
                val response = channel.receive()
                if (response.optLong("id", -1L) != requestId) {
                    throw IllegalStateException("Unexpected remote RPC response")
                }
                if (!response.optBoolean("ok", false)) {
                    throw FileAccessException(
                        LanRpcCodec.error(response.optString("error")),
                        detail = response.optString("detail").takeIf { it.isNotBlank() },
                    )
                }
                decode(response.opt("result"))
            } catch (error: FileAccessException) {
                throw error
            } catch (error: RemoteSessionDisconnectedException) {
                throw error
            } catch (error: Throwable) {
                runCatching { connection.close() }
                throw RemoteSessionDisconnectedException(error)
            }
        }
    }

    companion object {
        private const val TRANSFER_CHUNK_BYTES = 48 * 1024

        suspend fun connect(
            peer: LanPeer,
            localDeviceId: String,
            identity: DeviceIdentityRepository,
        ): LanRemoteFileClient = withContext(Dispatchers.IO) {
            require(peer.protocolVersion == LanProtocol.VERSION) { "Unsupported LAN protocol" }
            val socket = Socket()
            try {
                socket.tcpNoDelay = true
                socket.connect(InetSocketAddress(peer.host, peer.port), LanProtocol.CONNECT_TIMEOUT_MS)
                socket.soTimeout = LanProtocol.HANDSHAKE_TIMEOUT_MS
                val client = connect(
                    connection = SocketByteDuplexConnection(socket),
                    localDeviceId = localDeviceId,
                    identity = identity,
                    expectedDeviceId = peer.deviceId,
                    expectedFingerprint = peer.fingerprintHint,
                )
                socket.soTimeout = 0
                client
            } catch (t: Throwable) {
                runCatching { socket.close() }
                if (t is LanConnectionException) throw t
                throw LanConnectionException.Unreachable(t)
            }
        }

        suspend fun connect(
            connection: ByteDuplexConnection,
            localDeviceId: String,
            identity: DeviceIdentityRepository,
            expectedDeviceId: String,
            expectedFingerprint: String? = null,
        ): LanRemoteFileClient = withContext(Dispatchers.IO) {
            try {
                val plain = PlainJsonChannel(connection.inputStream, connection.outputStream)

                val ephemeral = LanCrypto.generateEphemeralKeyPair()
                val nonce = LanCrypto.randomNonce()
                val (clientHello, clientPayload) = createClientHello(localDeviceId, identity, ephemeral, nonce)
                plain.send(clientHello)

                val serverHello = plain.receive()
                if (serverHello.optString("type") == "error") throw LanConnectionException.ProtocolMismatch
                if (serverHello.optString("type") != "server_hello") throw LanConnectionException.HandshakeFailed
                if (serverHello.optInt("protocol") != LanProtocol.VERSION) throw LanConnectionException.ProtocolMismatch
                val remoteDeviceId = serverHello.optString("deviceId")
                if (remoteDeviceId != expectedDeviceId) throw LanConnectionException.IdentityMismatch

                val remoteIdentityKey = runCatching {
                    identity.decodePublicKey(serverHello.getString("identityKey"))
                }.getOrElse { throw LanConnectionException.HandshakeFailed }
                val remoteFingerprint = identity.fingerprint(remoteIdentityKey)
                if (!expectedFingerprint.isNullOrBlank() &&
                    !remoteFingerprint.startsWith(expectedFingerprint, ignoreCase = true)
                ) {
                    throw LanConnectionException.IdentityMismatch
                }
                val remoteEphemeralKey = runCatching {
                    LanCrypto.decodeEcPublicKey(serverHello.getString("ephemeralKey"))
                }.getOrElse { throw LanConnectionException.HandshakeFailed }
                val serverNonce = runCatching { LanCrypto.decode(serverHello.getString("nonce")) }
                    .getOrElse { throw LanConnectionException.HandshakeFailed }
                val serverPayload = LanCrypto.serverHelloPayload(
                    serverDeviceId = remoteDeviceId,
                    serverIdentityPublicKey = remoteIdentityKey.encoded,
                    serverEphemeralPublicKey = remoteEphemeralKey.encoded,
                    serverNonce = serverNonce,
                    clientPayload = clientPayload,
                )
                val serverSignature = runCatching { LanCrypto.decode(serverHello.getString("signature")) }
                    .getOrElse { throw LanConnectionException.HandshakeFailed }
                if (!identity.verify(remoteIdentityKey, serverPayload, serverSignature)) {
                    throw LanConnectionException.HandshakeFailed
                }

                val (sessionKey, transcriptHash) = LanCrypto.deriveSessionKey(
                    localEphemeral = ephemeral,
                    remoteEphemeralPublicKey = remoteEphemeralKey,
                    clientPayload = clientPayload,
                    serverPayload = serverPayload,
                )
                val secure = SecureJsonChannel(
                    connection.inputStream,
                    connection.outputStream,
                    sessionKey,
                    transcriptHash,
                )
                val session = secure.receive()
                if (session.optString("type") != "session") throw LanConnectionException.HandshakeFailed
                if (session.optString("status") != "accepted") throw LanConnectionException.Rejected
                LanRemoteFileClient(connection, secure, remoteDeviceId, remoteFingerprint)
            } catch (t: Throwable) {
                runCatching { connection.close() }
                if (t is LanConnectionException) throw t
                throw LanConnectionException.Unreachable(t)
            }
        }
    }
}

sealed class LanConnectionException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    data object Rejected : LanConnectionException("rejected")
    data object IdentityMismatch : LanConnectionException("identity_mismatch")
    data object ProtocolMismatch : LanConnectionException("protocol_mismatch")
    data object HandshakeFailed : LanConnectionException("handshake_failed")
    class Unreachable(cause: Throwable) : LanConnectionException("unreachable", cause)
}
