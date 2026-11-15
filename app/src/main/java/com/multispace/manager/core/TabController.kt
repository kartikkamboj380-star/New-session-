package com.multispace.manager.core

/**
 * Pure, UI-free tab model shared by the browser-style tab strips
 * (main session tabs and in-session web tabs).
 */
data class TabState(
    val id: String,
    val title: String,
    val isActive: Boolean,
    val position: Int
)

/**
 * Manages an ordered list of tabs: create, switch, close. The active-tab
 * invariants are enforced here so every surface that shows tabs behaves
 * identically.
 */
class TabController(initialTabs: List<TabState> = emptyList()) {

    private val _tabs: MutableList<TabState> = initialTabs
        .sortedBy { it.position }
        .mapIndexed { index, tab -> tab.copy(position = index) }
        .toMutableList()

    val tabs: List<TabState> get() = _tabs.toList()

    val activeTabId: String? get() = _tabs.firstOrNull { it.isActive }?.id

    val size: Int get() = _tabs.size

    fun newTab(id: String, title: String): TabState {
        _tabs.forEachIndexed { i, t -> _tabs[i] = t.copy(isActive = false) }
        val tab = TabState(id = id, title = title, isActive = true, position = _tabs.size)
        _tabs.add(tab)
        return tab
    }

    fun switchTo(id: String): Boolean {
        val index = _tabs.indexOfFirst { it.id == id }
        if (index < 0) return false
        _tabs.forEachIndexed { i, t -> _tabs[i] = t.copy(isActive = i == index) }
        return true
    }

    /**
     * Closes a tab. If the closed tab was active, the neighbour becomes active
     * (right neighbour first, otherwise left). Returns false when the tab
     * does not exist.
     */
    fun close(id: String): Boolean {
        val index = _tabs.indexOfFirst { it.id == id }
        if (index < 0) return false
        val wasActive = _tabs[index].isActive
        _tabs.removeAt(index)
        _tabs.forEachIndexed { i, t -> _tabs[i] = t.copy(position = i) }
        if (wasActive && _tabs.isNotEmpty()) {
            val nextIndex = index.coerceAtMost(_tabs.size - 1)
            _tabs.forEachIndexed { i, t -> _tabs[i] = t.copy(isActive = i == nextIndex) }
        }
        return true
    }

    fun indexOf(id: String): Int = _tabs.indexOfFirst { it.id == id }
}
