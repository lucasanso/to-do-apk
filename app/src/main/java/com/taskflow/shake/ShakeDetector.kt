package com.taskflow.shake

import kotlin.math.sqrt

/**
 * Recognizes a deliberate shake from accelerometer readings (m/s², gravity included).
 *
 * A shake = [peaks] strong jolts (acceleration above [thresholdG] times gravity) within [windowMs].
 * Jolts closer than [debounceMs] count as one. After a shake nothing fires for [cooldownMs].
 * Walking, putting the phone on a table or a single bump do not reach this pattern.
 * Pure Kotlin so the thresholds are unit-tested.
 */
class ShakeDetector(
    private val thresholdG: Float = Sensitivity.MEDIUM.thresholdG,
    private val peaks: Int = 3,
    private val windowMs: Long = 1_200,
    private val debounceMs: Long = 90,
    private val cooldownMs: Long = 2_500,
) {
    enum class Sensitivity(val label: String, val thresholdG: Float) {
        LOW("Baixa (balanço forte)", 2.9f),
        MEDIUM("Média", 2.4f),
        HIGH("Alta (balanço leve)", 2.0f);

        companion object {
            fun of(level: Int) = entries.getOrElse(level) { MEDIUM }
        }
    }

    private val jolts = ArrayDeque<Long>()
    private var lastJolt = Long.MIN_VALUE / 2
    private var lastShake = Long.MIN_VALUE / 2

    /** Feeds one reading; returns true exactly when a shake is recognized. */
    fun onReading(timeMs: Long, x: Float, y: Float, z: Float): Boolean {
        if (timeMs - lastShake < cooldownMs) return false
        val g = sqrt(x * x + y * y + z * z) / GRAVITY
        if (g < thresholdG) return false
        if (timeMs - lastJolt < debounceMs) return false
        lastJolt = timeMs
        jolts.addLast(timeMs)
        while (jolts.isNotEmpty() && timeMs - jolts.first() > windowMs) jolts.removeFirst()
        if (jolts.size >= peaks) {
            jolts.clear()
            lastShake = timeMs
            return true
        }
        return false
    }

    fun reset() { jolts.clear() }

    companion object { const val GRAVITY = 9.80665f }
}
