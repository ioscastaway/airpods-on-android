package com.ioscastaway.airpods.pods

/**
 * Model ids as they appear in bytes 3–4 of the proximity-pairing beacon, plus the "A-numbers" the
 * accessory channel reports in its metadata packet.
 *
 * Community-collected, not Apple-published. Unknown ids still parse; they just show as their hex.
 */
enum class PodsModel(val id: Int, val label: String, val single: Boolean = false, val numbers: Set<String> = emptySet()) {
    AIRPODS_1(0x0220, "AirPods", numbers = setOf("A1523", "A1722")),
    AIRPODS_2(0x0F20, "AirPods (2nd gen)", numbers = setOf("A2031", "A2032")),
    AIRPODS_3(0x1320, "AirPods (3rd gen)", numbers = setOf("A2564", "A2565")),
    AIRPODS_4(0x1920, "AirPods 4", numbers = setOf("A3050", "A3052", "A3053")),
    AIRPODS_4_ANC(0x1B20, "AirPods 4 (ANC)", numbers = setOf("A3054", "A3055", "A3056")),
    AIRPODS_PRO(0x0E20, "AirPods Pro", numbers = setOf("A2083", "A2084")),
    AIRPODS_PRO_2(0x1420, "AirPods Pro (2nd gen)", numbers = setOf("A2698", "A2699")),
    AIRPODS_PRO_2_USBC(0x2420, "AirPods Pro (2nd gen, USB-C)", numbers = setOf("A3047", "A3048")),
    AIRPODS_MAX(0x0A20, "AirPods Max", single = true, numbers = setOf("A2096")),
    UNKNOWN(-1, "Unknown Apple audio device");

    companion object {
        fun fromId(id: Int): PodsModel = entries.firstOrNull { it.id == id } ?: UNKNOWN
        fun fromModelNumber(number: String?): PodsModel =
            number?.let { n -> entries.firstOrNull { n.uppercase() in it.numbers } } ?: UNKNOWN
    }
}
