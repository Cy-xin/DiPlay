package com.shilapi.xcertplay.geely

import android.content.ComponentName
import com.geely.lib.oneosapi.input.IInputManager
import android.os.Looper
import com.geely.lib.oneosapi.input.IInputListener
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBuild
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[30])
class ReceiverWheelSettingsTest {
    @Test fun savedRemapSurvivesRestartAndResetRestoresVerifiedGeelyDefaults() {
        val first=Robolectric.buildActivity(GeelyActivity::class.java).setup()
        try {first.get().saveWheelMapping(200087,5);first.get().saveWheelMapping(200088,0)}
        finally {first.pause().stop().destroy()}
        val next=Robolectric.buildActivity(GeelyActivity::class.java).setup()
        try {
            assertEquals(5,next.get().ui.wheelMappings[200087]);assertEquals(0,next.get().ui.wheelMappings[200088])
            next.get().resetWheelMappings();assertEquals(ReceiverWheelMapping.defaults,next.get().ui.wheelMappings)
            next.get().saveWheelMapping(200087,999);assertEquals(4,next.get().ui.wheelMappings[200087])
        } finally {next.pause().stop().destroy()}
    }
    @Test fun activeSubscriptionReadsUpdatedMappingsAndDisabledKeyOnlyReportsReception() {
        var mappings=ReceiverWheelMapping.defaults
        val sent=mutableListOf<Int>();val observed=mutableListOf<Int>()
        val keys=ReceiverWheelKeys(RuntimeEnvironment.getApplication(),{a,_->sent+=a},{},mappings={mappings},observed={k,_->observed+=k})
        val callback=ReceiverWheelKeys::class.java.getDeclaredField("listener").apply {isAccessible=true}.get(keys) as IInputListener
        callback.onShortClick(200087,0);Shadows.shadowOf(Looper.getMainLooper()).idle()
        mappings=mappings+(200087 to 5);callback.onShortClick(200087,0);Shadows.shadowOf(Looper.getMainLooper()).idle()
        mappings=mappings+(200087 to 0);callback.onShortClick(200087,0);Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf(4,5),sent);assertEquals(listOf(200087,200087,200087),observed)
        callback.onLongPressTriggered(200400,0);Shadows.shadowOf(Looper.getMainLooper()).idle();assertEquals(-1,sent.last())
        keys.close()
    }
    @Test fun phoneCannotPretendToDetectFactoryKeysAndUnconnectedCarDetectionExpires() {
        val lifecycle=Robolectric.buildActivity(GeelyActivity::class.java).setup();val activity=lifecycle.get()
        try {
            activity.startWheelDetection();assertFalse(activity.ui.wheelDetecting)
            assertTrue(activity.ui.wheelMessage.contains("吉利车机"))
            ShadowBuild.setModel("KX11_A2")
            var registered:IntArray?=null;var unregistered=0
            val service=object:IInputManager.Stub() {
                override fun interceptKeyCode(key:Int,name:String?):Boolean=throw AssertionError("Must not intercept factory keys")
                override fun releaseKeyCode(key:Int,name:String?):Boolean=throw AssertionError("Must not change factory interception")
                override fun registerListener(listener:IInputListener?,name:String?,codes:IntArray?) {registered=codes}
                override fun unregisterListener(listener:IInputListener?,name:String?) {unregistered++}
                override fun getControlIndex()=0
            }
            Shadows.shadowOf(RuntimeEnvironment.getApplication()).setComponentNameAndServiceForBindService(ComponentName("com.geely.inputservice","com.geely.inputservice.InputService"),service)
            activity.startWheelDetection();assertTrue(activity.ui.wheelDetecting)
            val probe=GeelyActivity::class.java.getDeclaredField("wheelProbe").apply {isAccessible=true}.get(activity) as ReceiverWheelKeys
            val callback=ReceiverWheelKeys::class.java.getDeclaredField("listener").apply {isAccessible=true}.get(probe) as IInputListener
            callback.onShortClick(200087,0);Shadows.shadowOf(Looper.getMainLooper()).idle()
            assertArrayEquals(intArrayOf(200085,200087,200088,200400),registered)
            assertTrue(activity.ui.wheelLastEvent.contains("200087"));assertTrue(activity.ui.wheelLastEvent.contains("未执行"))
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(21));assertFalse(activity.ui.wheelDetecting)
            assertNull(GeelyActivity::class.java.getDeclaredField("wheelProbe").apply {isAccessible=true}.get(activity))
            assertEquals(1,unregistered)
        } finally {lifecycle.pause().stop().destroy()}
    }
}
