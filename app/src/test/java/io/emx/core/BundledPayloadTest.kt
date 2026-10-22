package io.emx.core

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Verifies the actual bundled driver payload: hash, entry count, modes and
 * the expected layout after extraction. This is the host-side "packaged and
 * extractable" verification; on-device behavior is a separate level.
 */
class BundledPayloadTest {

    private fun bundledZip(): File {
        // repo layout: app/src/test -> app/src/main/assets/driver/...
        val candidates = listOf(
            File("src/main/assets/driver/${DriverPayload.ASSET_PATH.substringAfterLast('/')}"),
            File("app/src/main/assets/driver/${DriverPayload.ASSET_PATH.substringAfterLast('/')}"),
        )
        return candidates.first { it.exists() }
    }

    @Test
    fun bundledZipHashMatches() {
        bundledZip().inputStream().use {
            assertEquals(DriverPayload.ZIP_SHA256, DriverPayload.sha256(it))
        }
    }

    @Test
    fun extractionProducesExpectedLayout() {
        val tmp = createTempDir(prefix = "emxreal")
        org.junit.Assume.assumeTrue("Exact Unix modes require a POSIX filesystem",
            java.nio.file.Files.getFileStore(tmp.toPath()).supportsFileAttributeView("posix"))
        val result = bundledZip().inputStream().use { DriverPayload.extract(it, File(tmp, "d")) }
        assertEquals(5, result.entries.size)

        val byName = result.entries.associateBy { it.name }
        val driver = byName.getValue("libvulkan_radeon.so")
        val icd = byName.getValue("radeon_icd.aarch64.json")
        val install = byName.getValue("install.sh")
        val notice = byName.getValue("NOTICE.txt")
        val readme = byName.getValue("README.txt")

        assertEquals(19736848L, driver.size)
        assertEquals(185L, icd.size)
        assertEquals(398L, install.size)
        assertEquals(18902L, notice.size)
        assertEquals(1991L, readme.size)

        // modes from the original zip: install.sh is executable, others are 0644
        assertEquals(493, install.unixMode) // 0o755
        assertTrue(File(result.dir, "install.sh").canExecute())
        assertEquals(420, driver.unixMode) // 0o644
        assertEquals(420, icd.unixMode)
        assertEquals(420, notice.unixMode)
        assertEquals(420, readme.unixMode)

        // extracted layout keeps the versioned directory structure
        assertTrue(File(result.dir, "libvulkan_radeon.so").exists())
        assertTrue(result.dir.absolutePath.endsWith("radv-xclipse/${DriverPayload.VERSION}"))
        tmp.deleteRecursively()
    }
}
