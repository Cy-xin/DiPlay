package com.shilapi.xcertplay.media

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[30],manifest=Config.NONE)
class ReceiverVideoFreshnessTest {
    @Test fun idleSourceStillProducesStatsAndLongGapIsPreservedWithoutCallingItStall() {
        var now=1_000_000L
        val stats=VideoStats(nanoTime={now})
        stats.onReceived(10);stats.onRendered()
        now+=5_000_000_000L
        assertTrue(stats.logIfDue()!!.contains("sinceRx=5000ms sinceShown=5000ms"))
        now+=5_000_000_000L
        assertTrue(stats.logIfDue()!!.contains("rx=0.0fps"))
        stats.onReceived(10);now+=5_000_000_000L
        val recovered=stats.logIfDue()!!
        assertTrue(recovered.contains("sourceGapMax=10000ms"))
        assertTrue(recovered.contains("sinceRx=5000ms sinceShown=15000ms"))
        assertFalse(recovered.contains("stalled"))
    }
    @Test fun receivingWithoutShowingCanBeDistinguishedFromSourceSilence() {
        var now=1_000_000L
        val stats=VideoStats(nanoTime={now});stats.onReceived(10);stats.onRendered()
        repeat(50) {now+=100_000_000L;stats.onReceived(10)}
        val line=stats.logIfDue()!!
        assertTrue(line.contains("sinceRx=0ms sinceShown=5000ms"))
        assertTrue(line.contains("sourceGapMax=100ms"))
    }
}
