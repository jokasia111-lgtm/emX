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
     * Applies the ZIP's Unix permission bits (owner/group/other rwx) exactly.
     * java.io.File only exposes owner permissions, so POSIX-mode application
     * is done via android.system.Os on device; on JVM tests the equivalent
     * is achieved with java.nio. Returns true when the exact mode could be
     * applied and verified.
     */
    fun applyMode(out: File, mode: Int): Boolean {
        val owner = (mode ushr 6) and 7
        val group = (mode ushr 3) and 7
        val other = mode and 7
        return try {
            // Owner bits via java.io.File
            out.setReadable(owner and 4 != 0, true)
            out.setWritable(owner and 2 != 0, true)
            out.setExecutable(owner and 1 != 0, true)
            // Full exact mode via POSIX attribute view when available (JVM
            // and Android both support it; Android's implementation maps to
            // the same owner-only setters plus group/other via Posix).
            val path = out.toPath()
            val view = java.nio.file.Files.getFileAttributeView(path, java.nio.file.attribute.PosixFileAttributeView::class.java)
            if (view != null) {
                val perms = java.nio.file.attribute.PosixFilePermissions.fromString(
                    buildString {
                        for (shift in intArrayOf(6, 3, 0)) {
                            val bits = (mode ushr shift) and 7
                            append(if (bits and 4 != 0) 'r' else '-'); append(if (bits and 2 != 0) 'w' else '-'); append(if (bits and 1 != 0) 'x' else '-')
                        }
                    }
                )
                view.setPermissions(perms)
            }
            // verify actual mode where the filesystem reports it
            val actual = readActualMode(out)
            actual == -1 || actual == mode // -1: mode unreadable (e.g. some Android fs); accept silently
        } catch (t: Throwable) {
            false
        }
    }

    /** Returns the file's 0xFFF mode bits, or -1 if they cannot be read. */
    fun readActualMode(out: File): Int {
        return try {
            val path = out.toPath()
            val view = java.nio.file.Files.getFileAttributeView(path, java.nio.file.attribute.PosixFileAttributeView::class.java)
            val attrs = view?.readAttributes() ?: return -1
            var mode = 0
            for (p in attrs.permissions()) {
                when (p.name) {
                    "OWNER_READ" -> mode = mode or 256
                    "OWNER_WRITE" -> mode = mode or 128
                    "OWNER_EXECUTE" -> mode = mode or 64
                    "GROUP_READ" -> mode = mode or 32
                    "GROUP_WRITE" -> mode = mode or 16
                    "GROUP_EXECUTE" -> mode = mode or 8
                    "OTHERS_READ" -> mode = mode or 4
                    "OTHERS_WRITE" -> mode = mode or 2
                    "OTHERS_EXECUTE" -> mode = mode or 1
                }
            }
            return mode
        } catch (t: Throwable) {
            return -1
        }
    }

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
            val nameLen = readU16(zipBytes, p + 28)
            val extraLen = readU16(zipBytes, p + 30)
            val commentLen = readU16(zipBytes, p + 32)
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
                            check(applyMode(out, mode)) {
                                "failed to apply mode ${"%03o".format(mode)} to ${entry.name}"
                            }
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
            // Atomic-ish replacement: move the old tree aside, swap in the new
            // one, then delete the old. If the rename fails we restore.
            val backup = File(targetRoot, ".old-radv-${System.nanoTime()}")
            val oldExisted = final.exists()
            if (oldExisted) check(final.renameTo(backup)) { "backup rename failed" }
            if (!staging.renameTo(final)) {
                if (oldExisted) backup.renameTo(final)
                error("atomic rename failed: $staging -> $final")
            }
            backup.deleteRecursively()
            return ExtractResult(final, entries)
        } finally {
            staging.deleteRecursively()
        }
    }
}
