package com.stsmn.monitor.domain

enum class StateFilter(val label: String) {
    SAFE("SAFE"), WARNING("WARNING"), EMERGENCY("EMERGENCY"), OFFLINE("OFFLINE");

    fun matches(s: MarkerState): Boolean = when (this) {
        SAFE -> s == MarkerState.SAFE
        WARNING -> s == MarkerState.WARNING
        EMERGENCY -> s == MarkerState.EMERGENCY
        OFFLINE -> s == MarkerState.OFFLINE || s == MarkerState.STALE
    }
}

object FleetFilter {
    private fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

    fun matchesQuery(d: MapDevice, query: String): Boolean {
        val q = norm(query)
        if (q.isEmpty()) return true
        return norm(d.id).contains(q) || norm(d.label).contains(q) || norm(d.name).contains(q)
    }

    fun apply(devices: List<MapDevice>, query: String, active: Set<StateFilter>): List<MapDevice> =
        devices.filter { d -> matchesQuery(d, query) && (active.isEmpty() || active.any { it.matches(d.state) }) }

    fun counts(devices: List<MapDevice>): Map<StateFilter, Int> =
        StateFilter.values().associateWith { f -> devices.count { f.matches(it.state) } }
}
