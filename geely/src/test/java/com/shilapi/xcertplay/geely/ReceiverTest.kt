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
    @Suppress("DEPRECATION")
    @Test fun immersiveFlagsRestoreOnExitAndSavedChoiceReturnsOnReopen() {
        val lifecycle=Robolectric.buildActivity(GeelyActivity::class.java).setup()
        val activity=lifecycle.get()
        val store=field(activity,"store") as ReceiverStore
        assertFalse(activity.ui.hideSystemBars)
        assertEquals(android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS,activity.window.attributes.layoutInDisplayCutoutMode)
        assertFalse(activity.window.isStatusBarContrastEnforced)
        assertFalse(activity.window.isNavigationBarContrastEnforced)
        store.prefs.edit().putBoolean("hide_system_bars",true).commit();activity.ui.hideSystemBars=true
        activity.onWindowFocusChanged(true)
        val mask=android.view.View.SYSTEM_UI_FLAG_FULLSCREEN or android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        assertEquals(mask,activity.window.decorView.systemUiVisibility and mask)
        lifecycle.pause().stop()
        assertEquals(0,activity.window.decorView.systemUiVisibility and mask)
        assertEquals(android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS,activity.window.attributes.layoutInDisplayCutoutMode)
        lifecycle.destroy()
        val reopened=Robolectric.buildActivity(GeelyActivity::class.java).setup()
        assertTrue(reopened.get().ui.hideSystemBars)
        assertEquals(mask,reopened.get().window.decorView.systemUiVisibility and mask)
        reopened.pause().stop().destroy()
    }

    @Test fun returningHomeAndStoppingActivityRetainConnectedSessionUntilExplicitDisconnect() {
        val lifecycle=Robolectric.buildActivity(GeelyActivity::class.java).setup();val activity=lifecycle.get()
        GeelyActivity::class.java.getDeclaredField("connected").apply {isAccessible=true}.set(activity,true)
        GeelyActivity::class.java.getDeclaredField("busy").apply {isAccessible=true}.set(activity,true)
        activity.ui.showCarPlay=true;invoke(activity,"applyState")
        val generation=field(activity,"generation")
        activity.showReceiverHome()
        assertTrue(activity.ui.connected);assertFalse(activity.ui.showCarPlay)
        activity.onBackPressedDispatcher.onBackPressed()
        assertFalse(activity.isFinishing)
        lifecycle.pause().stop()
        assertEquals(generation,field(activity,"generation"));assertTrue(activity.ui.connected)
        lifecycle.start().resume()
        invoke(activity,"showCarPlay");assertTrue(activity.ui.showCarPlay)
        activity.onBackPressedDispatcher.onBackPressed()
        assertTrue(activity.ui.connected);assertFalse(activity.ui.showCarPlay)
        activity.disconnectFromNotification()
        assertFalse(activity.ui.connected)
        (field(activity,"worker") as ExecutorService).submit {}.get(5,TimeUnit.SECONDS)
        shadowOf(Looper.getMainLooper()).idle();lifecycle.pause().stop().destroy()
    }
    @Test fun hostResolutionChangeFitsTheExistingSessionInsteadOfDisconnecting() {
        val lifecycle=Robolectric.buildActivity(GeelyActivity::class.java).setup();val activity=lifecycle.get()
        fun set(name:String,value:Any) {GeelyActivity::class.java.getDeclaredField(name).apply {isAccessible=true}.set(activity,value)}
        set("connected",true);set("busy",true);set("configuredWidth",1280);set("configuredHeight",720)
        (field(activity,"root") as android.view.View).layout(0,0,720,1280)
        val generation=field(activity,"generation")
        activity.onConfigurationChanged(android.content.res.Configuration(activity.resources.configuration))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(generation,field(activity,"generation"));assertEquals(true,field(activity,"connected"))
        val bounds=(field(activity,"video") as android.view.View).layoutParams
        // A later window traversal can replace the manually supplied size; fit the latest root.
        val window=field(activity,"root") as android.view.View
        assertEquals(window.width,bounds.width)
        assertEquals(16f/9f,bounds.width.toFloat()/bounds.height,.02f)
        assertTrue(bounds.height<=window.height)
        activity.disconnectFromNotification();(field(activity,"worker") as ExecutorService).submit {}.get(5,TimeUnit.SECONDS)
        shadowOf(Looper.getMainLooper()).idle();lifecycle.pause().stop().destroy()
    }
    @Test fun displayPreferencesSaveForNextConnectionWithoutEndingOrResizingRunningSession() {
        val lifecycle=Robolectric.buildActivity(GeelyActivity::class.java).setup();val activity=lifecycle.get()
        fun set(name:String,value:Any) {GeelyActivity::class.java.getDeclaredField(name).apply {isAccessible=true}.set(activity,value)}
        set("connected",true);set("busy",true);set("configuredWidth",1280);set("configuredHeight",720)
        invoke(activity,"applyState");val generation=field(activity,"generation")
        activity.saveDisplayPreference(true,125);activity.saveDisplayPreference(false,85);activity.saveDockPreference(ReceiverDockPosition.RIGHT.value)
        assertEquals(generation,field(activity,"generation"));assertTrue(activity.ui.connected)
        assertEquals(1280,field(activity,"configuredWidth"));assertEquals(720,field(activity,"configuredHeight"))
        assertEquals(125,activity.ui.quality);assertEquals(85,activity.ui.carPlaySize);assertTrue(activity.ui.reconnectNeeded)
        val prefs=(field(activity,"store") as ReceiverStore).prefs
        assertEquals(125,prefs.getInt("display_quality",0));assertEquals(85,prefs.getInt("carplay_ui_size",0))
        activity.disconnectFromNotification();(field(activity,"worker") as ExecutorService).submit {}.get(5,TimeUnit.SECONDS)
        shadowOf(Looper.getMainLooper()).idle();lifecycle.pause().stop().destroy()
    }
    @Test fun bundledGeelyHomeIconIsAdvertisedAsRealPngInsteadOfEmptyOemEntry() {
        val app=org.robolectric.RuntimeEnvironment.getApplication()
        val icons=receiverHomeIcons(app);assertEquals(1,icons.size)
        val icon=icons.single();assertEquals(icon.widthPixels,icon.heightPixels);assertTrue(icon.widthPixels in 1..ReceiverHomeIconSizing.MAX_PIXELS)
        assertNotNull(android.graphics.BitmapFactory.decodeByteArray(icon.data,0,icon.data.size))
        val air=receiverAirPlayConfig(com.shilapi.xcertplay.airplay.AirPlayIdentity.generate(),1280,720,30,icons)
        val info=com.shilapi.xcertplay.airplay.AirPlayInfoPlist.build(air)
        assertEquals(true,info["oemIconVisible"]);assertEquals("吉利",info["oemIconLabel"])
        val advertised=(info["oemIcons"] as List<*>).single() as Map<*,*>
        assertArrayEquals(icon.data,advertised["imageData"] as ByteArray)
    }
    @Test fun idleUiDoesNotStartAConnectionService() {
        val lifecycle=Robolectric.buildActivity(GeelyActivity::class.java).setup()
        assertNull(shadowOf(lifecycle.get().application).nextStartedService)
        lifecycle.pause().stop().destroy()
    }

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
    @Test fun rejectsInvalidManualInterfaceBeforeStartingHandshake() {
        try { ReceiverConfig(ConnectionMode.CAR_HOTSPOT,"wifi","12345678","invalid interface",null).validate();fail() }
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
    @Test fun scheduledRecoveryCanBeCancelledBeforeItCreatesAController() {
        val lifecycle=Robolectric.buildActivity(GeelyActivity::class.java).setup();val activity=lifecycle.get()
        GeelyActivity::class.java.getDeclaredMethod("recoverOrEnd",String::class.java,Boolean::class.javaPrimitiveType).apply {isAccessible=true}.invoke(activity,"network changed",true)
        val worker=field(activity,"worker") as ExecutorService
        worker.submit {}.get(5,TimeUnit.SECONDS);shadowOf(Looper.getMainLooper()).idle()
        assertEquals(true,field(activity,"recovering"));assertTrue(activity.ui.busy)
        GeelyActivity::class.java.getDeclaredMethod("endAttempt",String::class.java).apply {isAccessible=true}.invoke(activity,"已取消连接")
        worker.submit {}.get(5,TimeUnit.SECONDS);shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(6))
        assertEquals(false,field(activity,"recovering"));assertFalse(activity.ui.busy);assertNull(field(activity,"controller"))
        lifecycle.pause().stop().destroy()
    }
}
