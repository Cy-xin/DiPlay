package com.shilapi.xcertplay.geely

import android.os.Looper
import org.junit.Assert.*
import kotlinx.coroutines.flow.first
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
    @Test fun hostWindowControlsOrientationInsteadOfForcingLandscape() {
        val app=org.robolectric.RuntimeEnvironment.getApplication()
        val info=app.packageManager.getActivityInfo(android.content.ComponentName(app,GeelyActivity::class.java),0)
        assertEquals(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED,info.screenOrientation)
    }

    @Test fun compactAndLargeFontWindowsUseScrollableSingleColumn() {
        assertTrue(ReceiverLayoutPolicy.twoColumns(1280f,1f))
        assertTrue(ReceiverLayoutPolicy.twoColumns(1024f,1.35f))
        assertFalse(ReceiverLayoutPolicy.twoColumns(640f,1f))
        assertFalse(ReceiverLayoutPolicy.twoColumns(900f,1.35f))
        assertFalse(ReceiverLayoutPolicy.twoColumns(480f,1f))
    }
    @Test fun backDismissesDialogBeforeSettingsAndNeverChangesConnectionState() {
        val ui=ReceiverUiState();ui.busy=true;ui.settingsOpen=true;ui.dialog=ReceiverDialog.AUDIO
        assertTrue(ui.dismissTopLayer());assertNull(ui.dialog);assertTrue(ui.settingsOpen)
        assertTrue(ui.dismissTopLayer());assertFalse(ui.settingsOpen);assertTrue(ui.busy)
        assertFalse(ui.dismissTopLayer())
    }
    @Test fun appearancePersistsSeparatelyWithoutTouchingPairingsOrPlayback() = kotlinx.coroutines.runBlocking {
        val app=org.robolectric.RuntimeEnvironment.getApplication()
        val connection=ReceiverStore(app)
        connection.prefs.edit().putInt("video_fps",60).putString("phone","test-address").commit()
        val appearance=ReceiverAppearance(app)
        appearance.save(ReceiverThemeMode.DARK)
        assertEquals(ReceiverThemeMode.DARK,appearance.mode.first())
        assertEquals(60,connection.prefs.getInt("video_fps",30))
        assertEquals("test-address",connection.prefs.getString("phone",null))
        appearance.save(ReceiverThemeMode.SYSTEM)
        assertEquals(ReceiverThemeMode.SYSTEM,appearance.mode.first())
    }

    @Test fun playbackChoicesSurviveReopenAndReachProtocolWithoutClaiming120Fps() {
        var lifecycle=Robolectric.buildActivity(GeelyActivity::class.java).setup()
        val store=field(lifecycle.get(),"store") as ReceiverStore
        store.prefs.edit().putInt("media_buffer_ms",150).putInt("video_fps",60).commit()
        lifecycle.pause().stop().destroy()
        lifecycle=Robolectric.buildActivity(GeelyActivity::class.java).setup()
        assertEquals(150,lifecycle.get().ui.bufferMs)
        assertEquals(60,lifecycle.get().ui.fps)
        val config=receiverAirPlayConfig(com.shilapi.xcertplay.airplay.AirPlayIdentity.generate(),1280,720,60)
        assertEquals(60,config.main.fps)
        val advertised=com.shilapi.xcertplay.airplay.AirPlayInfoPlist.build(config)["displays"] as List<*>
        assertEquals(60,(advertised.first() as Map<*,*>)["maxFPS"])
        assertEquals(30,ReceiverPlaybackSettings.sanitizeFps(120))
        for(buffer in ReceiverPlaybackSettings.bufferChoices) {
            val plan=com.shilapi.xcertplay.media.MediaAudioBuffer.plan(true,48000,2,1024,buffer)
            assertEquals(48000*4*buffer/1000,plan.startBytes)
        }
        assertEquals(200,ReceiverPlaybackSettings.sanitizeBuffer(-1))
        lifecycle.pause().stop().destroy()
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
        activity.ui.ssid="CarAP";activity.ui.password="hotspot-pass"
        activity.switchMode(ConnectionMode.SAME_LAN)
        activity.ui.ssid="Router";activity.ui.password="router-pass"
        activity.switchMode(ConnectionMode.CAR_HOTSPOT)
        assertEquals("CarAP",activity.ui.ssid);assertEquals("hotspot-pass",activity.ui.password)
        activity.switchMode(ConnectionMode.SAME_LAN)
        assertEquals("Router",activity.ui.ssid);assertEquals("router-pass",activity.ui.password)
        assertEquals(2,ConnectionMode.entries.size)
        lifecycle.pause().stop().destroy()
    }
    @Test fun duplicateConnectAndCancellationCannotPublishStaleController() {
        val lifecycle=Robolectric.buildActivity(GeelyActivity::class.java).setup();val activity=lifecycle.get()
        activity.switchMode(ConnectionMode.SAME_LAN)
        activity.ui.ssid="Router";activity.ui.password=""
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
