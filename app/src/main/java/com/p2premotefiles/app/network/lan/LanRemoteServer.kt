package com.p2premotefiles.app.network.lan

import android.os.ParcelFileDescriptor
import com.p2premotefiles.app.app.DeviceIdentityRepository
import com.p2premotefiles.app.data.filesystem.StorageVolumeRepository
import com.p2premotefiles.app.data.filesystem.ThumbnailGenerator
import com.p2premotefiles.app.domain.filesystem.FileSystemBackend
import com.p2premotefiles.app.domain.model.FileAccessException
import com.p2premotefiles.app.domain.model.FileError
import com.p2premotefiles.app.network.transport.ByteDuplexConnection
import com.p2premotefiles.app.network.transport.SocketByteDuplexConnection
import com.p2premotefiles.app.peer.PeerCapabilities
import com.p2premotefiles.app.peer.PeerRepository
import com.p2premotefiles.app.util.PathUtils
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.SocketException
import java.security.KeyPair
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * APP前景時提供LAN遠端檔案服務，也可接收由VPS Relay建立的外部Byte stream。
 * 一般LAN連線需要被控端手動接受；Relay連線只能使用已經由VPS連線要求明確核准的Peer。
 */
class LanRemoteServer(
    private val identity: DeviceIdentityRepository,
    private val backend: FileSystemBackend,
    private val volumes: StorageVolumeRepository,
    private val thumbnails: ThumbnailGenerator,
    private val peers: PeerRepository,
) {
    private data class DownloadTransfer(val stream: InputStream, val total: Long, var sent: Long = 0L)
    private data class UploadTransfer(
        val stream: OutputStream,
        val expected: Long,
        val temporaryPath: String,
        val destinationPath: String,
        var received: Long = 0L,
    )

    data class IncomingRequest(
        val requestId: String,
        val deviceId: String,
        val fingerprint: String,
    )

    data class ExpectedPeer(
        val deviceId: String,
        val fingerprint: String,
    )

    enum class SessionRoute {
        LAN_DIRECT,
        VPS_RELAY,
    }

    data class ActiveSession(
        val sessionId: String,
        val deviceId: String,
        val fingerprint: String,
        val route: SessionRoute,
        val connectedAt: Long,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val activeConnections = ConcurrentHashMap.newKeySet<ByteDuplexConnection>()
    private val sessionConnections = ConcurrentHashMap<String, ByteDuplexConnection>()
    private val approvals = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()
    private val downloads = ConcurrentHashMap<String, DownloadTransfer>()
    private val uploads = ConcurrentHashMap<String, UploadTransfer>()
    private val incomingLock = Any()
    private val _incomingRequests = MutableStateFlow<List<IncomingRequest>>(emptyList())
    val incomingRequests: StateFlow<List<IncomingRequest>> = _incomingRequests.asStateFlow()

    private val _activeSessions = MutableStateFlow<List<ActiveSession>>(emptyList())
    val activeSessions: StateFlow<List<ActiveSession>> = _activeSessions.asStateFlow()

    @Volatile
    private var serverSocket: ServerSocket? = null
    @Volatile
    private var acceptJob: Job? = null
    @Volatile
    private var localDeviceId: String? = null

    @Synchronized
    fun start(deviceId: String): Int {
        require(deviceId.isNotBlank())
        val existing = serverSocket
        if (existing != null && !existing.isClosed && localDeviceId == deviceId) return existing.localPort

        stopLanListenerOnly()
        val socket = ServerSocket(0)
        serverSocket = socket
        localDeviceId = deviceId
        acceptJob = scope.launch { acceptLoop(socket, deviceId) }
        return socket.localPort
    }

    /**
     * 停止所有遠端檔案服務。APP離開前景時使用。
     */
    @Synchronized
    fun stop() {
        stopLanListenerOnly()
        localDeviceId = null
        activeConnections.toList().forEach { runCatching { it.close() } }
        activeConnections.clear()
        sessionConnections.clear()
        _activeSessions.value = emptyList()
        approvals.values.forEach { it.complete(false) }
        approvals.clear()
        downloads.values.forEach { runCatching { it.stream.close() } }
        downloads.clear()
        uploads.values.forEach { transfer ->
            runCatching { transfer.stream.close() }
            scope.launch { runCatching { backend.delete(transfer.temporaryPath) } }
        }
        uploads.clear()
        synchronized(incomingLock) { _incomingRequests.value = emptyList() }
    }

    fun stopLanListener() {
        stopLanListenerOnly()
    }

    fun respond(requestId: String, accepted: Boolean) {
        approvals.remove(requestId)?.complete(accepted)
        removeIncomingRequest(requestId)
    }

    fun disconnectSession(sessionId: String) {
        sessionConnections.remove(sessionId)?.let { connection ->
            runCatching { connection.close() }
        }
        removeActiveSession(sessionId)
    }

    /**
     * 接收已在VPS signaling階段由使用者核准的Relay連線。
     * 仍會重新驗證端對端握手裡的Device ID與Public Key fingerprint，VPS無法單靠宣稱ID繞過驗證。
     */
    fun acceptExternalConnection(
        connection: ByteDuplexConnection,
        ownDeviceId: String,
        expectedPeer: ExpectedPeer,
    ) {
        activeConnections += connection
        scope.launch {
            try {
                handleClient(connection, ownDeviceId, expectedPeer, SessionRoute.VPS_RELAY)
            } finally {
                activeConnections -= connection
                runCatching { connection.close() }
            }
        }
    }

    @Synchronized
    private fun stopLanListenerOnly() {
        acceptJob?.cancel()
        acceptJob = null
        runCatching { serverSocket?.close() }
        serverSocket = null
    }

    private suspend fun acceptLoop(server: ServerSocket, ownDeviceId: String) {
        while (!server.isClosed) {
            val socket = try {
                server.accept()
            } catch (_: SocketException) {
                break
            } catch (_: Throwable) {
                continue
            }
            runCatching {
                socket.tcpNoDelay = true
                socket.soTimeout = LanProtocol.HANDSHAKE_TIMEOUT_MS
            }
            val connection = SocketByteDuplexConnection(socket)
            activeConnections += connection
            scope.launch {
                try {
                    handleClient(connection, ownDeviceId, expectedPeer = null, route = SessionRoute.LAN_DIRECT)
                } finally {
                    activeConnections -= connection
                    runCatching { connection.close() }
                }
            }
        }
    }

    private suspend fun handleClient(
        connection: ByteDuplexConnection,
        ownDeviceId: String,
        expectedPeer: ExpectedPeer?,
        route: SessionRoute,
    ) {
        val plain = PlainJsonChannel(connection.inputStream, connection.outputStream)
        val clientHello = plain.receive()
        if (clientHello.optString("type") != "client_hello") return
        if (clientHello.optInt("protocol") != LanProtocol.VERSION) {
            plain.send(JSONObject().put("type", "error").put("reason", "protocol"))
            return
        }

        val clientDeviceId = clientHello.optString("deviceId")
        if (clientDeviceId.isBlank() || clientDeviceId == ownDeviceId) return
        val clientIdentityKey = runCatching {
            identity.decodePublicKey(clientHello.getString("identityKey"))
        }.getOrNull() ?: return
        val clientFingerprint = identity.fingerprint(clientIdentityKey)
        if (expectedPeer != null) {
            if (clientDeviceId != expectedPeer.deviceId ||
                !clientFingerprint.equals(expectedPeer.fingerprint, ignoreCase = true)
            ) {
                return
            }
        }

        val clientEphemeralKey = runCatching {
            LanCrypto.decodeEcPublicKey(clientHello.getString("ephemeralKey"))
        }.getOrNull() ?: return
        val clientNonce = runCatching { LanCrypto.decode(clientHello.getString("nonce")) }.getOrNull() ?: return
        val clientPayload = LanCrypto.clientHelloPayload(
            deviceId = clientDeviceId,
            identityPublicKey = clientIdentityKey.encoded,
            ephemeralPublicKey = clientEphemeralKey.encoded,
            nonce = clientNonce,
        )
        val clientSignature = runCatching { LanCrypto.decode(clientHello.getString("signature")) }.getOrNull() ?: return
        if (!identity.verify(clientIdentityKey, clientPayload, clientSignature)) return

        val serverEphemeral: KeyPair = LanCrypto.generateEphemeralKeyPair()
        val serverNonce = LanCrypto.randomNonce()
        val serverIdentityBytes = identity.publicKeyEncoded()
        val serverPayload = LanCrypto.serverHelloPayload(
            serverDeviceId = ownDeviceId,
            serverIdentityPublicKey = serverIdentityBytes,
            serverEphemeralPublicKey = serverEphemeral.public.encoded,
            serverNonce = serverNonce,
            clientPayload = clientPayload,
        )
        plain.send(
            JSONObject()
                .put("type", "server_hello")
                .put("protocol", LanProtocol.VERSION)
                .put("deviceId", ownDeviceId)
                .put("identityKey", LanCrypto.encode(serverIdentityBytes))
                .put("ephemeralKey", LanCrypto.encode(serverEphemeral.public.encoded))
                .put("nonce", LanCrypto.encode(serverNonce))
                .put("signature", LanCrypto.encode(identity.sign(serverPayload))),
        )

        val (sessionKey, transcriptHash) = LanCrypto.deriveSessionKey(
            localEphemeral = serverEphemeral,
            remoteEphemeralPublicKey = clientEphemeralKey,
            clientPayload = clientPayload,
            serverPayload = serverPayload,
        )
        val secure = SecureJsonChannel(
            connection.inputStream,
            connection.outputStream,
            sessionKey,
            transcriptHash,
        )

        val trustedPeer = peers.findTrusted(clientDeviceId, clientFingerprint)
        val autoAccepted = expectedPeer == null && peers.autoAcceptPeer(clientDeviceId, clientFingerprint) != null
        val accepted = when {
            expectedPeer != null -> true
            autoAccepted -> true
            else -> {
                val requestId = UUID.randomUUID().toString()
                val approval = CompletableDeferred<Boolean>()
                approvals[requestId] = approval
                addIncomingRequest(
                    IncomingRequest(
                        requestId = requestId,
                        deviceId = clientDeviceId,
                        fingerprint = clientFingerprint,
                    ),
                )
                val result = withTimeoutOrNull(LanProtocol.APPROVAL_TIMEOUT_MS) { approval.await() } ?: false
                approvals.remove(requestId)
                removeIncomingRequest(requestId)
                result
            }
        }

        secure.send(
            JSONObject()
                .put("type", "session")
                .put("status", if (accepted) "accepted" else "rejected"),
        )
        if (!accepted) return

        val capabilities = trustedPeer?.capabilities ?: PeerCapabilities.FULL
        val sessionId = UUID.randomUUID().toString()
        val session = ActiveSession(
            sessionId = sessionId,
            deviceId = clientDeviceId,
            fingerprint = clientFingerprint,
            route = route,
            connectedAt = System.currentTimeMillis(),
        )
        sessionConnections[sessionId] = connection
        addActiveSession(session)
        peers.recordRecent(
            deviceId = clientDeviceId,
            fingerprint = clientFingerprint,
            route = if (route == SessionRoute.LAN_DIRECT) PeerRepository.ROUTE_LAN else PeerRepository.ROUTE_VPS,
        )
        if (connection is SocketByteDuplexConnection) {
            connection.setReadTimeout(0)
        }
        try {
            serveRpc(secure, capabilities)
        } finally {
            sessionConnections.remove(sessionId)
            removeActiveSession(sessionId)
        }
    }

    private suspend fun serveRpc(channel: SecureJsonChannel, capabilities: PeerCapabilities) {
        while (true) {
            val request = try {
                channel.receive()
            } catch (_: EOFException) {
                break
            } catch (_: SocketException) {
                break
            } catch (_: IOException) {
                break
            }
            val requestId = request.optLong("id", -1L)
            if (requestId < 0) break
            val response = try {
                val operation = request.getString("op")
                requireCapability(operation, capabilities)
                val result = handleRpc(operation, request.optJSONObject("args") ?: JSONObject())
                JSONObject().put("id", requestId).put("ok", true).put("result", result ?: JSONObject.NULL)
            } catch (error: FileAccessException) {
                JSONObject()
                    .put("id", requestId)
                    .put("ok", false)
                    .put("error", LanRpcCodec.errorName(error.error))
                    .put("detail", error.detail ?: error.cause?.message ?: "")
            } catch (_: Throwable) {
                JSONObject()
                    .put("id", requestId)
                    .put("ok", false)
                    .put("error", LanRpcCodec.errorName(FileError.UNKNOWN))
            }
            channel.send(response)
        }
    }

    private fun requireCapability(operation: String, capabilities: PeerCapabilities) {
        val allowed = when (operation) {
            "PING" -> true
            "LIST_VOLUMES", "LIST_DIRECTORY", "STAT", "THUMBNAIL", "PERMISSIONS" -> capabilities.browse
            "DOWNLOAD_BEGIN", "DOWNLOAD_CHUNK", "DOWNLOAD_END" -> capabilities.download
            "UPLOAD_BEGIN", "UPLOAD_CHUNK", "UPLOAD_END" -> capabilities.upload
            "MKDIR", "COPY", "MOVE", "RENAME", "SET_PERMISSIONS", "COMPRESS" -> capabilities.manage
            "DELETE" -> capabilities.delete
            "TRANSFER_CANCEL" -> capabilities.download || capabilities.upload
            else -> false
        }
        if (!allowed) {
            throw FileAccessException(
                FileError.PERMISSION_DENIED,
                detail = "Remote peer capability denied: $operation",
            )
        }
    }

    private suspend fun handleRpc(operation: String, args: JSONObject): Any? = when (operation) {
        "PING" -> true
        "LIST_VOLUMES" -> LanRpcCodec.volumes(volumes.listVolumes())
        "LIST_DIRECTORY" -> LanRpcCodec.fileEntries(backend.listDirectory(requireAllowed(args.getString("path"))))
        "STAT" -> LanRpcCodec.fileEntry(backend.stat(requireAllowed(args.getString("path"))))
        "MKDIR" -> {
            backend.createDirectory(requireAllowed(args.getString("path")))
            true
        }
        "COPY" -> {
            backend.copy(
                requireAllowed(args.getString("source")),
                requireAllowed(args.getString("destination")),
            )
            true
        }
        "MOVE" -> {
            backend.move(
                requireAllowed(args.getString("source")),
                requireAllowed(args.getString("destination")),
            )
            true
        }
        "RENAME" -> {
            backend.rename(
                requireAllowed(args.getString("source")),
                requireAllowed(args.getString("destination")),
            )
            true
        }
        "DELETE" -> {
            backend.delete(requireAllowed(args.getString("path")))
            true
        }
        "THUMBNAIL" -> {
            val bytes = thumbnails.generate(
                requireAllowed(args.getString("path")),
                args.optInt("maxSizePx", 192).coerceIn(32, 512),
            )
            bytes?.let(LanCrypto::encode) ?: JSONObject.NULL
        }
        "PERMISSIONS" -> {
            backend.permissions(requireAllowed(args.getString("path")))
                ?.let(LanRpcCodec::permissions)
                ?: JSONObject.NULL
        }
        "SET_PERMISSIONS" -> {
            backend.setPermissions(
                path = requireAllowed(args.getString("path")),
                mode = args.getInt("mode") and 0x1FF,
                applyToFiles = args.optBoolean("applyToFiles", false),
                applyToDirectories = args.optBoolean("applyToDirectories", false),
            )
            true
        }
        "COMPRESS" -> {
            val rawPaths = args.getJSONArray("paths")
            val resolvedPaths = mutableListOf<String>()
            for (index in 0 until rawPaths.length()) {
                resolvedPaths += requireAllowed(rawPaths.getString(index))
            }
            val sourcePaths = resolvedPaths.distinct()
            if (sourcePaths.isEmpty()) throw FileAccessException(FileError.INVALID_PATH)
            val destination = requireAllowed(args.getString("destination"))
            compressToZip(
                sourcePaths = sourcePaths,
                destination = destination,
                storeOnly = args.optBoolean("storeOnly", false),
            )
            true
        }
        "DOWNLOAD_BEGIN" -> {
            val path = requireAllowed(args.getString("path"))
            val entry = backend.stat(path)
            if (entry.isDirectory) throw FileAccessException(FileError.INVALID_PATH)
            val id = UUID.randomUUID().toString()
            val pfd = backend.openRead(path)
            downloads[id] = DownloadTransfer(ParcelFileDescriptor.AutoCloseInputStream(pfd), entry.size)
            JSONObject().put("transferId", id).put("size", entry.size)
        }
        "DOWNLOAD_CHUNK" -> {
            val id = args.getString("transferId")
            val transfer = downloads[id] ?: throw FileAccessException(FileError.NOT_FOUND)
            val maxBytes = args.optInt("maxBytes", 48 * 1024).coerceIn(1024, 64 * 1024)
            val buffer = ByteArray(maxBytes)
            val read = transfer.stream.read(buffer)
            val bytes = if (read <= 0) ByteArray(0) else buffer.copyOf(read)
            if (read > 0) transfer.sent += read
            JSONObject()
                .put("data", LanCrypto.encode(bytes))
                .put("eof", read < 0 || transfer.sent >= transfer.total)
        }
        "DOWNLOAD_END" -> {
            downloads.remove(args.getString("transferId"))?.stream?.close()
            true
        }
        "UPLOAD_BEGIN" -> {
            val path = requireAllowed(args.getString("path"))
            val expected = args.optLong("size", -1L)
            if (expected < 0L) throw FileAccessException(FileError.INVALID_PATH)
            val exists = runCatching { backend.stat(path) }.isSuccess
            if (exists) throw FileAccessException(FileError.CONFLICT)
            val id = UUID.randomUUID().toString()
            val parent = PathUtils.parent(path) ?: throw FileAccessException(FileError.INVALID_PATH)
            val temporaryPath = PathUtils.child(parent, ".p2p-part-$id")
            val pfd = backend.openWrite(temporaryPath, append = false)
            uploads[id] = UploadTransfer(
                stream = ParcelFileDescriptor.AutoCloseOutputStream(pfd),
                expected = expected,
                temporaryPath = temporaryPath,
                destinationPath = path,
            )
            JSONObject().put("transferId", id)
        }
        "UPLOAD_CHUNK" -> {
            val transfer = uploads[args.getString("transferId")] ?: throw FileAccessException(FileError.NOT_FOUND)
            val bytes = LanCrypto.decode(args.getString("data"))
            if (bytes.size > 64 * 1024 || transfer.received + bytes.size > transfer.expected) {
                throw FileAccessException(FileError.INVALID_PATH)
            }
            transfer.stream.write(bytes)
            transfer.received += bytes.size
            true
        }
        "UPLOAD_END" -> {
            val transfer = uploads.remove(args.getString("transferId")) ?: throw FileAccessException(FileError.NOT_FOUND)
            transfer.stream.flush()
            transfer.stream.close()
            if (transfer.received != transfer.expected) {
                runCatching { backend.delete(transfer.temporaryPath) }
                throw FileAccessException(FileError.UNKNOWN)
            }
            backend.rename(transfer.temporaryPath, transfer.destinationPath)
            true
        }
        "TRANSFER_CANCEL" -> {
            val id = args.getString("transferId")
            downloads.remove(id)?.stream?.close()
            uploads.remove(id)?.let { transfer ->
                transfer.stream.close()
                runCatching { backend.delete(transfer.temporaryPath) }
            }
            true
        }
        else -> throw FileAccessException(FileError.INVALID_PATH)
    }

    /**
     * 壓縮工作在被控端本機執行，因此不會把來源檔案先傳到主控端。
     * 「僅儲存」使用Deflater level 0，維持單次串流讀取，避免ZIP STORED模式為了CRC而先完整讀取一次來源檔。
     */
    private suspend fun compressToZip(
        sourcePaths: List<String>,
        destination: String,
        storeOnly: Boolean,
    ) {
        if (runCatching { backend.stat(destination) }.isSuccess) {
            throw FileAccessException(FileError.CONFLICT)
        }

        val destinationParent = PathUtils.parent(destination) ?: throw FileAccessException(FileError.INVALID_PATH)
        val parentEntry = backend.stat(destinationParent)
        if (!parentEntry.isDirectory) throw FileAccessException(FileError.INVALID_PATH)

        val normalizedDestination = PathUtils.normalize(destination)
        sourcePaths.forEach { source ->
            val entry = backend.stat(source)
            val normalizedSource = PathUtils.normalize(source)
            if (normalizedDestination == normalizedSource ||
                (entry.isDirectory && normalizedDestination.startsWith("$normalizedSource/"))
            ) {
                throw FileAccessException(
                    FileError.INVALID_PATH,
                    detail = "Archive destination cannot be inside a selected source directory",
                )
            }
        }

        val temporaryPath = PathUtils.child(destinationParent, ".p2p-zip-${UUID.randomUUID()}")
        try {
            val pfd = backend.openWrite(temporaryPath, append = false)
            ParcelFileDescriptor.AutoCloseOutputStream(pfd).use { rawOutput ->
                ZipOutputStream(rawOutput.buffered()).use { zip ->
                    zip.setLevel(if (storeOnly) Deflater.NO_COMPRESSION else Deflater.DEFAULT_COMPRESSION)
                    val usedNames = linkedSetOf<String>()
                    sourcePaths.forEach { source ->
                        val entry = backend.stat(source)
                        val baseName = source.substringAfterLast('/').ifBlank { "item" }
                        addZipEntry(
                            zip = zip,
                            sourcePath = source,
                            entryName = uniqueZipName(baseName, usedNames),
                            knownEntry = entry,
                        )
                    }
                }
            }
            backend.rename(temporaryPath, destination)
        } catch (error: FileAccessException) {
            runCatching { backend.delete(temporaryPath) }
            throw error
        } catch (error: Throwable) {
            runCatching { backend.delete(temporaryPath) }
            throw FileAccessException(
                FileError.UNKNOWN,
                cause = error,
                detail = error.message ?: error.javaClass.simpleName,
            )
        }
    }

    private suspend fun addZipEntry(
        zip: ZipOutputStream,
        sourcePath: String,
        entryName: String,
        knownEntry: com.p2premotefiles.app.domain.model.FileEntry? = null,
    ) {
        val source = requireAllowed(sourcePath)
        val entry = knownEntry ?: backend.stat(source)
        val safeName = entryName.replace('\\', '/').trimStart('/')
        if (safeName.isBlank() || safeName == "." || safeName == "..") {
            throw FileAccessException(FileError.INVALID_PATH)
        }

        if (entry.isDirectory) {
            val directoryName = safeName.trimEnd('/') + "/"
            val directoryEntry = ZipEntry(directoryName)
            if (entry.modifiedAt > 0L) directoryEntry.time = entry.modifiedAt
            zip.putNextEntry(directoryEntry)
            zip.closeEntry()

            backend.listDirectory(source).forEach { child ->
                val childPath = requireAllowed(child.path)
                val childName = child.name.takeIf { it.isNotBlank() && it != "." && it != ".." }
                    ?: return@forEach
                addZipEntry(
                    zip = zip,
                    sourcePath = childPath,
                    entryName = directoryName + childName,
                    knownEntry = child,
                )
            }
        } else {
            val fileEntry = ZipEntry(safeName)
            if (entry.modifiedAt > 0L) fileEntry.time = entry.modifiedAt
            zip.putNextEntry(fileEntry)
            val pfd = backend.openRead(source)
            ParcelFileDescriptor.AutoCloseInputStream(pfd).use { input ->
                input.copyTo(zip, bufferSize = 128 * 1024)
            }
            zip.closeEntry()
        }
    }

    private fun uniqueZipName(name: String, usedNames: MutableSet<String>): String {
        if (usedNames.add(name)) return name
        val dot = name.lastIndexOf('.')
        val stem = if (dot > 0) name.substring(0, dot) else name
        val extension = if (dot > 0) name.substring(dot) else ""
        var index = 2
        while (true) {
            val candidate = "$stem ($index)$extension"
            if (usedNames.add(candidate)) return candidate
            index++
        }
    }

    private suspend fun requireAllowed(path: String): String {
        if (!path.startsWith('/')) throw FileAccessException(FileError.INVALID_PATH)
        val normalized = PathUtils.normalize(path)
        val roots = volumes.listVolumes().map { PathUtils.normalize(it.rootPath) }
        val allowed = roots.any { root -> normalized == root || normalized.startsWith("$root/") }
        if (!allowed) throw FileAccessException(FileError.INVALID_PATH)

        val canonical = runCatching { File(normalized).canonicalPath }.getOrNull()
        if (canonical != null) {
            val canonicalAllowed = roots.any { root ->
                val canonicalRoot = runCatching { File(root).canonicalPath }.getOrDefault(root)
                canonical == canonicalRoot || canonical.startsWith("$canonicalRoot/")
            }
            if (!canonicalAllowed) throw FileAccessException(FileError.INVALID_PATH)
        }
        return normalized
    }

    private fun addActiveSession(session: ActiveSession) {
        synchronized(incomingLock) {
            _activeSessions.value = _activeSessions.value.filterNot { it.sessionId == session.sessionId } + session
        }
    }

    private fun removeActiveSession(sessionId: String) {
        synchronized(incomingLock) {
            _activeSessions.value = _activeSessions.value.filterNot { it.sessionId == sessionId }
        }
    }

    private fun addIncomingRequest(request: IncomingRequest) {
        synchronized(incomingLock) {
            _incomingRequests.value = _incomingRequests.value + request
        }
    }

    private fun removeIncomingRequest(requestId: String) {
        synchronized(incomingLock) {
            _incomingRequests.value = _incomingRequests.value.filterNot { it.requestId == requestId }
        }
    }
}
