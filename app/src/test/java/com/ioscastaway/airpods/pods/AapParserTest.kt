package com.ioscastaway.airpods.pods

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AapParserTest {

    private fun hex(s: String) = s.replace(" ", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test
    fun `battery packet from the LibrePods reference decodes per component`() {
        val e = AapParser.parse(hex("04 00 04 00 04 00 03 02 01 64 02 01 04 01 63 01 01 08 01 11 02 01"))
        assertTrue(e is AapParser.Event.Battery); e as AapParser.Event.Battery
        assertEquals(AapParser.Part(100, AapParser.State.DISCHARGING), e.right)
        assertEquals(AapParser.Part(99, AapParser.State.CHARGING), e.left)
        assertEquals(AapParser.Part(17, AapParser.State.DISCHARGING), e.case)
    }

    @Test
    fun `ear packet counts pods in ears`() {
        val e = AapParser.parse(hex("04 00 04 00 06 00 00 01")) as AapParser.Event.Ear
        assertEquals(AapParser.Pod.IN_EAR, e.primary)
        assertEquals(AapParser.Pod.OUT, e.secondary)
        assertEquals(1, e.inEarCount)
        assertEquals(0, (AapParser.parse(hex("04 00 04 00 06 00 02 02")) as AapParser.Event.Ear).inEarCount)
        assertEquals(2, (AapParser.parse(hex("04 00 04 00 06 00 00 00")) as AapParser.Event.Ear).inEarCount)
    }

    @Test
    fun `metadata packet captured from AirPods 4 yields name and model number`() {
        // First 60 bytes of the 0x1D packet the pods sent on a Galaxy Z Fold 8 (firmware 8B39).
        val e = AapParser.parse(hex(
            "040004001D0002F4000400EC8898ED9DACEC9D9820416972506F6473202332004133303533004170706C6520496E632E00" +
                "46515946395159395834003831323100"
        )) as AapParser.Event.Metadata
        assertEquals("수희의 AirPods #2", e.name)
        assertEquals("A3053", e.modelNumber)
        assertEquals("Apple Inc.", e.manufacturer)
        assertEquals(PodsModel.AIRPODS_4, PodsModel.fromModelNumber(e.modelNumber))
    }

    @Test
    fun `packets without the AAP header are ignored`() {
        assertNull(AapParser.parse(hex("01 00 04 00 00 00 01 00 03 00")))
        assertNull(AapParser.parse(hex("04 00")))
        assertEquals(AapParser.Event.Other(0x09), AapParser.parse(hex("04 00 04 00 09 00 0D 02 00 00 00")))
    }
}
