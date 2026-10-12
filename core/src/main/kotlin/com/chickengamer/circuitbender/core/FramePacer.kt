package com.chickengamer.circuitbender.core

/**
 * Decides which camera frames to process so the phone stays cool: never more
 * than [maxFps], never busy more than [maxBusy] of the time (after a 40 ms
 * frame the cores rest ~27 ms), and a lower cap when the phone reports heat.
 * Thermal levels follow Android's PowerManager.THERMAL_STATUS_* numbering.
 */
class FramePacer(val maxFps: Int = 30, val maxBusy: Float = 0.6f) {
    @Volatile var thermal: Int = 0

    private var lastStart = Long.MIN_VALUE / 2
    private var restUntil = Long.MIN_VALUE / 2

    /** The frame rate allowed at the current temperature. */
    val fpsCap: Float
        get() = when {
            thermal >= SEVERE -> maxFps / 4f
            thermal >= MODERATE -> maxFps / 2f
            else -> maxFps.toFloat()
        }

    fun shouldProcess(nowMs: Long): Boolean {
        // 10% slack absorbs camera timestamp jitter at exactly the cap.
        val interval = 1000f / fpsCap * 0.9f
        return nowMs - lastStart >= interval && nowMs >= restUntil
    }

    fun finished(startMs: Long, costMs: Long) {
        lastStart = startMs
        val rest = (costMs * (1f / maxBusy - 1f)).toLong()
        restUntil = startMs + costMs + rest
    }

    companion object {
        const val MODERATE = 2
        const val SEVERE = 3
    }
}
