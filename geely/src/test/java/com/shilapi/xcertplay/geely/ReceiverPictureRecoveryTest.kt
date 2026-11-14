package com.shilapi.xcertplay.geely

import android.os.Handler
import android.os.Looper
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], manifest = Config.NONE)
class ReceiverPictureRecoveryTest {
    private val handler = Handler(Looper.getMainLooper())
    private var visible = true
    private var frames = 100L
    private var requests = 0
    private val recovery = ReceiverPictureRecovery(handler, { visible }, { frames }, { requests++ }, { _, _ -> })
    private fun advance(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    @Test fun stillScreenGetsImmediatePictureAndOnlyTwoBoundedRetries() {
        recovery.start(Any(), "return")
        assertEquals(1, requests)
        advance(999); assertEquals(1, requests)
        advance(1); assertEquals(2, requests)
        advance(1_000); assertEquals(3, requests)
        advance(60_000); assertEquals(3, requests)
        recovery.stop()
    }

    @Test fun aNewRenderedFrameCancelsTheRemainingRetries() {
        recovery.start(Any(), "return")
        frames++
        advance(2_500)
        assertEquals(1, requests)
        recovery.stop()
    }

    @Test fun decoderCounterResetToZeroIsNotMistakenForAVisibleFrame() {
        recovery.start(Any(), "return")
        frames = 0
        advance(1_000); assertEquals(2, requests)
        frames = 1
        advance(1_000); assertEquals(2, requests)
        recovery.stop()
    }

    @Test fun hiddenOrDisconnectedPlaybackDoesNotRequestPictures() {
        visible = false
        recovery.start(Any(), "hidden")
        assertEquals(0, requests)
        visible = true
        recovery.start(Any(), "return")
        visible = false
        advance(3_000)
        assertEquals(1, requests)
        visible = true
        advance(3_000)
        assertEquals(1, requests)
    }

    @Test fun surfaceAndResumeCallbacksCoalesceAndOldSurfaceRetriesCannotLeak() {
        val first = Any()
        recovery.start(first, "surface_created")
        recovery.start(first, "resume")
        assertEquals(1, requests)
        advance(500)
        recovery.start(Any(), "new_surface")
        assertEquals(2, requests)
        advance(500); assertEquals(2, requests)
        advance(500); assertEquals(3, requests)
        recovery.stop()
        advance(3_000); assertEquals(3, requests)
        recovery.start(first, "return_again")
        assertEquals(4, requests)
        recovery.stop()
    }
}
