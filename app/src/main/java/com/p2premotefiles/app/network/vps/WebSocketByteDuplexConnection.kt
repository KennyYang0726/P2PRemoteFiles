package com.p2premotefiles.app.network.vps

import com.p2premotefiles.app.BuildConfig
import com.p2premotefiles.app.network.transport.ByteDuplexConnection
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 將VPS的WebSocket二進位訊息還原成雙向byte stream，讓既有加密RPC協定直接重用。
 * Relay WebSocket只轉送不解密；實際身分驗證與端對端加密仍在LanCrypto層。
 */
class WebSocketByteDuplexConnection private constructor(
    private val ready: CompletableDeferred<Unit>,
) : ByteDuplexConnection {
    private val closed = AtomicBoolean(false)
    private val incoming = PipedInputStream(1024 * 1024)
    private val incomingWriter = PipedOutputStream(incoming)

    @Volatile
    private var socket: WebSocket? = null

    override val inputStream: InputStream = incoming

    override val outputStream: OutputStream = object : OutputStream() {
        override fun write(value: Int) {
            write(byteArrayOf(value.toByte()), 0, 1)
        }

        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            if (closed.get()) throw IOException("Relay connection closed")
            if (length == 0) return
            val current = socket ?: throw IOException("Relay not connected")
            if (!current.send(bytes.copyOfRange(offset, offset + length).toByteString())) {
                throw IOException("Relay outgoing buffer rejected data")
            }
        }
    }

    private val listener = object : WebSocketListener() {
        override fun onMessage(webSocket: WebSocket, text: String) {
            when (text) {
                "READY" -> ready.complete(Unit)
                "WAIT" -> Unit
                else -> fail(IOException("Unexpected relay control message"))
            }
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            try {
                incomingWriter.write(bytes.toByteArray())
                incomingWriter.flush()
            } catch (error: Throwable) {
                fail(error)
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            fail(t)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            fail(IOException("Relay closed: $code"))
        }
    }

    private fun fail(error: Throwable) {
        ready.completeExceptionally(error)
        close()
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { socket?.close(1000, null) }
        runCatching { incomingWriter.close() }
        runCatching { incoming.close() }
    }

    companion object {
        suspend fun connect(
            client: OkHttpClient,
            sessionId: String,
            role: String,
            token: String,
        ): WebSocketByteDuplexConnection = withContext(Dispatchers.IO) {
            require(role == "controller" || role == "target")
            val connection = WebSocketByteDuplexConnection(CompletableDeferred())
            val query = listOf(
                "session" to sessionId,
                "role" to role,
                "token" to token,
            ).joinToString("&") { (key, value) ->
                "$key=${URLEncoder.encode(value, "UTF-8")}" 
            }
            val request = Request.Builder()
                .url("${BuildConfig.VPS_RELAY_URL}?$query")
                .build()
            connection.socket = client.newWebSocket(request, connection.listener)
            try {
                withTimeout(90_000L) { connection.ready.await() }
                connection
            } catch (error: Throwable) {
                connection.close()
                throw error
            }
        }
    }
}
