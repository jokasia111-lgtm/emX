package io.emx.core

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.CRC32
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val MODE_755 = 493 // 0o755

class DriverPayloadTest {

    /** Handcrafted minimal ZIP with one stored entry, external attrs = mode<<16. */
    private fun makeZip(mode: Int, content: ByteArray = "payload".toByteArray()): ByteArray {
        val crc = CRC32().apply { update(content) }
        val name = "install.sh".toByteArray()
        val bos = ByteArrayOutputStream()

        fun int(v: Int) = byteArrayOf(
            (v and 0xFF).toByte(), ((v ushr 8) and 0xFF).toByte(),
            ((v ushr 16) and 0xFF).toByte(), ((v ushr 24) and 0xFF).toByte()
        )
        fun short(v: Int) = byteArrayOf((v and 0xFF).toByte(), ((v ushr 8) and 0xFF).toByte())

        // local file header (30 bytes)
        bos.write(int(0x04034b50))
        bos.write(short(20)); bos.write(short(0)); bos.write(short(0)) // version, flags, method
        bos.write(int(0)); // modtime + moddate
        bos.write(int(crc.value.toInt()))
        bos.write(int(content.size)); bos.write(int(content.size))
        bos.write(short(name.size)); bos.write(short(0))
        bos.write(name); bos.write(content)

        val cdStart = bos.size()
        // central directory (46 bytes)
        bos.write(int(0x02014b50))
        bos.write(short(20)); bos.write(short(20)); bos.write(short(0)); bos.write(short(0))
        bos.write(int(0)) // modtime + moddate
        bos.write(int(crc.value.toInt()))
        bos.write(int(content.size)); bos.write(int(content.size))
        bos.write(short(name.size)); bos.write(short(0)); bos.write(short(0))
        bos.write(short(0)); bos.write(short(0))
        bos.write(int(mode shl 16)) // external attributes
        bos.write(int(0))
        bos.write(name)
        val cdSize = bos.size() - cdStart
        // EOCD (22 bytes)
        bos.write(int(0x06054b50))
        bos.write(short(0)); bos.write(short(0)); bos.write(short(1)); bos.write(short(1))
        bos.write(int(cdSize)); bos.write(int(cdStart)); bos.write(short(0))
        return bos.toByteArray()
    }

    @Test
    fun unixModesParsesExternalAttributes() {
        val modes = DriverPayload.unixModes(makeZip(MODE_755))
        assertEquals(mapOf("install.sh" to MODE_755), modes)
    }

    @Test
    fun extractPreservesLayoutAndModes() {
        val tmp = createTempDir(prefix = "emxtest")
        org.junit.Assume.assumeTrue("Exact Unix modes require a POSIX filesystem",
            java.nio.file.Files.getFileStore(tmp.toPath()).supportsFileAttributeView("posix"))
        val extracted = File(tmp, "extracted")
        val zipBytes = makeZip(MODE_755)
        val result = DriverPayload.extract(zipBytes.inputStream(), extracted)
        val out = File(result.dir, "install.sh")
        assertTrue(out.exists())
        assertEquals("payload", out.readText())
        assertEquals(MODE_755, result.entries.single().unixMode)
        assertTrue(out.canExecute())
        tmp.deleteRecursively()
    }
}
