package com.shilapi.xcertplay.orchestration

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class TeardownCompletionTest {
    @Test fun timeoutDoesNotLoseLateReleaseAndDuplicateCompletionDoesNotRepeatIt() {
        val done=TeardownCompletion();val released=AtomicInteger()
        assertFalse(done.await(0,TimeUnit.MILLISECONDS))
        done.onComplete {released.incrementAndGet()}
        assertEquals(0,released.get());done.countDown();done.countDown()
        assertEquals(1,released.get());assertTrue(done.await(0,TimeUnit.MILLISECONDS))
        done.onComplete {released.incrementAndGet()};assertEquals(2,released.get())
    }
    @Test fun registrationRacingCompletionStillRunsExactlyOnce() {
        repeat(100) {
            val done=TeardownCompletion();val calls=AtomicInteger()
            val thread=Thread {done.countDown()};thread.start()
            done.onComplete {calls.incrementAndGet()};thread.join()
            assertEquals(1,calls.get())
        }
    }
}
