package io.emx.runtime

object VulkanProbe {
    fun hasXclipseRadv(output: String): Boolean =
        output.split(Regex("(?m)^\\s*GPU\\s*\\d+\\s*:?\\s*$")).drop(1).any { gpu ->
            val fields = gpu.lineSequence().mapNotNull { line ->
                val split = line.trim().split('=', limit = 2)
                if (split.size == 2) split[0].trim() to split[1].trim() else null
            }.toMap()
            fields["vendorID"]?.lowercase() in setOf("0x1002", "4098") &&
                fields["driverName"]?.equals("radv", ignoreCase = true) == true &&
                fields["deviceName"]?.let {
                    it.contains("VANGOGHLITE", ignoreCase = true) ||
                        it.contains("XCLIPSE", ignoreCase = true)
                } == true
        }
}
