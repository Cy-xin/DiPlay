package com.shilapi.xcertplay.media

import java.io.Closeable
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Sink-wide sequence survives decoder replacement. A single observer waits for a matching surface. */
internal class PictureFrameSignal:Closeable {
    private data class Frame(val surface:Any,val sequence:Long)
    private class Watch(val surface:Any,val after:Long,val notify:()->Unit)
    private val serial=AtomicLong()
    private val latest=AtomicReference<Frame?>()
    private val watch=AtomicReference<Watch?>()
    @Volatile private var closed=false
    fun sequenceOn(surface:Any):Long=latest.get()?.takeIf {it.surface===surface}?.sequence ?: 0L
    fun onFrame(surface:Any) {
        if(closed) return
        val frame=Frame(surface,serial.incrementAndGet())
        latest.set(frame)
        deliver(frame)
    }
    fun observe(surface:Any,after:Long,notify:()->Unit):Closeable {
        if(closed) return Closeable {}
        val next=Watch(surface,after,notify)
        watch.set(next)
        if(closed) watch.compareAndSet(next,null)
        // Covers a frame arriving between the caller's snapshot and observer registration.
        latest.get()?.let(::deliver)
        return Closeable {watch.compareAndSet(next,null)}
    }
    private fun deliver(frame:Frame) {
        val current=watch.get() ?: return
        if(!closed && current.surface===frame.surface && frame.sequence>current.after && watch.compareAndSet(current,null)) {
            runCatching(current.notify)
        }
    }
    fun detach(surface:Any) {
        latest.get()?.takeIf {it.surface===surface}?.let {latest.compareAndSet(it,null)}
        watch.get()?.takeIf {it.surface===surface}?.let {watch.compareAndSet(it,null)}
    }
    override fun close() {closed=true;watch.set(null);latest.set(null)}
}
