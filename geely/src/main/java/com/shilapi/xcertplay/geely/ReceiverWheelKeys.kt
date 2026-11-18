package com.shilapi.xcertplay.geely

import android.content.*
import android.os.*
import com.geely.lib.oneosapi.input.IInputListener
import com.geely.lib.oneosapi.input.IInputManager
import java.io.Closeable

/** Session-scoped listener; only foreground CarPlay may temporarily own play/pause. */
internal class ReceiverWheelKeys(
    private val context:Context,
    private val dispatch:(Int,Boolean)->Unit,
    private val report:(String)->Unit,
    private val mappings:()->Map<Int,Int> = {ReceiverWheelMapping.defaults},
    private val observed:(Int,Boolean)->Unit={_,_->},
):Closeable {
    private val main=Handler(Looper.getMainLooper())
    private var bound=false
    private var closed=false
    private var manager:IInputManager?=null
    private var registered=false
    private var foregroundCarPlay=false
    private var playPauseOwned=false
    fun setForegroundCarPlay(active:Boolean) {
        foregroundCarPlay=active
        updatePlayPauseOwnership()
    }
    private fun updatePlayPauseOwnership() {
        val input=manager ?: return
        val wanted=!closed && registered && foregroundCarPlay && action(PLAY_PAUSE,false,mappings())!=null
        if(wanted==playPauseOwned) return
        val accepted=runCatching {
            if(wanted) input.interceptKeyCode(PLAY_PAUSE,context.packageName)
            else input.releaseKeyCode(PLAY_PAUSE,context.packageName)
        }.getOrDefault(false)
        if(accepted) playPauseOwned=wanted
        report("play_pause_takeover=$wanted accepted=$accepted owned=$playPauseOwned")
    }
    private val listener=object:IInputListener.Stub() {
        override fun onKeyCodeEvent(key:Int,event:Int,function:Int) {}
        override fun onHoldingPressStarted(key:Int,function:Int) {}
        override fun onHoldingPressStopped(key:Int,function:Int) {}
        override fun onDoubleClick(key:Int,function:Int) {}
        override fun onShortClick(key:Int,function:Int) = offer(key,false)
        override fun onLongPressTriggered(key:Int,function:Int) = offer(key,true)
    }
    private fun offer(key:Int,long:Boolean) {
        main.post {if(!closed) {
            observed(key,long)
            val action=action(key,long,mappings())
            report("key=$key long=$long action=${action ?: "none"}")
            // A passive callback is not proof that the factory handler was suppressed.
            // Outside CarPlay leave this physical key entirely to the head unit.
            if(key==PLAY_PAUSE && (!foregroundCarPlay || !playPauseOwned)) {
                report("play_pause_blocked foreground=$foregroundCarPlay owned=$playPauseOwned")
            } else if(action!=null) dispatch(action,long)
        }}
    }
    private val connection=object:ServiceConnection {
        override fun onServiceConnected(name:ComponentName,binder:IBinder) {
            if(closed) return
            manager=IInputManager.Stub.asInterface(binder)
            registered=false;playPauseOwned=false
            runCatching {manager?.registerListener(listener,context.packageName,KEYS)}
                .onSuccess {registered=true;report("registered=true");updatePlayPauseOwnership()}.onFailure {report("register_failed=${it.javaClass.simpleName}")}
        }
        override fun onServiceDisconnected(name:ComponentName) {manager=null;registered=false;playPauseOwned=false;report("service_disconnected")}
    }
    fun start() {
        if(closed || bound) return
        bound=runCatching {context.bindService(Intent().setComponent(ComponentName("com.geely.inputservice","com.geely.inputservice.InputService")),connection,Context.BIND_AUTO_CREATE)}.getOrDefault(false)
        report("bind=$bound")
    }
    override fun close() {
        if(closed) return
        closed=true;foregroundCarPlay=false;updatePlayPauseOwnership();main.removeCallbacksAndMessages(null)
        runCatching {manager?.unregisterListener(listener,context.packageName)}
        manager=null
        if(bound) runCatching {context.unbindService(connection)}
        bound=false
    }
    companion object {
        private const val PLAY_PAUSE=200085
        private val KEYS=intArrayOf(200085,200087,200088,200400)
        // CarPlay media HID usages; MODE is recognized only via its long callback.
        fun action(key:Int,long:Boolean,mappings:Map<Int,Int> = ReceiverWheelMapping.defaults):Int? = when {
            long && key==200400 -> -1
            long -> null
            key in ReceiverWheelMapping.defaults -> mappings[key]?.takeIf {it in ReceiverWheelMapping.choices && it!=0}
            else -> null
        }
    }
}
