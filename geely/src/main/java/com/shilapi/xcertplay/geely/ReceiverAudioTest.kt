package com.shilapi.xcertplay.geely

import android.content.Context
import android.media.*
import android.os.SystemClock
import com.shilapi.xcertplay.media.AudioOutputDevice
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.*

internal data class AudioTestPlan(val role:ReceiverAudioRole,val usage:Int,val preferred:AudioOutputDevice?)
internal data class AudioTestRoute(val requested:String?,val accepted:Boolean?,val actualBus:String?,val actualType:Int?) {
    val mismatch get()=requested!=null && actualType!=null && requested!=actualBus
    fun describe():String {
        fun label(bus:String)=ReceiverAudioRole.entries.firstOrNull {it.bus==bus}?.title ?: bus
        val actual=actualBus?.let(::label) ?: actualType?.let {"系统默认输出"} ?: "等待系统上报"
        return "请求：${requested?.let(::label) ?: "跟随系统"}；实际：$actual"+
            if(mismatch) "（与所选通道不一致）" else if(accepted==false) "（已回退系统路由）" else ""
    }
}
internal interface AudioTestOutput:Closeable {
    fun start(samples:ShortArray)
    fun route():AudioTestRoute
}

/** Exactly five seconds of low-level PCM, with five gently faded pulses and no system volume writes. */
internal object AudioTestSignal {
    const val RATE=48000
    const val SECONDS=5
    const val PEAK=2621 // about -22 dBFS, independent of the user's system volume
    fun samples(role:ReceiverAudioRole):ShortArray {
        val frequency=when(role) {ReceiverAudioRole.MEDIA->440;ReceiverAudioRole.NAVIGATION->554;ReceiverAudioRole.PHONE->660;ReceiverAudioRole.ASSISTANT->784;ReceiverAudioRole.RINGTONE->880}
        val sounding=RATE*3/5;val fade=RATE/50
        return ShortArray(RATE*SECONDS) {index ->
            val position=index%RATE
            if(position>=sounding) 0 else {
                val envelope=minOf(1.0,position.toDouble()/fade,(sounding-position).toDouble()/fade)
                (sin(2*PI*frequency*index/RATE)*PEAK*envelope).roundToInt().toShort()
            }
        }
    }
}

/** A single finite worker, created only on explicit playback; cancellation never waits on the UI thread. */
internal class ReceiverAudioTest(
    private val outputFactory:(AudioTestPlan,()->Unit)->AudioTestOutput,
    private val clock:()->Long=SystemClock::elapsedRealtime,
    private val pause:(Long)->Unit={Thread.sleep(it)},
) {
    enum class Phase { PREPARING,PLAYING,FINISHED,STOPPED,FAILED }
    data class Update(val role:ReceiverAudioRole,val phase:Phase,val remaining:Int=0,val route:AudioTestRoute?=null,val error:String?=null)
    private class Run(val plan:AudioTestPlan,val notify:(Update)->Unit) {
        val cancelled=AtomicBoolean(false)
        @Volatile var cancelReason:String?=null
        lateinit var thread:Thread
        fun emit(update:Update) {runCatching {notify(update)}}
    }
    private var current:Run?=null
    private var lastThread:Thread?=null
    @Synchronized fun start(plan:AudioTestPlan,notify:(Update)->Unit):Boolean {
        if(current!=null) return false
        val run=Run(plan,notify);current=run
        run.thread=Thread({play(run)},"DiPlay-AudioTest").apply {isDaemon=true}
        lastThread=run.thread;run.thread.start();return true
    }
    @Synchronized fun stop() {current?.let {it.cancelReason="user_or_page";it.cancelled.set(true);it.thread.interrupt()}}
    fun awaitStopped(timeoutMillis:Long):Boolean {
        val thread=synchronized(this) {lastThread} ?: return true
        thread.join(timeoutMillis.coerceAtLeast(1));return !thread.isAlive
    }
    private fun play(run:Run) {
        var output:AudioTestOutput?=null
        var phase=Phase.FINISHED
        var error:String?=null
        var lastRoute:AudioTestRoute?=null
        try {
            run.emit(Update(run.plan.role,Phase.PREPARING,AudioTestSignal.SECONDS))
            val samples=AudioTestSignal.samples(run.plan.role)
            if(!run.cancelled.get()) {
                output=outputFactory(run.plan) {run.cancelReason="focus_lost";run.cancelled.set(true);run.thread.interrupt()}
                if(!run.cancelled.get()) output.start(samples)
                val deadline=clock()+AudioTestSignal.SECONDS*1000L
                var lastRemaining=-1
                while(!run.cancelled.get() && clock()<deadline) {
                    val remaining=((deadline-clock()+999)/1000).toInt().coerceIn(1,AudioTestSignal.SECONDS)
                    if(remaining!=lastRemaining) {
                        lastRemaining=remaining;lastRoute=output.route()
                        run.emit(Update(run.plan.role,Phase.PLAYING,remaining,lastRoute))
                    }
                    pause(40)
                }
            }
            if(run.cancelled.get()) phase=Phase.STOPPED
        } catch(_:InterruptedException) {run.cancelled.set(true);phase=Phase.STOPPED}
        catch(failure:Exception) {phase=Phase.FAILED;error=failure.javaClass.simpleName+": "+failure.message.orEmpty().take(160)}
        finally {
            if(phase==Phase.STOPPED) error=run.cancelReason
            runCatching {output?.route()}.getOrNull()?.let {lastRoute=it}
            runCatching {output?.close()}.onFailure {phase=Phase.FAILED;error=it.javaClass.simpleName}
            synchronized(this) {if(current===run) current=null}
            run.emit(Update(run.plan.role,phase,route=lastRoute,error=error))
        }
    }
}

/** Same usage, content type and preferred-device APIs as the original receiver; no telephony/mic operation. */
internal class AndroidAudioTestOutput(context:Context,private val plan:AudioTestPlan,private val lostFocus:()->Unit):AudioTestOutput {
    private val manager=context.getSystemService(AudioManager::class.java)
    private var track:AudioTrack?=null
    private var focus:AudioFocusRequest?=null
    private var accepted:Boolean?=null
    override fun start(samples:ShortArray) {
        val attributes=AudioAttributes.Builder().setUsage(plan.usage)
            .setContentType(if(plan.role==ReceiverAudioRole.MEDIA) AudioAttributes.CONTENT_TYPE_MUSIC else AudioAttributes.CONTENT_TYPE_SPEECH).build()
        val request=AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attributes).setWillPauseWhenDucked(true)
            .setOnAudioFocusChangeListener({change ->if(change<0) lostFocus()},android.os.Handler(android.os.Looper.getMainLooper())).build()
        focus=request
        check(manager?.requestAudioFocus(request)==AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {"Audio focus unavailable"}
        val output=AudioTrack.Builder().setAudioAttributes(attributes)
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(AudioTestSignal.RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setTransferMode(AudioTrack.MODE_STATIC).setBufferSizeInBytes(samples.size*2).build()
        track=output
        check(output.state!=AudioTrack.STATE_UNINITIALIZED) {"Audio output unavailable"}
        plan.preferred?.let {preferred ->
            accepted=preferred.resolve(manager)?.let {runCatching {output.setPreferredDevice(it)}.getOrDefault(false)} ?: false
            if(accepted==false) output.setPreferredDevice(null)
        }
        check(output.write(samples,0,samples.size,AudioTrack.WRITE_BLOCKING)==samples.size) {"Incomplete test signal"}
        output.play()
    }
    override fun route():AudioTestRoute {
        val device=track?.routedDevice
        val bus=device?.takeIf {it.type==AudioDeviceInfo.TYPE_BUS}?.address?.takeIf {it in ReceiverAudioRole.entries.map {role->role.bus}}
        return AudioTestRoute(plan.preferred?.address,accepted,bus,device?.type)
    }
    override fun close() {
        try {track?.let {runCatching {it.stop()};it.release()}}
        finally {track=null;focus?.let {manager?.abandonAudioFocusRequest(it)};focus=null}
    }
}
