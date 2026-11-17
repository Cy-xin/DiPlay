package com.shilapi.xcertplay.geely

import android.content.*
import android.os.*
import com.geely.lib.oneosapi.input.IInputListener
import com.geely.lib.oneosapi.input.IInputManager
import java.io.Closeable

/** Session-scoped subscription. Never intercepts or modifies factory key handling. */
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
            if(action!=null) dispatch(action,long)
        }}
    }
    private val connection=object:ServiceConnection {
        override fun onServiceConnected(name:ComponentName,binder:IBinder) {
            if(closed) return
            manager=IInputManager.Stub.asInterface(binder)
            runCatching {manager?.registerListener(listener,context.packageName,KEYS)}
                .onSuccess {report("registered=true")}.onFailure {report("register_failed=${it.javaClass.simpleName}")}
        }
        override fun onServiceDisconnected(name:ComponentName) {manager=null;report("service_disconnected")}
    }
    fun start() {
        if(closed || bound) return
        bound=runCatching {context.bindService(Intent().setComponent(ComponentName("com.geely.inputservice","com.geely.inputservice.InputService")),connection,Context.BIND_AUTO_CREATE)}.getOrDefault(false)
        report("bind=$bound")
    }
    override fun close() {
        if(closed) return
        closed=true;main.removeCallbacksAndMessages(null)
        runCatching {manager?.unregisterListener(listener,context.packageName)}
        manager=null
        if(bound) runCatching {context.unbindService(connection)}
        bound=false
    }
    companion object {
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
