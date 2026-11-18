package com.shilapi.xcertplay.geely

import android.content.ComponentName
import android.content.ServiceConnection
import android.os.Looper
import com.geely.lib.oneosapi.input.IInputListener
import com.geely.lib.oneosapi.input.IInputManager
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[30])
class ReceiverWheelOwnershipTest {
    private class Input: IInputManager.Stub() {
        val operations=mutableListOf<String>()
        var accept=true
        lateinit var listener:IInputListener
        override fun interceptKeyCode(key:Int,name:String?):Boolean {operations+="acquire:$key";return accept}
        override fun releaseKeyCode(key:Int,name:String?):Boolean {operations+="release:$key";return accept}
        override fun registerListener(value:IInputListener,name:String?,codes:IntArray?) {listener=value}
        override fun unregisterListener(value:IInputListener?,name:String?) {operations+="unregister"}
        override fun getControlIndex()=0
    }
    private fun bind(keys:ReceiverWheelKeys,input:Input) {
        val connection=ReceiverWheelKeys::class.java.getDeclaredField("connection").apply {isAccessible=true}.get(keys) as ServiceConnection
        connection.onServiceConnected(ComponentName("com.geely.inputservice","com.geely.inputservice.InputService"),input)
    }
    private fun press(input:Input) {input.listener.onShortClick(200085,0);Shadows.shadowOf(Looper.getMainLooper()).idle()}
    @Test fun exclusiveOnlyWhileCarPlayIsVisibleAndReleaseBeforeUnregister() {
        val input=Input();val sent=mutableListOf<Int>();var mappings=ReceiverWheelMapping.defaults
        val keys=ReceiverWheelKeys(RuntimeEnvironment.getApplication(),{a,_->sent+=a},{},mappings={mappings})
        bind(keys,input);press(input);assertTrue(sent.isEmpty());assertTrue(input.operations.isEmpty())
        keys.setForegroundCarPlay(true);keys.setForegroundCarPlay(true);press(input)
        assertEquals(listOf(3),sent);assertEquals(listOf("acquire:200085"),input.operations)
        keys.setForegroundCarPlay(false);press(input);assertEquals(listOf(3),sent)
        keys.setForegroundCarPlay(true);mappings=mappings+(200085 to 0);keys.setForegroundCarPlay(true);press(input)
        assertEquals(listOf(3),sent)
        mappings=ReceiverWheelMapping.defaults;keys.setForegroundCarPlay(true)
        input.listener.onShortClick(200085,0);keys.close();Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf(3),sent)
        assertEquals(listOf("release:200085","unregister"),input.operations.takeLast(2))
        assertTrue(input.operations.all {it=="unregister" || it.endsWith(":200085")})
    }
    @Test fun deniedOrThrowingTakeoverNeverForwardsDuplicatePlayPause() {
        val input=Input().apply {accept=false};val sent=mutableListOf<Int>();val logs=mutableListOf<String>()
        val keys=ReceiverWheelKeys(RuntimeEnvironment.getApplication(),{a,_->sent+=a},{logs+=it})
        keys.setForegroundCarPlay(true);bind(keys,input);press(input)
        assertTrue(sent.isEmpty());assertTrue(logs.contains("play_pause_takeover=true accepted=false owned=false"))
        input.listener.onShortClick(200087,0);Shadows.shadowOf(Looper.getMainLooper()).idle();assertEquals(listOf(4),sent)
        keys.setForegroundCarPlay(false);keys.close()
        assertFalse(input.operations.any {it.startsWith("release")})
    }
    @Test fun activityReleasesOnHomePauseDetectionAndDisconnect() {
        val lifecycle=Robolectric.buildActivity(GeelyActivity::class.java).setup();val activity=lifecycle.get()
        fun set(name:String,value:Any) {GeelyActivity::class.java.getDeclaredField(name).apply {isAccessible=true}.set(activity,value)}
        val input=Input();val keys=ReceiverWheelKeys(activity.applicationContext,{_,_->},{})
        try {
            set("wheelKeys",keys);set("connected",true);activity.ui.showCarPlay=true;bind(keys,input)
            activity.updateWheelControlScope();assertEquals("acquire:200085",input.operations.last())
            activity.showReceiverHome();assertEquals("release:200085",input.operations.last())
            activity.ui.showCarPlay=true;activity.updateWheelControlScope()
            lifecycle.pause();assertEquals("release:200085",input.operations.last())
            lifecycle.resume();assertEquals("acquire:200085",input.operations.last())
            activity.ui.wheelDetecting=true;activity.updateWheelControlScope();assertEquals("release:200085",input.operations.last())
            activity.ui.wheelDetecting=false;activity.updateWheelControlScope()
            activity.disconnectFromNotification();assertEquals(listOf("release:200085","unregister"),input.operations.takeLast(2))
        } finally {lifecycle.pause().stop().destroy()}
    }
}
