package com.shilapi.xcertplay.geely

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ReceiverLifecycleWorkTest {
    @Test fun sameFrameRequestsCoalesceAndKeepTheLatestValidSurfaceAndRestoreReason() {
        val queue=mutableListOf<Runnable>();var generation=1;var surface:Any=Any();val calls=mutableListOf<String?>()
        val work=ReceiverSurfaceWork({queue+=it},{queue.remove(it)},{g,s->g==generation && s===surface},{calls+=it})
        work.request(1,surface,"return");repeat(10) {work.request(1,surface)}
        assertEquals(1,queue.size);queue.removeAt(0).run();assertEquals(listOf("return"),calls)
        work.request(1,surface,"obsolete");generation=2;surface=Any();work.request(2,surface)
        queue.removeAt(0).run();assertEquals(listOf("return",null),calls)
        work.request(2,surface,"rotation");surface=Any();queue.removeAt(0).run();assertEquals(2,calls.size)
        work.request(2,surface,"destroy");work.cancel();assertTrue(queue.isEmpty())
    }
    @Test fun cancelledQueuedPreparationSkipsWorkAndStillSignalsActualCompletion() {
        val preparation=ReceiverPreparation();var calls=0;var finished=0
        preparation.cancel();preparation.whenFinished {finished++};assertEquals(0,finished)
        preparation.run {calls++};assertEquals(0,calls);assertEquals(1,finished)
    }
    @Test fun cancellingRunningPreparationDoesNotSignalCompletionBeforeItActuallyLeaves() {
        val preparation=ReceiverPreparation();val entered=CountDownLatch(1);val leave=CountDownLatch(1);val finished=CountDownLatch(1)
        val worker=Thread {preparation.run {entered.countDown();leave.await()}}
        try {
            worker.start();assertTrue(entered.await(3,TimeUnit.SECONDS));preparation.cancel();preparation.whenFinished {finished.countDown()}
            assertFalse(finished.await(20,TimeUnit.MILLISECONDS));leave.countDown()
            assertTrue(finished.await(3,TimeUnit.SECONDS))
        } finally {leave.countDown();worker.join(3000)}
    }
}
