package com.taskflow.domain

import com.taskflow.shake.ShakeDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class ShakeDetectorTest {
    private val g = ShakeDetector.GRAVITY

    /** Readings every 20 ms (50 Hz, like SENSOR_DELAY_GAME) of a motion along x on top of gravity on z. */
    private fun feed(d: ShakeDetector, durationMs: Long, startMs: Long = 0, ax: (Long) -> Float): Int {
        var shakes = 0
        var t = startMs
        while (t < startMs + durationMs) {
            if (d.onReading(t, ax(t - startMs), 0f, g)) shakes++
            t += 20
        }
        return shakes
    }

    /** Back-and-forth shake: [hz] swings per second with [peakG] extra acceleration. */
    private fun shake(hz: Double, peakG: Float) = { t: Long -> (peakG * g * sin(2 * PI * hz * t / 1000.0)).toFloat() }

    @Test fun `phone resting or walking never triggers`() {
        val d = ShakeDetector()
        assertEquals(0, feed(d, 10_000) { 0f })                        // on a table
        assertEquals(0, feed(d, 20_000, 10_000, shake(2.0, 0.6f)))   // walking (~1.2 g)
        assertEquals(0, feed(d, 20_000, 30_000, shake(3.0, 1.2f)))   // running / bumpy bus (~1.6 g)
    }

    @Test fun `a single bump does not trigger`() {
        val d = ShakeDetector()
        var t = 0L
        repeat(5) {                       // five isolated bumps, 2 s apart
            assertFalse(d.onReading(t, 3.5f * g, 0f, g))
            t += 2_000
        }
    }

    @Test fun `a deliberate shake triggers once`() {
        val d = ShakeDetector()
        assertEquals(1, feed(d, 1_500, ax = shake(3.5, 3.0f)))
    }

    @Test fun `cooldown avoids double triggers, then it works again`() {
        val d = ShakeDetector()
        assertEquals(1, feed(d, 2_000, ax = shake(3.5, 3.0f)))
        assertEquals(1, feed(d, 1_500, startMs = 5_000, ax = shake(3.5, 3.0f)))
    }

    @Test fun `sensitivity changes how hard the shake must be`() {
        val light = shake(3.5, 2.0f)  // ~2.2 g peaks
        assertEquals(0, feed(ShakeDetector(ShakeDetector.Sensitivity.LOW.thresholdG), 1_500, ax = light))
        assertEquals(0, feed(ShakeDetector(ShakeDetector.Sensitivity.MEDIUM.thresholdG), 1_500, ax = light))
        assertEquals(1, feed(ShakeDetector(ShakeDetector.Sensitivity.HIGH.thresholdG), 1_500, ax = light))
        assertTrue(ShakeDetector.Sensitivity.LOW.thresholdG > ShakeDetector.Sensitivity.HIGH.thresholdG)
    }

    @Test fun `slow jolts spread over time do not add up`() {
        val d = ShakeDetector()
        var t = 0L
        repeat(6) {
            assertFalse(d.onReading(t, 3.0f * g, 0f, g))
            t += 1_300   // more than the 1.2 s window between jolts
        }
    }
}
