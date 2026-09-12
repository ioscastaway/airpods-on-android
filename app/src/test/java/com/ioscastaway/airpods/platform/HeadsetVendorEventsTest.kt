package com.ioscastaway.airpods.platform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HeadsetVendorEventsTest {
    @Test
    fun `parses battery and dock from IPHONEACCEV args`() {
        // +IPHONEACCEV=2,1,7,2,0  → two pairs: battery level 7 (→ 80 %), not docked
        val (battery, dock) = HeadsetVendorEvents.parseAccev(arrayOf(2, 1, 7, 2, 0))!!
        assertEquals(80, battery)
        assertEquals(false, dock)
    }

    @Test
    fun `battery only, as strings`() {
        val (battery, dock) = HeadsetVendorEvents.parseAccev(arrayOf("1", "1", "9"))!!
        assertEquals(100, battery)
        assertNull(dock)
    }

    @Test
    fun `empty args give nothing`() {
        assertNull(HeadsetVendorEvents.parseAccev(arrayOf<Any>()))
    }
}
