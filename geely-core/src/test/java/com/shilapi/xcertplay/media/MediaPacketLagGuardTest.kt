package com.shilapi.xcertplay.media

import java.util.concurrent.LinkedBlockingQueue
import org.junit.Assert.*
import org.junit.Test

class MediaPacketLagGuardTest {
    @Test fun recoveredBacklogDoesNotKeepDiscardingCatchupPackets() {
        val q = LinkedBlockingQueue<Int>()
        val g = MediaPacketLagGuard(48000)
        repeat(150) { q.add(it * 1024) }
        assertEquals(0, g.recover(q, 149 * 1024, { it }, 1_000_000_000L))
        assertTrue(g.recover(q, 149 * 1024, { it }, 2_600_000_000L) > 0)
        q.add(150 * 1024)
        assertEquals(0, g.recover(q, 150 * 1024, { it }, 2_601_000_000L))
        // New sustained overload still gets bounded after its own grace period.
        repeat(120) { q.add((151 + it) * 1024) }
        assertEquals(0, g.recover(q, 270 * 1024, { it }, 3_000_000_000L))
        assertTrue(g.recover(q, 270 * 1024, { it }, 4_600_000_000L) > 0)
    }
}
