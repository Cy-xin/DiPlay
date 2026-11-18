package com.shilapi.xcertplay.geely

/** One foreground-open attempt, consumed before checking prerequisites or invoking connect(). */
internal class ReceiverAutoConnect {
    private var pending=false
    fun open(enabled:Boolean) {pending=enabled}
    fun suppress() {pending=false}
    fun take(enabled:Boolean,foreground:Boolean,displayReady:Boolean):Boolean {
        if(!enabled) pending=false
        if(!pending || !foreground || !displayReady) return false
        pending=false
        return true
    }
}
