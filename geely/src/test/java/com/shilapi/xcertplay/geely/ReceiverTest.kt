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
    @Test fun setupKeepsPrimaryAndStatusInsideWindowAcrossWidthsAndLargeFonts() {
        for(font in listOf(1f,1.35f)) {
            val lifecycle=Robolectric.buildActivity(GeelyActivity::class.java).setup()
            val activity=lifecycle.get()
            activity.resources.configuration.fontScale=font
            val page=field(activity,"setupPage") as android.view.View
            val primary=field(activity,"primary") as android.view.View
            val status=field(activity,"status") as android.view.View
            for((width,height) in listOf(1280 to 600,1024 to 480,640 to 360,480 to 600)) {
                repeat(3) {
                    page.measure(android.view.View.MeasureSpec.makeMeasureSpec(width,android.view.View.MeasureSpec.EXACTLY),android.view.View.MeasureSpec.makeMeasureSpec(height,android.view.View.MeasureSpec.EXACTLY))
                    page.layout(0,0,width,height)
                }
                for(view in listOf(primary,status)) {
                    val bounds=android.graphics.Rect(0,0,view.width,view.height)
                    (page as android.view.ViewGroup).offsetDescendantRectToMyCoords(view,bounds)
                    assertTrue("$width x $height font=$font: $bounds",bounds.top>=0 && bounds.bottom<=height && bounds.left>=0 && bounds.right<=width)
                    assertTrue(view.height>0 && view.width>0)
                }
            }
            lifecycle.pause().stop().destroy()
        }
    }

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
        (field(activity,"video") as android.view.View).layout(0,0,1280,720)
        val worker=field(activity,"worker") as ExecutorService
        val gate=CountDownLatch(1);worker.execute { gate.await(5,TimeUnit.SECONDS) }
        shadowOf(activity.application).grantPermissions(android.Manifest.permission.RECORD_AUDIO)
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

    @Test fun hiddenPhysicalBluetoothAddressStillAllowsWirelessIdentification() {
        val identity=com.shilapi.xcertplay.airplay.AirPlayIdentity.generate()
        val air=receiverAirPlayConfig(identity,1280,720)
        // Real phones may return a placeholder address; the controller then uses this fallback.
        val wireless=com.shilapi.xcertplay.transport.Iap2WirelessIdentification(air.btMac,"TestNetwork")
        assertEquals(ReceiverStore.deviceId(identity),wireless.bluetoothMac)
        assertEquals(air.btMac,receiverAirPlayConfig(identity,1280,720).btMac)
    }

    @Test fun productionAudioOfferIncludesInputAndOutputInsteadOfDisplayOnlyAudio() {
        val air=receiverAirPlayConfig(com.shilapi.xcertplay.airplay.AirPlayIdentity.generate(),1280,720)
        val info=com.shilapi.xcertplay.airplay.AirPlayInfoPlist.build(air)
        val formats=(info["audioFormats"] as List<*>).filterIsInstance<Map<*,*>>()
        assertTrue("CarPlay must offer its actual microphone capability",air.microphone)
        assertFalse(air.disableAudioOutput)
        assertTrue(formats.any { it["audioType"]=="default" && (it["audioInputFormats"] as? Number)?.toInt()?.let { value -> value!=0 }==true })
        assertTrue(formats.any { it["audioType"]=="media" && (it["audioOutputFormats"] as? Number)?.toInt()?.let { value -> value!=0 }==true })
    }

    @Test fun deniedMicrophonePermissionDoesNotStartAnAttemptOrAdvertiseAnUnusableInput() {
        val lifecycle=Robolectric.buildActivity(GeelyActivity::class.java).setup();val activity=lifecycle.get()
        shadowOf(activity.application).denyPermissions(android.Manifest.permission.RECORD_AUDIO)
        invoke(activity,"connect")
        val request=shadowOf(activity).lastRequestedPermission
        assertTrue(request.requestedPermissions.contains(android.Manifest.permission.RECORD_AUDIO))
        activity.onRequestPermissionsResult(request.requestCode,request.requestedPermissions,IntArray(request.requestedPermissions.size) { android.content.pm.PackageManager.PERMISSION_DENIED })
        assertEquals(false,field(activity,"busy"));assertEquals(0,field(activity,"generation"));assertNull(field(activity,"controller"));assertNull(field(activity,"leaseToken"))
        lifecycle.pause().stop().destroy()
    }
}
