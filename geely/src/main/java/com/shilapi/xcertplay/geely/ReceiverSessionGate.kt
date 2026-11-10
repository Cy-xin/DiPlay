package com.shilapi.xcertplay.geely

/** One explicitly started attempt per process, including while an old Activity is closing. */
internal object ReceiverSessionGate {
    private var owner: Any? = null
    @Synchronized fun acquire(token: Any): Boolean {
        if (owner != null) return false
        owner = token
        return true
    }
    @Synchronized fun release(token: Any?) {
        if (token != null && owner === token) owner = null
    }
}
