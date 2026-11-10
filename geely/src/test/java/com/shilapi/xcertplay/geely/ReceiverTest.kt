package com.shilapi.xcertplay.geely

import android.os.Looper
import android.widget.RadioButton
import android.widget.EditText
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[30])
class ReceiverTest {
    private fun field(activity:GeelyActivity, name:String):Any? = GeelyActivity::class.java.getDeclaredField(name).apply { isAccessible=true }.get(activity)
    private fun invoke(activity:GeelyActivity,name:String) = GeelyActivity::class.java.getDeclaredMethod(name).apply { isAccessible=true }.invoke(activity)
    @Test fun rejectsOversizeUtf8SsidAndInvalidPasswordWithoutLoggingCredentials() {
        for(config in listOf(ReceiverConfig(ConnectionMode.SAME_LAN,"中".repeat(11),"12345678","",null),ReceiverConfig(ConnectionMode.SAME_LAN,"wifi","short","",null))) {
            try { config.validate();fail("Expected invalid configuration") } catch(_:IllegalArgumentException) {}
            assertFalse(config.toString().contains(config.password))
        }
        ReceiverConfig(ConnectionMode.SAME_LAN,"wifi","","",null).validate()
    }
    @Test fun rejectsMissingManualInterfaceBeforeStartingHandshake() {
        try { ReceiverConfig(ConnectionMode.CAR_HOTSPOT,"wifi","12345678","no_such_geely0",null).validate();fail() }
        catch(e:IllegalArgumentException) { assertTrue(e.message!!.contains("接口")) }
    }
    @Test fun switchingModesKeepsSeparateCredentialsAndOnlyTwoModes() {
        val lifecycle=Robolectric.buildActivity(GeelyActivity::class.java).setup();val activity=lifecycle.get()
        val ssid=field(activity,"ssid") as EditText;val password=field(activity,"password") as EditText
        ssid.setText("CarAP");password.setText("hotspot-pass")
        (field(activity,"modeLan") as RadioButton).isChecked=true
        ssid.setText("Router");password.setText("router-pass")
        (field(activity,"modeHotspot") as RadioButton).isChecked=true
        assertEquals("CarAP",ssid.text.toString());assertEquals("hotspot-pass",password.text.toString())
        (field(activity,"modeLan") as RadioButton).isChecked=true
        assertEquals("Router",ssid.text.toString());assertEquals("router-pass",password.text.toString())
        assertEquals(2,ConnectionMode.entries.size)
        lifecycle.pause().stop().destroy()
    }
    @Test fun duplicateConnectAndCancellationCannotPublishStaleController() {
        val lifecycle=Robolectric.buildActivity(GeelyActivity::class.java).setup();val activity=lifecycle.get()
        (field(activity,"modeLan") as RadioButton).isChecked=true
        (field(activity,"ssid") as EditText).setText("Router")
        (field(activity,"password") as EditText).setText("")
        val worker=field(activity,"worker") as ExecutorService
        val gate=CountDownLatch(1);worker.execute { gate.await(5,TimeUnit.SECONDS) }
        invoke(activity,"connect");val first=field(activity,"generation")
        invoke(activity,"connect");assertEquals(first,field(activity,"generation"))
        GeelyActivity::class.java.getDeclaredMethod("endAttempt",String::class.java).apply { isAccessible=true }.invoke(activity,"cancelled")
        gate.countDown();worker.submit {}.get(5,TimeUnit.SECONDS);shadowOf(Looper.getMainLooper()).idle()
        assertNull(field(activity,"controller"));assertEquals(false,field(activity,"busy"));assertEquals(false,field(activity,"stopping"))
        lifecycle.pause().stop().destroy()
    }
    @Test fun terminalFailureAfterFirstFrameEndsTheConnection() {
        val lifecycle=Robolectric.buildActivity(GeelyActivity::class.java).setup();val activity=lifecycle.get()
        GeelyActivity::class.java.getDeclaredField("connected").apply { isAccessible=true }.set(activity,true)
        GeelyActivity::class.java.getDeclaredMethod("onStatus",Int::class.javaPrimitiveType,com.shilapi.xcertplay.orchestration.CarPlayStatus::class.java).apply { isAccessible=true }.invoke(activity,field(activity,"generation"),com.shilapi.xcertplay.orchestration.CarPlayStatus.Failed("tunnel closed"))
        val worker=field(activity,"worker") as ExecutorService
        worker.submit {}.get(5,TimeUnit.SECONDS);shadowOf(Looper.getMainLooper()).idle()
        assertEquals(false,field(activity,"connected"));assertEquals(false,field(activity,"stopping"))
        lifecycle.pause().stop().destroy()
    }

    @Test fun systemNightModeDoesNotDisconnectAnUnchangedDisplay() {
        val lifecycle=Robolectric.buildActivity(GeelyActivity::class.java).setup();val activity=lifecycle.get()
        (field(activity,"video") as android.view.View).layout(0,0,1280,720)
        for((name,value) in listOf("configuredWidth" to 1280,"configuredHeight" to 720)) {
            GeelyActivity::class.java.getDeclaredField(name).apply { isAccessible=true }.set(activity,value)
        }
        GeelyActivity::class.java.getDeclaredField("busy").apply { isAccessible=true }.set(activity,true)
        activity.onConfigurationChanged(android.content.res.Configuration(activity.resources.configuration).apply { uiMode=android.content.res.Configuration.UI_MODE_NIGHT_YES })
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(true,field(activity,"busy"))
        lifecycle.pause().stop().destroy()
    }

    @Test fun processGateBlocksAnotherActivityUntilTheOriginalAttemptReleases() {
        val first=Any();val second=Any()
        assertTrue(ReceiverSessionGate.acquire(first))
        assertFalse(ReceiverSessionGate.acquire(second))
        ReceiverSessionGate.release(second)
        assertFalse(ReceiverSessionGate.acquire(second))
        ReceiverSessionGate.release(first)
        assertTrue(ReceiverSessionGate.acquire(second))
        ReceiverSessionGate.release(second)
    }

}
