package com.ioscastaway.airpods.pods

/**
 * Turns a stream of in-ear readings into pause/resume decisions, the way iOS does it:
 * take a pod out and playback pauses; put it back and playback resumes — but only if it was
 * this detector that paused it, and only for a while.
 *
 * Pure logic, no Android: readings in, [Action]s out. The service supplies the clock and whether
 * audio is currently playing.
 */
class EarDetector(
    private val settings: () -> Settings,
    private val isPlaying: () -> Boolean,
) {
    enum class Action { PAUSE, RESUME }

    enum class Mode {
        /** Pause as soon as either pod leaves an ear (iOS behaviour). */
        ONE_POD,
        /** Pause only when both pods are out. */
        BOTH_PODS,
    }

    data class Settings(
        val enabled: Boolean = true,
        val mode: Mode = Mode.ONE_POD,
        /** A new in-ear state must hold this long before it counts; beacons flicker. */
        val stableMs: Long = 350,
        /** Auto-resume only within this window after an auto-pause. */
        val resumeWindowMs: Long = 10 * 60 * 1000,
    )

    private var confirmedCount: Int? = null
    private var candidateCount: Int? = null
    private var candidateSinceMs = 0L
    private var pausedAtMs: Long? = null

    /** Feed every decoded beacon. Returns an action when one is due, otherwise null. */
    fun onStatus(status: PodsStatus, nowMs: Long): Action? {
        val s = settings()
        if (!s.enabled) { reset(); return null }
        // Both pods in the case (no battery seen for either) tells us nothing about ears.
        if (status.leftBattery == null && status.rightBattery == null) return null

        val count = status.inEarCount
        if (count != candidateCount) {
            candidateCount = count
            candidateSinceMs = nowMs
        }
        if (nowMs - candidateSinceMs < s.stableMs) return null

        val previous = confirmedCount
        confirmedCount = count
        if (previous == null || previous == count) return null

        val wornBefore = worn(previous, s.mode)
        val wornNow = worn(count, s.mode)
        return when {
            wornBefore && !wornNow -> {
                if (isPlaying()) { pausedAtMs = nowMs; Action.PAUSE } else null
            }
            !wornBefore && wornNow -> {
                val at = pausedAtMs ?: return null
                pausedAtMs = null
                if (nowMs - at <= s.resumeWindowMs && !isPlaying()) Action.RESUME else null
            }
            else -> null
        }
    }

    /** Call when the pods disconnect: a stale "we paused it" must not resume something later. */
    fun reset() {
        confirmedCount = null
        candidateCount = null
        pausedAtMs = null
    }

    private fun worn(count: Int, mode: Mode): Boolean = when (mode) {
        Mode.ONE_POD -> count == 2
        Mode.BOTH_PODS -> count >= 1
    }
}
