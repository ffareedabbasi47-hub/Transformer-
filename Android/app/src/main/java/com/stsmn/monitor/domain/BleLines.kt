package com.stsmn.monitor.domain

/**
 * The ESP32 sends text lines over BLE in small (20 byte) notifications. This puts them back together.
 * Pure logic, unit-tested on the JVM.
 */
class BleLineAssembler(private val maxLine: Int = 2048) {
    private val sb = StringBuilder()

    /** Feed one notification; returns every COMPLETE line it finished (without the line break). */
    fun feed(chunk: String): List<String> {
        val out = ArrayList<String>()
        for (c in chunk) {
            if (c == '\n' || c == '\r') {
                if (sb.isNotEmpty()) { out.add(sb.toString()); sb.setLength(0) }
            } else {
                sb.append(c)
                if (sb.length > maxLine) sb.setLength(0)    // runaway line: drop it, resync at the next line break
            }
        }
        return out
    }

    fun reset() { sb.setLength(0) }
}

/** One line of the ESP32 BLE stream: "ST <status json>", "HE <health json>", "OK <text>", "ER <text>". */
data class BleMessage(val kind: Kind, val payload: String) {
    enum class Kind { STATUS, HEALTH, OK, ERROR }

    companion object {
        fun parse(line: String): BleMessage? {
            if (line.length < 3 || line[2] != ' ') return null
            val payload = line.substring(3)
            return when (line.substring(0, 2)) {
                "ST" -> BleMessage(Kind.STATUS, payload)
                "HE" -> BleMessage(Kind.HEALTH, payload)
                "OK" -> BleMessage(Kind.OK, payload)
                "ER" -> BleMessage(Kind.ERROR, payload)
                else -> null
            }
        }

        /** Command that saves the SMS number over BLE (needs the device password; the number may be empty = SMS off). */
        fun phoneCommand(password: String, phone: String): String = "PHONE $password $phone\n"
    }
}
