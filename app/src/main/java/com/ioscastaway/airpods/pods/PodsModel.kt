package com.ioscastaway.airpods.pods

/**
 * Model ids as they appear in bytes 3–4 of the proximity-pairing beacon.
 *
 * Community-collected, not Apple-published. Unknown ids still parse; they just show as their hex.
 */
enum class PodsModel(val id: Int, val label: String, val single: Boolean = false) {
    AIRPODS_1(0x0220, "AirPods"),
    AIRPODS_2(0x0F20, "AirPods (2nd gen)"),
    AIRPODS_3(0x1320, "AirPods (3rd gen)"),
    AIRPODS_4(0x1920, "AirPods 4"),
    AIRPODS_4_ANC(0x1B20, "AirPods 4 (ANC)"),
    AIRPODS_PRO(0x0E20, "AirPods Pro"),
    AIRPODS_PRO_2(0x1420, "AirPods Pro (2nd gen)"),
    AIRPODS_PRO_2_USBC(0x2420, "AirPods Pro (2nd gen, USB-C)"),
    AIRPODS_MAX(0x0A20, "AirPods Max", single = true),
    UNKNOWN(-1, "Unknown Apple audio device");

    companion object {
        fun fromId(id: Int): PodsModel = entries.firstOrNull { it.id == id } ?: UNKNOWN
    }
}
