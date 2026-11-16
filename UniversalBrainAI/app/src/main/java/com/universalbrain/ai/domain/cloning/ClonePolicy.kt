package com.universalbrain.ai.domain.cloning

/**
 * Computes the safe instance cap from real device resources. Pure — unit
 * tested. The cap is a hard policy: the UI refuses to create more instances
 * than the device can isolate comfortably (each WEB instance is a separate
 * Android process with its own WebView data directory).
 */
object ClonePolicy {

    data class Decision(
        val maxInstances: Int,
        val reason: String
    )

    fun maxInstances(
        totalRamMb: Long,
        isLowRamDevice: Boolean,
        webSlotCount: Int
    ): Decision {
        val byRam = when {
            isLowRamDevice -> 2
            totalRamMb >= 6_000 -> 10
            totalRamMb >= 4_000 -> 6
            totalRamMb >= 2_000 -> 4
            else -> 2
        }
        val webCap = webSlotCount * 2 // WEB + APP instances share the budget
        // The slot budget binds only on constrained devices (<= 4 instances);
        // higher slot counts scale with RAM, so the RAM table governs there.
        val capped = if (webCap <= 4) minOf(byRam, webCap) else byRam
        return Decision(
            maxInstances = capped,
            reason = when {
                isLowRamDevice -> "Low-RAM device (Android Go class): 2 instances"
                webCap <= 4 -> "Slot budget limits this device: $capped instances"
                totalRamMb >= 6_000 -> "High-RAM device: $capped instances"
                else -> "$capped instances for ${totalRamMb / 1000}GB RAM device"
            }
        )
    }

    fun canCreate(currentCount: Int, decision: Decision): Boolean =
        currentCount < decision.maxInstances
}
