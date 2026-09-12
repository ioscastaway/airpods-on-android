package com.ioscastaway.airpods.pods

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProximityParserTest {

    /** Builds a 27-byte beacon from the nibbles this parser cares about. */
    private fun frame(
        model: Int = 0x1920,
        flipNibble: Int = 0x2,   // bit 0x02 set → not flipped
        earNibble: Int = 0x0,
        podA: Int = 0xF, podB: Int = 0xF,
        chargeNibble: Int = 0x0,
        case: Int = 0xF,
    ): ByteArray {
        val b = ByteArray(27)
        b[0] = 0x07; b[1] = 0x19; b[2] = 0x01
        b[3] = (model shr 8).toByte(); b[4] = (model and 0xFF).toByte()
        b[5] = ((flipNibble shl 4) or earNibble).toByte()
        b[6] = ((podA shl 4) or podB).toByte()
        b[7] = ((chargeNibble shl 4) or case).toByte()
        return b
    }

    @Test
    fun `rejects anything that is not a proximity pairing frame`() {
        assertNull(ProximityParser.parse(byteArrayOf(0x10, 0x05, 0x01), -50, 0))
        assertNull(ProximityParser.parse(frame().copyOf(20), -50, 0))
    }

    @Test
    fun `decodes model, batteries and case when not flipped`() {
        // not flipped: nibble 12 = right, nibble 13 = left
        val s = ProximityParser.parse(frame(podA = 0x8, podB = 0x9, case = 0x5), -40, 123)!!
        assertEquals(PodsModel.AIRPODS_4, s.model)
        assertFalse(s.flipped)
        assertEquals(90, s.leftBattery)
        assertEquals(80, s.rightBattery)
        assertEquals(50, s.caseBattery)
        assertEquals(-40, s.rssi)
        assertEquals(123L, s.timestampMs)
    }

    @Test
    fun `swaps left and right when the flip bit is clear`() {
        val s = ProximityParser.parse(frame(flipNibble = 0x0, podA = 0x8, podB = 0x9), -40, 0)!!
        assertTrue(s.flipped)
        assertEquals(80, s.leftBattery)
        assertEquals(90, s.rightBattery)
    }

    @Test
    fun `fifteen means unknown battery`() {
        val s = ProximityParser.parse(frame(podA = 0xF, podB = 0x3, case = 0xF), -40, 0)!!
        assertEquals(30, s.leftBattery)
        assertNull(s.rightBattery)
        assertNull(s.caseBattery)
    }

    @Test
    fun `charging bits follow the flip too`() {
        val s = ProximityParser.parse(frame(chargeNibble = 0b0101), -40, 0)!!   // pod bit 0x01 + case
        assertTrue(s.leftCharging)
        assertFalse(s.rightCharging)
        assertTrue(s.caseCharging)
        val f = ProximityParser.parse(frame(flipNibble = 0x0, chargeNibble = 0b0001), -40, 0)!!
        assertFalse(f.leftCharging)
        assertTrue(f.rightCharging)
    }

    @Test
    fun `in-ear bits are read per pod`() {
        val both = ProximityParser.parse(frame(earNibble = 0x0A), -40, 0)!!
        assertTrue(both.inEarLeft); assertTrue(both.inEarRight); assertEquals(2, both.inEarCount)
        val leftOnly = ProximityParser.parse(frame(earNibble = 0x02), -40, 0)!!
        assertTrue(leftOnly.inEarLeft); assertFalse(leftOnly.inEarRight)
        val flippedLeft = ProximityParser.parse(frame(flipNibble = 0x0, earNibble = 0x08), -40, 0)!!
        assertTrue(flippedLeft.inEarLeft); assertFalse(flippedLeft.inEarRight)
    }

    @Test
    fun `unknown model ids still parse`() {
        val s = ProximityParser.parse(frame(model = 0x7777, podA = 0x5, podB = 0x5), -40, 0)!!
        assertEquals(PodsModel.UNKNOWN, s.model)
        assertEquals(0x7777, s.modelId)
        assertEquals(50, s.leftBattery)
    }
}
