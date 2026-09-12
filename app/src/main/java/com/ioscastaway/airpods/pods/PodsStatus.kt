package com.ioscastaway.airpods.pods

/**
 * One decoded status beacon.
 *
 * Battery values are percentages in steps of 10, or null when the beacon reports "unknown"
 * (a pod that is out of range of the case, for instance). [inEarLeft]/[inEarRight] come from
 * undocumented status bits and are the basis of auto-pause; see [ProximityParser].
 */
data class PodsStatus(
    val modelId: Int,
    val model: PodsModel,
    val leftBattery: Int?,
    val rightBattery: Int?,
    val caseBattery: Int?,
    val leftCharging: Boolean,
    val rightCharging: Boolean,
    val caseCharging: Boolean,
    val inEarLeft: Boolean,
    val inEarRight: Boolean,
    val flipped: Boolean,
    val rssi: Int,
    val timestampMs: Long,
    val raw: ByteArray,
) {
    val inEarCount: Int get() = (if (inEarLeft) 1 else 0) + (if (inEarRight) 1 else 0)

    fun rawHex(): String = raw.joinToString("") { "%02X".format(it) }

    override fun equals(other: Any?): Boolean = other is PodsStatus && other.rawHex() == rawHex()
    override fun hashCode(): Int = rawHex().hashCode()
}
