package com.multispace.manager

import com.multispace.manager.core.TabController
import com.multispace.manager.core.TabState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Browser-style tab strip behaviour: new / switch / close / active indicator. */
class TabControllerTest {

    @Test
    fun `new tab becomes the active tab`() {
        val tabs = TabController()
        tabs.newTab("1", "First")
        tabs.newTab("2", "Second")
        assertEquals("2", tabs.activeTabId)
        assertEquals(2, tabs.size)
    }

    @Test
    fun `switching activates only the target tab`() {
        val tabs = TabController()
        tabs.newTab("1", "First")
        tabs.newTab("2", "Second")
        tabs.switchTo("1")
        assertEquals("1", tabs.activeTabId)
        assertFalse(tabs.tabs.first { it.id == "2" }.isActive)
        assertTrue(tabs.tabs.first { it.id == "1" }.isActive)
    }

    @Test
    fun `switching to missing tab returns false`() {
        val tabs = TabController()
        tabs.newTab("1", "First")
        assertFalse(tabs.switchTo("missing"))
        assertEquals("1", tabs.activeTabId)
    }

    @Test
    fun `closing the active tab activates the right neighbour`() {
        val tabs = TabController()
        tabs.newTab("1", "A")
        tabs.newTab("2", "B")
        tabs.newTab("3", "C")
        tabs.switchTo("2")
        assertTrue(tabs.close("2"))
        assertEquals("3", tabs.activeTabId)
        assertEquals(2, tabs.size)
        assertEquals(listOf(0, 1), tabs.tabs.map { it.position })
    }

    @Test
    fun `closing the last active tab falls back to left neighbour`() {
        val tabs = TabController()
        tabs.newTab("1", "A")
        tabs.newTab("2", "B")
        tabs.switchTo("2")
        tabs.close("2")
        assertEquals("1", tabs.activeTabId)
    }

    @Test
    fun `closing a background tab keeps the active tab`() {
        val tabs = TabController()
        tabs.newTab("1", "A")
        tabs.newTab("2", "B")
        tabs.close("1")
        assertEquals("2", tabs.activeTabId)
        assertEquals(1, tabs.size)
    }

    @Test
    fun `closing missing tab returns false`() {
        val tabs = TabController()
        tabs.newTab("1", "A")
        assertFalse(tabs.close("missing"))
    }

    @Test
    fun `initial tabs are ordered by position and normalized`() {
        val tabs = TabController(
            listOf(
                TabState("b", "B", false, 1),
                TabState("a", "A", true, 0)
            )
        )
        assertEquals(listOf("a", "b"), tabs.tabs.map { it.id })
        assertEquals("a", tabs.activeTabId)
    }

    @Test
    fun `empty controller has no active tab`() {
        assertNull(TabController().activeTabId)
    }
}
