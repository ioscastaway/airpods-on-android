package com.ioscastaway.airpods.pods

/**
 * One snapshot of the pods, from either source.
 *
 * From the BLE beacon: battery in steps of 10 (null = "unknown"), in-ear bits per side.
 * From the accessory channel (AAP): battery in 1 % steps, in-ear as a count — the channel reports
 * "primary/secondary", not left/right, so [earSidesKnown] is false and [inEarLeft]/[inEarRight]
 * only encode how many pods are in ears.
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
    val source: Source = Source.BEACON,
    val earSidesKnown: Boolean = true,
) {
    enum class Source { BEACON, AAP }

    val inEarCount: Int get() = (if (inEarLeft) 1 else 0) + (if (inEarRight) 1 else 0)

    fun rawHex(): String = raw.joinToString("") { "%02X".format(it) }

    override fun equals(other: Any?): Boolean = other is PodsStatus && other.source == source && other.rawHex() == rawHex()
    override fun hashCode(): Int = rawHex().hashCode()
}
