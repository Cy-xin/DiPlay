package com.shilapi.xcertplay.geely

import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicBoolean

/** Cancellation prevents new preparation work; completion means the worker really left its action. */
internal class ReceiverPreparation {
    private val cancelled=AtomicBoolean()
    private val complete=CompletableFuture<Unit>()
    val isCancelled get()=cancelled.get()
    fun cancel() {cancelled.set(true)}
    fun run(action:()->Unit) {try {if(!isCancelled) action()} finally {complete.complete(Unit)}}
    fun whenFinished(action:()->Unit) {complete.thenRun(action)}
}
