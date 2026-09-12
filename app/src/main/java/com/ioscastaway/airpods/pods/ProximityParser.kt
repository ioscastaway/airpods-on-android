package com.ioscastaway.airpods.pods

/**
 * Decodes Apple's "proximity pairing" BLE advertisement (manufacturer id 0x004C, type 0x07).
 *
 * This is category 3 in the series' precision scale: an undocumented Apple protocol, decoded by
 * the community (OpenPods, CAPod and others) and verified here against AirPods 4 on firmware 8B39.
 * The layout below is expressed in hex-nibble indexes over the 27 manufacturer-data bytes, the
 * same convention those projects use, so it can be compared against them line by line.
 *
 * ```
 * byte  0      0x07  message type: proximity pairing
 * byte  1      payload length (0x19 on older firmware; not relied on)
 * byte  3–4    model id (big-endian), e.g. 0x1920 = AirPods 4
 * nibble 10    bit 0x02 clear  → pods are "flipped": left/right fields are swapped
 * nibble 11    in-ear bits: 0x02 and 0x08 (which is left depends on flip)
 * nibble 12/13 battery of one pod each, 0–10 → 0–100 %, 15 → unknown
 * nibble 14    charging bits: 0x01 / 0x02 pods (flip-dependent), 0x04 case
 * nibble 15    case battery, same encoding
 * ```
 *
 * Nothing after byte 8 is used; newer firmware encrypts the tail.
 */
object ProximityParser {

    const val APPLE_COMPANY_ID = 0x004C
    const val TYPE_PROXIMITY_PAIRING: Byte = 0x07
    /** Hardware filter: Apple + message type only. The length byte differs between firmware generations. */
    val PREFIX: ByteArray = byteArrayOf(TYPE_PROXIMITY_PAIRING)
    val PREFIX_MASK: ByteArray = byteArrayOf(0xFF.toByte())
    /** Everything this parser reads sits in bytes 0–7. */
    private const val MIN_LENGTH = 8

    fun looksLikePods(data: ByteArray?): Boolean =
        data != null && data.size >= MIN_LENGTH && data[0] == TYPE_PROXIMITY_PAIRING

    fun parse(data: ByteArray, rssi: Int, timestampMs: Long): PodsStatus? {
        if (!looksLikePods(data)) return null

        fun nibble(index: Int): Int {
            val b = data[index / 2].toInt() and 0xFF
            return if (index % 2 == 0) b ushr 4 else b and 0x0F
        }

        val modelId = ((data[3].toInt() and 0xFF) shl 8) or (data[4].toInt() and 0xFF)
        val flipped = (nibble(10) and 0x02) == 0

        val ear = nibble(11)
        val inEarLeft = ear and (if (flipped) 0x08 else 0x02) != 0
        val inEarRight = ear and (if (flipped) 0x02 else 0x08) != 0

        val left = nibble(if (flipped) 12 else 13)
        val right = nibble(if (flipped) 13 else 12)
        val case = nibble(15)

        val charge = nibble(14)
        val leftCharging = charge and (if (flipped) 0x02 else 0x01) != 0
        val rightCharging = charge and (if (flipped) 0x01 else 0x02) != 0
        val caseCharging = charge and 0x04 != 0

        return PodsStatus(
            modelId = modelId,
            model = PodsModel.fromId(modelId),
            leftBattery = percent(left),
            rightBattery = percent(right),
            caseBattery = percent(case),
            leftCharging = leftCharging,
            rightCharging = rightCharging,
            caseCharging = caseCharging,
            inEarLeft = inEarLeft,
            inEarRight = inEarRight,
            flipped = flipped,
            rssi = rssi,
            timestampMs = timestampMs,
            raw = data.copyOf(),
        )
    }

    private fun percent(nibble: Int): Int? = if (nibble in 0..10) nibble * 10 else null
}
