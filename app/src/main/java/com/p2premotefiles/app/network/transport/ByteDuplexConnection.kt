package com.p2premotefiles.app.network.transport

import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket

interface ByteDuplexConnection : Closeable {
    val inputStream: InputStream
    val outputStream: OutputStream
}

class SocketByteDuplexConnection(
    private val socket: Socket,
) : ByteDuplexConnection {
    override val inputStream: InputStream
        get() = socket.getInputStream()

    override val outputStream: OutputStream
        get() = socket.getOutputStream()

    fun setReadTimeout(timeoutMs: Int) {
        socket.soTimeout = timeoutMs
    }

    override fun close() {
        socket.close()
    }
}
