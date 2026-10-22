package io.emx.runtime

import java.net.HttpURLConnection
import java.net.URI
import java.net.Socket
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PayloadServerTest {
    private fun connect(url: String) = (URI(url).toURL().openConnection() as HttpURLConnection).apply {
        connectTimeout = 2000
        readTimeout = 2000
    }

    @Test fun servesExactPayloadOnlyWithToken() {
        val payload = ByteArray(65536) { it.toByte() }
        PayloadServer(payload).use { server ->
            val uri = URI(server.url)
            assertEquals("127.0.0.1", uri.host)
            assertTrue(uri.path.matches(Regex("/[a-f0-9]{64}/driver.zip")))
            val connection = connect(server.url)
            try {
                assertEquals(200, connection.responseCode)
                assertEquals("no-store", connection.getHeaderField("Cache-Control"))
                assertContentEquals(payload, connection.inputStream.use { it.readBytes() })
            } finally { connection.disconnect() }
            val denied = connect("http://127.0.0.1:${uri.port}/driver.zip")
            try { assertEquals(404, denied.responseCode) } finally { denied.disconnect() }
        }
    }

    @Test fun rejectsBrowserOriginAndOtherMethods() {
        PayloadServer(byteArrayOf(1)).use { server ->
            val uri = URI(server.url)
            for (request in listOf("GET ${uri.path} HTTP/1.1\r\nOrigin: https://example.test", "POST ${uri.path} HTTP/1.1")) {
                Socket(uri.host, uri.port).use { socket ->
                    socket.soTimeout = 2000
                    socket.getOutputStream().write("$request\r\nHost: localhost\r\n\r\n".toByteArray())
                    assertTrue(socket.getInputStream().bufferedReader().readLine().contains("404"))
                }
            }
        }
    }

    @Test fun closeRevokesTheHandoff() {
        val server = PayloadServer(byteArrayOf(1))
        val url = server.url
        server.close()
        val connection = connect(url)
        try { assertFailsWith<java.io.IOException> { connection.inputStream } }
        finally { connection.disconnect() }
    }

    @Test fun eachServerHasADifferentToken() {
        PayloadServer(byteArrayOf()).use { first ->
            PayloadServer(byteArrayOf()).use { second ->
                assertTrue(URI(first.url).path != URI(second.url).path)
            }
        }
    }
}
