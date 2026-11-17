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
    @Test fun finalRequestHasAnObservationWindowAndFailureIsReportedOnlyOnce() {
        var failures=0;val times=mutableListOf<Long>();val reports=mutableListOf<String>()
        val started=android.os.SystemClock.uptimeMillis()
        val subject=ReceiverPictureRecovery(handler,{true},{0},{times+=android.os.SystemClock.uptimeMillis()-started},{event,_->reports+=event},onFailed={failures++})
        subject.start(Any(),"return");advance(2000)
        assertEquals(listOf(0L,1000L,2000L),times);assertEquals(0,failures)
        advance(999);assertEquals(0,failures);advance(1);assertEquals(1,failures)
        advance(60000);assertEquals(1,failures);assertEquals(3,times.size)
        assertEquals(1,reports.count {it=="picture_restore_failed"});subject.stop()
    }
    @Test fun frameAfterThirdRequestPreventsFailureAndLateFrameClearsExistingFeedback() {
        var count=0L;var notify:(()->Unit)?=null;var hint=false
        val subject=ReceiverPictureRecovery(handler,{true},{count},{},{_,_->},
            observe={_,_,callback->notify=callback;java.io.Closeable {}},onFailed={hint=true},onCleared={hint=false})
        val surface=Any();subject.start(surface,"return");advance(2500)
        count++;notify!!();advance(0);advance(1000);assertFalse(hint)
        subject.retry(surface,"manual");advance(3000);assertTrue(hint)
        count++;notify!!();advance(0);assertFalse(hint);advance(60000);assertFalse(hint)
        subject.stop()
    }
    @Test fun positiveCounterResetRequiresAFurtherFrameAndZeroAloneDoesNotProveRecovery() {
        var count=100L;var restored=0;var failures=0
        val subject=ReceiverPictureRecovery(handler,{true},{count},{},{event,_->if(event=="picture_restored") restored++},onFailed={failures++})
        subject.start(Any(),"return");count=7;advance(1000)
        assertEquals(0,restored);count=8;advance(1000);assertEquals(1,restored);advance(1000);assertEquals(0,failures)
        subject.retry(Any(),"again");count=0;advance(3000)
        assertEquals(1,restored);assertEquals(1,failures);subject.stop()
    }
    @Test fun staleSurfaceEventsAndCancelledFinalChecksCannotModifyTheNewRun() {
        val callbacks=mutableListOf<()->Unit>();var failures=0;var cleared=0
        val subject=ReceiverPictureRecovery(handler,{visible},{0},{},{_,_->},
            observe={_,_,callback->callbacks+=callback;java.io.Closeable {}},onFailed={failures++},onCleared={cleared++})
        subject.start(Any(),"first");advance(1500);subject.start(Any(),"replacement")
        val before=cleared;callbacks.first()();advance(0);assertEquals(before,cleared)
        advance(1500);assertEquals(0,failures)
        subject.stop();callbacks.last()();advance(60000);assertEquals(0,failures)
        subject.start(Any(),"background");visible=false;advance(3000);assertEquals(0,failures)
    }

}
