package io.emx.runtime

import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class PayloadServer(private val payload: ByteArray) : Closeable {
    private val token = ByteArray(32).also { SecureRandom().nextBytes(it) }
        .joinToString("") { "%02x".format(it) }
    private val server = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
    private val closed = AtomicBoolean(false)
    private val client = AtomicReference<Socket?>()
    val url = "http://127.0.0.1:${server.localPort}/$token/driver.zip"

    init {
        Thread({
            while (!closed.get()) {
                try {
                    server.accept().use { socket ->
                        client.set(socket)
                        if (closed.get()) return@use
                        socket.soTimeout = 3000
                        serve(socket)
                    }
                } catch (_: java.io.IOException) {
                    // A disconnected client must not prevent Termux from retrying.
                } finally {
                    client.set(null)
                }
            }
        }, "emX-payload").apply { isDaemon = true; start() }
    }

    private fun serve(socket: Socket) {
        val input = socket.getInputStream()
        val header = StringBuilder()
        while (header.length < 8192 && !header.endsWith("\r\n\r\n")) {
            val byte = input.read()
            if (byte < 0) return
            header.append(byte.toChar())
        }
        val lines = header.toString().split("\r\n")
        val allowed = header.endsWith("\r\n\r\n") &&
            lines.first() == "GET /$token/driver.zip HTTP/1.1" &&
            lines.none { it.startsWith("Origin:", ignoreCase = true) }
        val body = if (allowed) payload else ByteArray(0)
        val status = if (allowed) "200 OK" else "404 Not Found"
        socket.getOutputStream().apply {
            write(("HTTP/1.1 $status\r\nContent-Length: ${body.size}\r\n" +
                "Content-Type: application/zip\r\nCache-Control: no-store\r\n" +
                "Connection: close\r\n\r\n").toByteArray(Charsets.US_ASCII))
            write(body)
            flush()
        }
    }

    override fun close() {
        closed.set(true)
        server.close()
        client.getAndSet(null)?.close()
    }
}
