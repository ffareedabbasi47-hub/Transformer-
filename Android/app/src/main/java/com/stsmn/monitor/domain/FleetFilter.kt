package com.stsmn.monitor.domain

/** The four filter chips. OFFLINE also covers STALE (no fresh data), exactly like the status counts. */
enum class StateFilter(val label: String) {
    SAFE("SAFE"), WARNING("WARNING"), EMERGENCY("EMERGENCY"), OFFLINE("OFFLINE");

    fun matches(s: MarkerState): Boolean = when (this) {
        SAFE -> s == MarkerState.SAFE
        WARNING -> s == MarkerState.WARNING
        EMERGENCY -> s == MarkerState.EMERGENCY
        OFFLINE -> s == MarkerState.OFFLINE || s == MarkerState.STALE
    }
}

/** Search and filter over the real transformer list. Pure functions: nothing is invented. */
object FleetFilter {
    private fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

    /** Empty query matches everything; "tr001", "TR-001" and "stsmn-tr-001" all find STSMN-TR-001. */
    fun matchesQuery(d: MapDevice, query: String): Boolean {
        val q = norm(query)
        if (q.isEmpty()) return true
        return norm(d.id).contains(q) || norm(d.label).contains(q) || norm(d.name).contains(q)
    }

    /** `active` empty = no state filter. */
    fun apply(devices: List<MapDevice>, query: String, active: Set<StateFilter>): List<MapDevice> =
        devices.filter { d -> matchesQuery(d, query) && (active.isEmpty() || active.any { it.matches(d.state) }) }

    fun counts(devices: List<MapDevice>): Map<StateFilter, Int> =
        StateFilter.values().associateWith { f -> devices.count { f.matches(it.state) } }
}
