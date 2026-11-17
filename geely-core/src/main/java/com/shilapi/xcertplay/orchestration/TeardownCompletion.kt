package com.shilapi.xcertplay.orchestration

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** A timeout stops waiting, not teardown. Late completion must still release its owner. */
internal class TeardownCompletion {
    private val latch=CountDownLatch(1)
    private val callbacks=mutableListOf<()->Unit>()
    fun await(timeout:Long,unit:TimeUnit)=latch.await(timeout,unit)
    fun onComplete(callback:()->Unit) {
        val ready=synchronized(this) {
            if(latch.count==0L) true else {callbacks+=callback;false}
        }
        if(ready) callback()
    }
    fun countDown() {
        val ready=synchronized(this) {
            if(latch.count==0L) return
            latch.countDown()
            callbacks.toList().also {callbacks.clear()}
        }
        ready.forEach {runCatching(it)}
    }
}
