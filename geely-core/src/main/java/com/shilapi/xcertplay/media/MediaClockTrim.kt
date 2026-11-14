package com.shilapi.xcertplay.media

import kotlin.math.exp
import kotlin.math.roundToInt

/**
 * Keeps the head unit's audio clock in step with the iPhone's during music (#610).
 *
 * The iPhone sends music at its own 48 kHz and the head unit plays it at its own. A Humax BHCN-T3103C played
 * 0.76 % fast, so the 300 ms start buffer ran dry about every 70 s and music paused while it refilled. After
 * each write the renderer reports how many frames are queued; a slow average of that level drives a PI
 * controller that, once a second, trims the track's playback rate so the level stays at the start threshold.
 * The trim is bounded by [MAX_TRIM] (26 cents at most) and settles at the head unit's own drift, which is a
 * hardware constant, so the next music stream starts from the trim this one learned.
 */
internal class MediaClockTrim(
    private val sampleRate: Int,
    private val targetFrames: Long,
    private var integral: Double = learnedTrim,
) {
    private var level: Double? = null
    private var lastSampleNs = 0L
    private var lastUpdateNs: Long? = null
    private var compressedBacklog = false
    private var lowPcm = false

    /** Count protected updates, not audio packets, for bounded diagnostics. */
    var protectedUpdates = 0L
        private set

    /** The playback rate last asked of the track, in Hz. */
    var rate = sampleRate
        private set

    /** Include compressed packets waiting for decode: those frames are already queued audio. */
    fun sample(queuedFrames: Long, nowNs: Long, compressedFrames: Long = 0L) {
        if (targetFrames <= 0L) return
        // Decoder backpressure is not evidence that the head unit's clock runs at a different
        // rate; do not teach the *next* music stream an artificial clock correction.
        compressedBacklog = compressedFrames > targetFrames
        lowPcm = queuedFrames.coerceAtLeast(0L) < targetFrames / 2
        val buffered = queuedFrames.coerceAtLeast(0L) +
            compressedFrames.coerceIn(0L, sampleRate.toLong() * 16L)
        val error = (buffered - targetFrames).toDouble() / targetFrames
        val previous = level
        // After a pause, a rebuffer or the first write the average restarts from what is queued now.
        level = if (previous == null || nowNs - lastSampleNs > STALE_NS) error
        else previous + (error - previous) * (1.0 - exp(-(nowNs - lastSampleNs).toDouble() / AVERAGE_NS))
        lastSampleNs = nowNs
    }

    /** Between writes, brake only; an empty network interval must not teach clock drift. */
    fun protectPcm(queuedFrames: Long): Int? {
        if (targetFrames <= 0L || queuedFrames >= targetFrames / 2 || rate <= sampleRate) return null
        protectedUpdates++
        rate = sampleRate
        return rate
    }

    /** The playback rate to set now, or null to keep the current one. */
    fun rateDue(nowNs: Long): Int? {
        val averagedError = level ?: return null
        // A compressed burst is not playable PCM. Do not drain a depleted hardware
        // queue faster while its decoder is catching up or the smoothed level is stale.
        val error = if (lowPcm) minOf(averagedError, 0.0) else averagedError
        // Without new audio the level says nothing about the clocks: keep the trim as it is.
        if (nowNs - lastSampleNs > STALE_NS) return null
        val previousUpdate = lastUpdateNs
        // Safety braking must not wait a full second while a nearly empty track runs fast.
        val braking = lowPcm && rate > sampleRate
        if (!braking && previousUpdate != null && nowNs - previousUpdate < UPDATE_NS) return null
        // A pause between updates is not drift: integrate at most one normal step over it.
        val seconds = if (previousUpdate == null) 0.0 else ((nowNs - previousUpdate) / 1e9).coerceAtMost(MAX_STEP_SECONDS)
        lastUpdateNs = nowNs
        if (!compressedBacklog && !braking) {
            integral = (integral + KI * error * seconds).coerceIn(-MAX_TRIM, MAX_TRIM)
            learnedTrim = integral
        }
        val correction = (KP * error + integral).coerceIn(-MAX_TRIM, MAX_TRIM)
        val safeCorrection = if (lowPcm) minOf(correction, 0.0) else correction
        if (lowPcm && KP * averagedError + integral > 0.0) protectedUpdates++
        val next = (sampleRate * (1.0 + safeCorrection)).roundToInt()
        if (next == rate) return null
        rate = next
        return next
    }

    internal companion object {
        /** At most 1.5 % slower or faster: 26 cents, above the 0.76 % seen on #610. */
        const val MAX_TRIM = 0.015
        const val KP = 0.03
        /** Per second, per unit of level error. */
        const val KI = 0.001
        const val AVERAGE_NS = 5_000_000_000.0
        const val UPDATE_NS = 1_000_000_000L
        const val STALE_NS = 250_000_000L
        const val MAX_STEP_SECONDS = 2.0

        /** The drift the last music stream settled on. Each process plays to one head unit. */
        @Volatile var learnedTrim = 0.0
    }
}
