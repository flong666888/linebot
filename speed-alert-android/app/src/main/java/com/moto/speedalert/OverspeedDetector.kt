package com.moto.speedalert

/**
 * Decides when the overspeed beeping starts and stops.
 *
 * Beeping starts after [confirmFixes] consecutive GPS fixes at or above the threshold
 * (so a single GPS glitch does not trigger it) and stops on the first fix below it.
 */
class OverspeedDetector(private val confirmFixes: Int = 2) {
    var alarming = false
        private set
    private var overCount = 0

    /** Feed one GPS fix; returns whether the alarm should be sounding now. */
    fun update(speedKmh: Double, threshold: Int): Boolean {
        if (speedKmh >= threshold) {
            overCount++
            if (overCount >= confirmFixes) alarming = true
        } else {
            overCount = 0
            alarming = false
        }
        return alarming
    }

    fun reset() {
        overCount = 0
        alarming = false
    }
}
