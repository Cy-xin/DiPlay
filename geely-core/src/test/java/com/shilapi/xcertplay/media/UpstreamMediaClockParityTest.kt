package com.shilapi.xcertplay.media

import org.junit.Assert.assertEquals
import org.junit.Test

/** Compare every output against the pinned original, including gaps and decoder backpressure. */
class UpstreamMediaClockParityTest {
    @Test fun burstCalibrationAndClockDriftMatchOriginal() = compare(measuresTarget = true)
    @Test fun lowPcmAndCompressedBacklogMatchOriginal() = compare(measuresTarget = false)

    private fun compare(measuresTarget: Boolean) {
        for (initialDrift in listOf(-.0076, 0.0, .01)) {
            MediaClockTrim.learnedTrim = initialDrift
            UpstreamMediaClockTrimReference.learnedTrim = initialDrift
            val actual = MediaClockTrim(48_000, 14_400, measuresTarget = measuresTarget)
            val expected = UpstreamMediaClockTrimReference(48_000, 14_400, measuresTarget = measuresTarget)
            try {
                var now = 1_000_000_000L
                repeat(1200) { step ->
                    now += if (step % 71 == 0) 3_200_000_000L else 100_000_000L
                    val pcm = when (step % 97) { in 0..6 -> 1700L; in 7..12 -> 0L; else -> 38400L }
                    val compressed = if (step % 97 < 30) 48000L else 0L
                    val moving = step > 10
                    val arrivals = if (step % 4 == 0) 2400.0 * 100_000_000L else 0.0
                    if (step % 251 == 0) { actual.measureAgain(); expected.measureAgain() }
                    actual.sample(pcm, now, compressed, moving, arrivals)
                    expected.sample(pcm, now, compressed, moving, arrivals)
                    assertEquals("step=$step drift=$initialDrift", expected.rateDue(now), actual.rateDue(now))
                    assertEquals(expected.rate, actual.rate)
                    assertEquals(UpstreamMediaClockTrimReference.learnedTrim, MediaClockTrim.learnedTrim, 0.0)
                }
            } finally {
                MediaClockTrim.learnedTrim = 0.0
                UpstreamMediaClockTrimReference.learnedTrim = 0.0
            }
        }
    }
}
