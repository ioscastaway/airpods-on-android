package com.ioscastaway.airpods.pods

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import com.ioscastaway.airpods.pods.EarDetector.Action

class EarDetectorTest {

    private var playing = true
    private var settings = EarDetector.Settings(stableMs = 300)
    private val detector = EarDetector({ settings }, { playing })

    private fun status(inEar: Int, battery: Int? = 80) = PodsStatus(
        modelId = 0x1920, model = PodsModel.AIRPODS_4,
        leftBattery = battery, rightBattery = battery, caseBattery = 50,
        leftCharging = false, rightCharging = false, caseCharging = false,
        inEarLeft = inEar >= 1, inEarRight = inEar == 2,
        flipped = false, rssi = -40, timestampMs = 0, raw = ByteArray(27) { inEar.toByte() },
    )

    /** Feed the same reading until it is confirmed (past stableMs). */
    private fun settle(inEar: Int, from: Long): Action? {
        var a: Action? = null
        var t = from
        repeat(3) { a = detector.onStatus(status(inEar), t) ?: a; t += 200 }
        return a
    }

    @Test
    fun `removing one pod pauses, putting it back resumes`() {
        assertNull(settle(2, 0))                       // baseline: both in
        playing = true
        assertEquals(Action.PAUSE, settle(1, 1000))    // one out
        playing = false                                 // we paused it
        assertEquals(Action.RESUME, settle(2, 3000))   // back in
    }

    @Test
    fun `a flicker shorter than stableMs is ignored`() {
        settle(2, 0)
        assertNull(detector.onStatus(status(1), 5000))
        assertNull(detector.onStatus(status(1), 5100))
        assertNull(detector.onStatus(status(2), 5200))   // back before it settled
        assertNull(detector.onStatus(status(2), 5600))
    }

    @Test
    fun `does not resume if it never paused, or if the user resumed themselves`() {
        settle(2, 0)
        playing = false                                 // user had already paused
        assertNull(settle(1, 1000))                     // nothing to pause
        assertNull(settle(2, 3000))                     // so nothing to resume
        // and: we pause, user hits play manually, then pod goes back in → no double play
        playing = true
        assertEquals(Action.PAUSE, settle(1, 6000))
        playing = true                                  // user resumed
        assertNull(settle(2, 8000))
    }

    @Test
    fun `both-pods mode waits for both to come out`() {
        settings = settings.copy(mode = EarDetector.Mode.BOTH_PODS)
        settle(2, 0)
        assertNull(settle(1, 1000))
        assertEquals(Action.PAUSE, settle(0, 2000))
        playing = false
        assertEquals(Action.RESUME, settle(1, 4000))
    }

    @Test
    fun `resume window expires`() {
        settle(2, 0)
        assertEquals(Action.PAUSE, settle(1, 1000))
        playing = false
        assertNull(settle(2, 1000 + 11 * 60 * 1000))
    }

    @Test
    fun `pods in the case say nothing about ears`() {
        settle(2, 0)
        assertNull(detector.onStatus(status(0, battery = null), 1000))
        assertNull(detector.onStatus(status(0, battery = null), 2000))
    }

    @Test
    fun `disabled means silent`() {
        settings = settings.copy(enabled = false)
        assertNull(settle(2, 0)); assertNull(settle(1, 1000))
    }
}
