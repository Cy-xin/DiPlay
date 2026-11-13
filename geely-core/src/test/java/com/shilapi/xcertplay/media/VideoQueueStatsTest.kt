package com.shilapi.xcertplay.media

import org.junit.Assert.*
import org.junit.Test

class VideoQueueStatsTest {
    @Test fun inputQueuePeaksAreBoundedPerReportingWindow() {
        var now=1L
        val stats=VideoStats(nanoTime={now})
        stats.onReceived(100);stats.onQueuedFrame(20_000_000,2)
        stats.onQueuedFrame(70_000_000,7);stats.onQueuedFrame(-1,-1)
        assertNull(stats.logIfDue());now=6_000_000_001L
        val report=stats.logIfDue()!!
        assertTrue(report.contains("queueMax=70ms queuePeak=7"))
        stats.onReceived(100);now=12_000_000_001L
        assertTrue(stats.logIfDue()!!.contains("queueMax=0ms queuePeak=0"))
    }
}
