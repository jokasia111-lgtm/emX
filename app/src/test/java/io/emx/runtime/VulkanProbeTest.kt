package io.emx.runtime

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VulkanProbeTest {
    private fun gpu(name: String = "AMD Radeon Graphics (RADV VANGOGHLITE)", driver: String = "radv", vendor: String = "0x1002") = """
        GPU0:
            vendorID = $vendor
            deviceName = $name
            driverName = $driver
    """.trimIndent()

    @Test fun acceptsUpstreamXclipseSummary() = assertTrue(VulkanProbe.hasXclipseRadv(gpu()))
    @Test fun acceptsSpacedGpuHeader() =
        assertTrue(VulkanProbe.hasXclipseRadv(gpu().replace("GPU0:", "GPU 0:")))
    @Test fun acceptsExplicitXclipseName() = assertTrue(VulkanProbe.hasXclipseRadv(gpu("Samsung Xclipse 530")))
    @Test fun rejectsSoftwareRenderer() = assertFalse(VulkanProbe.hasXclipseRadv(gpu("llvmpipe", "llvmpipe", "0x10005")))
    @Test fun rejectsOtherAmdGpu() = assertFalse(VulkanProbe.hasXclipseRadv(gpu("AMD Radeon RX 6800")))
    @Test fun rejectsMentionInError() = assertFalse(VulkanProbe.hasXclipseRadv("Failed loading radv VANGOGHLITE 0x1002"))
    @Test fun rejectsMissingVendor() = assertFalse(VulkanProbe.hasXclipseRadv(gpu(vendor = "")))
    @Test fun doesNotCombineDifferentDevices() {
        val first = gpu(driver = "proprietary")
        val second = gpu(name = "Other GPU").replace("GPU0:", "GPU1:")
        assertFalse(VulkanProbe.hasXclipseRadv(first + "\n" + second))
    }
    @Test fun findsDeviceAfterSoftwareRenderer() {
        val first = gpu("llvmpipe", "llvmpipe", "0x10005")
        assertTrue(VulkanProbe.hasXclipseRadv(first + "\n" + gpu().replace("GPU0:", "GPU1:")))
    }
}
