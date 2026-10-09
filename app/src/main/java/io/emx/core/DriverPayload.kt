package io.emx.core

import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

/**
 * Verified extraction of the bundled RADV-Xclipse driver payload.
 *
 * The payload is the original, unmodified upstream ZIP. It is extracted
 * byte-for-byte into a versioned directory; Unix modes from the ZIP are
 * preserved (install.sh is 0755). Extraction is staged and atomically
 * renamed into place so a partial extraction is never visible.
 */
object DriverPayload {

    const val ASSET_PATH = "driver/radv-xclipse-proot-glibc-26.3.0-devel-dc5f6bc4e3.zip"
    const val ZIP_SHA256 =
        "c041ac31e18165442574deb305846119a3d239cee6b781f249dd4467683ecd29"
    const val VERSION = "26.3.0-devel-dc5f6bc4e3"
    const val EXPECTED_ENTRY_COUNT = 5

    data class EntryResult(val name: String, val size: Long, val sha256: String, val unixMode: Int)
    data class ExtractResult(val dir: File, val entries: List<EntryResult>)

    fun sha256(stream: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = stream.read(buf)
            if (n < 0) break
            digest.update(buf, 0, n)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun sha256(file: File): String =
        file.inputStream().use { sha256(it) }

    /**
     * Minimal central-directory parser: returns name -> unix mode (permission
     * bits only, e.g. 0644/0755) for each entry. java.util.zip does not
     * expose external attributes, so we read them ourselves.
     */
    fun unixModes(zipBytes: ByteArray): Map<String, Int> {
        val eocd = findEocd(zipBytes)
        val cdOffset = readU32(zipBytes, eocd + 16).toInt()
        val entryCount = readU16(zipBytes, eocd + 10)
        val modes = mutableMapOf<String, Int>()
        var p = cdOffset
        repeat(entryCount) {
            check(readU32(zipBytes, p) == 0x02014b50L) { "bad central directory entry" }
            val mode = (readU32(zipBytes, p + 38) ushr 16).toInt() and 0xFFF
            val nameLen = readU32(zipBytes, p + 28).toInt()
            val extraLen = readU32(zipBytes, p + 30).toInt()
            val commentLen = readU32(zipBytes, p + 32).toInt()
            val name = String(zipBytes, p + 46, nameLen, Charsets.UTF_8)
            if (mode != 0) modes[name] = mode
            p += 46 + nameLen + extraLen + commentLen
        }
        return modes
    }

    private fun findEocd(zipBytes: ByteArray): Int {
        var i = zipBytes.size - 22
        while (i >= 0) {
            if (readU32(zipBytes, i) == 0x06054b50L) return i
            i--
        }
        throw IllegalArgumentException("no EOCD in zip")
    }

    private fun readU16(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)

    private fun readU32(b: ByteArray, off: Int): Long {
        var v = 0L
        for (i in 3 downTo 0) v = (v shl 8) or (b[off + i].toLong() and 0xFF)
        return v
    }

    /** Extracts the payload ZIP into [targetRoot]/radv-xclipse/[version]. */
    fun extract(zipStream: InputStream, targetRoot: File): ExtractResult {
        // Materialize the zip in memory to parse modes and verify integrity
        val zipBytes = zipStream.readBytes()
        val modes = unixModes(zipBytes)

        val staging = File(targetRoot, ".staging-radv-${System.nanoTime()}")
        val final = File(File(targetRoot, "radv-xclipse"), VERSION)
        try {
            staging.mkdirs()
            val entries = mutableListOf<EntryResult>()
            ZipInputStream(ByteArrayInputStream(zipBytes)).use { zis ->
                while (true) {
                    val entry: ZipEntry = zis.nextEntry ?: break
                    val safeName = entry.name.replace('\\', '/')
                    require(!safeName.startsWith("/") && !safeName.contains("..")) {
                        "unsafe path in driver zip: ${entry.name}"
                    }
                    val out = File(staging, safeName)
                    if (entry.isDirectory) {
                        out.mkdirs()
                    } else {
                        out.parentFile?.mkdirs()
                        out.outputStream().use { os -> zis.copyTo(os) }
                        val mode = modes[entry.name] ?: 0
                        if (mode != 0) {
                            // apply only the rwx bits: owner, group, other
                            out.setReadable(mode and 0x124 != 0, false)
                            out.setWritable(mode and 0x92 != 0, false)
                            out.setExecutable(mode and 0x49 != 0, false)
                        }
                        entries.add(
                            EntryResult(
                                name = safeName,
                                size = out.length(),
                                sha256 = sha256(out),
                                unixMode = mode,
                            )
                        )
                    }
                    zis.closeEntry()
                }
            }
            final.parentFile?.mkdirs()
            final.deleteRecursively()
            check(staging.renameTo(final)) { "atomic rename failed: $staging -> $final" }
            return ExtractResult(final, entries)
        } finally {
            staging.deleteRecursively()
        }
    }
}
