package com.stsmn.monitor.domain

class BleLineAssembler(private val maxLine: Int = 2048) {
    private val sb = StringBuilder()
    private var dropping = false

    fun feed(chunk: String): List<String> {
        val out = ArrayList<String>()
        for (c in chunk) {
            if (c == '\n' || c == '\r') {
                if (!dropping && sb.isNotEmpty()) out.add(sb.toString())
                sb.setLength(0); dropping = false
            } else if (!dropping) {
                sb.append(c)
                if (sb.length > maxLine) { sb.setLength(0); dropping = true }
            }
        }
        return out
    }

    fun reset() { sb.setLength(0); dropping = false }
}

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

        fun phoneCommand(password: String, phone: String): String = "PHONE $password $phone\n"
    }
}
