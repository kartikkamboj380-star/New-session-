package com.multispace.manager.core

/**
 * Pool of WebView host process slots. Each slot is a separate Android process
 * (`:web_slot_N` in the manifest) because WebView data directory isolation
 * works per-process: one process = one WebView data directory = one isolated
 * web session at a time.
 *
 * When all slots are busy the caller must show an explicit "isolation slots
 * busy" message — sessions are never silently downgraded to a shared,
 * non-isolated WebView.
 */
class WebSlotRegistry(private val slotCount: Int = DEFAULT_SLOT_COUNT) {

    private val slots: Array<String?> = arrayOfNulls(slotCount)

    /** @return the slot index for this session, or null when all slots are busy. */
    @Synchronized
    fun acquire(sessionId: String): Int? {
        val existing = slotOf(sessionId)
        if (existing != null) return existing
        val free = slots.indexOfFirst { it == null }
        if (free < 0) return null
        slots[free] = sessionId
        return free
    }

    @Synchronized
    fun release(sessionId: String) {
        val index = slots.indexOfFirst { it == sessionId }
        if (index >= 0) slots[index] = null
    }

    @Synchronized
    fun slotOf(sessionId: String): Int? =
        slots.indexOfFirst { it == sessionId }.takeIf { it >= 0 }

    @Synchronized
    fun activeCount(): Int = slots.count { it != null }

    @Synchronized
    fun capacity(): Int = slotCount

    companion object {
        const val DEFAULT_SLOT_COUNT = 4
    }
}
