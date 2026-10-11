package com.stsmn.monitor.ui

import com.stsmn.monitor.domain.GlobeData
import com.stsmn.monitor.domain.MapDevice

/**
 * Compatibility wrapper used by the unit tests and any UI code that still expects a
 * top-level globe JSON helper in the UI package.
 */
fun globeJson(devices: List<MapDevice>): String = GlobeData.json(devices)
