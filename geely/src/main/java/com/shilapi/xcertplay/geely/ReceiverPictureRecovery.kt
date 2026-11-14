package com.shilapi.xcertplay.geely

import android.os.Handler

/** Reuse the upstream immediate/1s/2s picture requests; never reconnect or poll in background. */
internal class ReceiverPictureRecovery(
    private val handler: Handler,
    private val eligible: () -> Boolean,
    private val rendered: () -> Long,
    private val refresh: () -> Unit,
    private val report: (String, String) -> Unit,
) {
    private var owner: Any? = null
    private val pending = mutableListOf<Runnable>()

    fun start(surface: Any?, reason: String) {
        if (surface == null || !eligible() || owner === surface) return
        stop()
        owner = surface
        val baseline = rendered()
        request(reason, 0)
        for (attempt in 1..2) {
            val retry = Runnable {
                if (owner !== surface) return@Runnable
                if (!eligible()) { stop(); return@Runnable }
                val current = rendered()
                // A replacement decoder can restart its counter at zero.
                if (current > 0L && current != baseline) {
                    report("picture_restored", "checkMs=${attempt * 1000} rendered=$current baseline=$baseline")
                    clearPending()
                    return@Runnable
                }
                request(reason, attempt)
            }
            pending += retry
            handler.postDelayed(retry, attempt * 1_000L)
        }
    }

    private fun request(reason: String, attempt: Int) {
        refresh()
        report("picture_refresh_requested", "reason=$reason attempt=$attempt")
    }

    private fun clearPending() {
        pending.forEach(handler::removeCallbacks)
        pending.clear()
    }

    fun stop() {
        owner = null
        clearPending()
    }
}
