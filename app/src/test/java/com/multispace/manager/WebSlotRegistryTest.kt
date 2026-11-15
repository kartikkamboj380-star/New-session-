package com.multispace.manager

import com.multispace.manager.core.WebSlotRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Isolation slot pool: each concurrent WEB session needs its own process slot;
 * exhaustion must be reported, never silently downgraded.
 */
class WebSlotRegistryTest {

    @Test
    fun `acquire assigns distinct slots`() {
        val registry = WebSlotRegistry(slotCount = 4)
        assertEquals(0, registry.acquire("a"))
        assertEquals(1, registry.acquire("b"))
        assertEquals(2, registry.acquire("c"))
        assertEquals(3, registry.acquire("d"))
        assertEquals(4, registry.activeCount())
    }

    @Test
    fun `exhaustion returns null instead of a shared slot`() {
        val registry = WebSlotRegistry(slotCount = 2)
        registry.acquire("a")
        registry.acquire("b")
        assertNull(registry.acquire("c"))
        assertEquals(2, registry.activeCount())
    }

    @Test
    fun `reacquiring the same session reuses its slot`() {
        val registry = WebSlotRegistry()
        val first = registry.acquire("a")
        assertEquals(first, registry.acquire("a"))
        assertEquals(1, registry.activeCount())
    }

    @Test
    fun `release frees the slot for the next session`() {
        val registry = WebSlotRegistry(slotCount = 1)
        assertEquals(0, registry.acquire("a"))
        assertNull(registry.acquire("b"))
        registry.release("a")
        assertEquals(0, registry.acquire("b"))
        assertEquals(1, registry.activeCount())
    }

    @Test
    fun `releasing unknown session is a no-op`() {
        val registry = WebSlotRegistry()
        registry.release("ghost")
        assertEquals(0, registry.activeCount())
    }

    @Test
    fun `slot lookup works for acquired sessions`() {
        val registry = WebSlotRegistry()
        val slot = registry.acquire("a")
        assertTrue(registry.slotOf("a") == slot)
        assertNull(registry.slotOf("unknown"))
    }
}
