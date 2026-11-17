package com.shilapi.xcertplay.geely

import android.os.Handler
import java.io.Closeable

/** Three requests, then one bounded observation. Later frames clear feedback by event, never polling. */
internal class ReceiverPictureRecovery(
    private val handler: Handler,
    private val eligible: () -> Boolean,
    private val rendered: () -> Long,
    private val refresh: () -> Unit,
    private val report: (String, String) -> Unit,
    private val observe:(Any,Long,()->Unit)->Closeable?={_,_,_->null},
    private val onFailed:()->Unit={},
    private val onCleared:()->Unit={},
) {
    private var owner: Any? = null
    private var run=0L
    private var baseline=0L
    private var initial=0L
    private var failed=false
    private var completed=false
    private var observer:Closeable?=null
    private val pending = mutableListOf<Runnable>()

    fun start(surface: Any?, reason: String) {
        if (surface == null || !eligible() || owner === surface) return
        stop()
        owner = surface
        val token=run
        baseline=rendered();initial=baseline
        observer=observe(surface,baseline) {
            handler.post {if(run==token && owner===surface) {if(eligible()) restored(reason,"frame_event") else stop()}}
        }
        request(reason, 0)
        for (attempt in 1..3) {
            val check=Runnable {
                if(run!=token || owner!==surface) return@Runnable
                if(!eligible()) {stop();return@Runnable}
                val current=rendered()
                // Legacy counters may reset. A decrease alone is not a new-frame proof.
                if(current>=0 && current<baseline) baseline=current
                if(current>0 && current>baseline) {restored(reason,"checkMs=${attempt*1000}");return@Runnable}
                if(attempt<3) request(reason,attempt)
                else if(!failed) {
                    failed=true
                    report("picture_restore_failed","reason=$reason initial=$initial baseline=$baseline rendered=$current checkMs=3000")
                    onFailed()
                }
            }
            pending+=check
            handler.postDelayed(check,attempt*1000L)
        }
    }
    fun retry(surface:Any?,reason:String) {stop();start(surface,reason)}
    private fun restored(reason:String,proof:String) {
        if(completed) return
        completed=true
        report("picture_restored","reason=$reason $proof rendered=${rendered()} baseline=$baseline")
        clearPending();observer?.close();observer=null;failed=false;onCleared()
    }
    private fun request(reason: String, attempt: Int) {
        refresh()
        report("picture_refresh_requested", "reason=$reason attempt=$attempt")
    }
    private fun clearPending() {pending.forEach(handler::removeCallbacks);pending.clear()}
    fun stop() {
        ++run;owner=null;failed=false;completed=false
        clearPending();observer?.close();observer=null;onCleared()
    }
}
