package com.ioscastaway.airpods.pods

/**
 * Decodes the packets AirPods send over their accessory channel (AAP: Apple Accessory Protocol,
 * classic L2CAP PSM 0x1001). This is the channel the iPhone uses — precision-scale category 3,
 * reverse-engineered by the LibrePods project and verified here against AirPods 4 (8B39).
 *
 * Every notification starts with the header `04 00 04 00`, then a one-byte opcode and a zero.
 *
 * ```
 * battery   04 00 04 00 04 00 <n> ( <part> 01 <level> <state> 01 ) × n
 *           part: 02 right, 04 left, 08 case · level 0–100 · state: 01 charging, 02 discharging, 04 disconnected
 * ear       04 00 04 00 06 00 <primary> <secondary>     00 in ear, 01 out, 02 in case
 * metadata  04 00 04 00 1D <…> NUL-separated strings: name, model number, maker, serial, firmware, …
 * ```
 *
 * Ear detection reports "primary" and "secondary" pods, not left and right; which physical pod is
 * primary changes as they are taken in and out. Pause/resume only needs the count, so that is what
 * this exposes.
 */
object AapParser {

    val HEADER = byteArrayOf(0x04, 0x00, 0x04, 0x00)

    sealed interface Event {
        data class Battery(val left: Part?, val right: Part?, val case: Part?) : Event
        data class Ear(val primary: Pod, val secondary: Pod) : Event {
            val inEarCount: Int get() = (if (primary == Pod.IN_EAR) 1 else 0) + (if (secondary == Pod.IN_EAR) 1 else 0)
        }
        data class Metadata(val name: String, val modelNumber: String, val manufacturer: String, val serial: String, val firmware: String) : Event
        data class Other(val opcode: Int) : Event
    }

    /** One battery component. [percent] is 0–100; [state] is what the pods say about it. */
    data class Part(val percent: Int, val state: State)
    enum class State { UNKNOWN, CHARGING, DISCHARGING, DISCONNECTED }
    enum class Pod { IN_EAR, OUT, IN_CASE, UNKNOWN }

    const val OPCODE_BATTERY = 0x04
    const val OPCODE_EAR = 0x06
    const val OPCODE_CONTROL = 0x09
    const val OPCODE_METADATA = 0x1D

    fun parse(packet: ByteArray): Event? {
        if (packet.size < 6) return null
        if (!HEADER.indices.all { packet[it] == HEADER[it] }) return null
        return when (val opcode = packet[4].toInt() and 0xFF) {
            OPCODE_BATTERY -> battery(packet)
            OPCODE_EAR -> ear(packet)
            OPCODE_METADATA -> metadata(packet)
            else -> Event.Other(opcode)
        }
    }

    private fun battery(p: ByteArray): Event? {
        if (p.size < 7) return null
        val count = p[6].toInt() and 0xFF
        var left: Part? = null; var right: Part? = null; var case: Part? = null
        var i = 7
        repeat(count) {
            if (i + 4 >= p.size) return@repeat
            val part = p[i].toInt() and 0xFF
            val level = p[i + 2].toInt() and 0xFF
            val state = when (p[i + 3].toInt() and 0xFF) {
                0x01 -> State.CHARGING; 0x02 -> State.DISCHARGING; 0x04 -> State.DISCONNECTED; else -> State.UNKNOWN
            }
            val value = Part(level.coerceIn(0, 100), state)
            when (part) { 0x04 -> left = value; 0x02 -> right = value; 0x08 -> case = value }
            i += 5
        }
        return Event.Battery(left, right, case)
    }

    private fun ear(p: ByteArray): Event? {
        if (p.size < 8) return null
        fun pod(b: Byte) = when (b.toInt() and 0xFF) { 0x00 -> Pod.IN_EAR; 0x01 -> Pod.OUT; 0x02 -> Pod.IN_CASE; else -> Pod.UNKNOWN }
        return Event.Ear(pod(p[6]), pod(p[7]))
    }

    private fun metadata(p: ByteArray): Event? {
        // After the header and opcode come a 16-bit payload length and two flag bytes; strings start at byte 11.
        if (p.size < 12) return null
        val body = p.copyOfRange(11, p.size)
        val strings = ArrayList<String>()
        var start = 0
        for (idx in body.indices) {
            if (body[idx] == 0.toByte()) { strings += String(body, start, idx - start, Charsets.UTF_8); start = idx + 1 }
            if (strings.size >= 5) break
        }
        if (strings.size < 5) return null
        return Event.Metadata(strings[0], strings[1], strings[2], strings[3], strings[4])
    }

    // Outgoing packets, as documented by LibrePods.
    val HANDSHAKE = byteArrayOf(0x00, 0x00, 0x04, 0x00, 0x01, 0x00, 0x02, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)
    val SET_FEATURES = byteArrayOf(0x04, 0x00, 0x04, 0x00, 0x4d, 0x00, 0xff.toByte(), 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)
    val REQUEST_NOTIFICATIONS = byteArrayOf(0x04, 0x00, 0x04, 0x00, 0x0f, 0x00, 0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte())
}
