package com.shilapi.xcertplay.geely

import android.view.SurfaceView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[30])
class ReceiverAutoConnectTest {
    @Test fun oneAttemptPerOpenWaitsForDisplayAndManualCancellationSuppressesIt() {
        val policy=ReceiverAutoConnect()
        policy.open(false);assertFalse(policy.take(false,true,true))
        policy.open(true)
        assertFalse(policy.take(true,false,true));assertFalse(policy.take(true,true,false))
        assertTrue(policy.take(true,true,true))
        repeat(10) {assertFalse(policy.take(true,true,true))}
        policy.open(true);policy.suppress();assertFalse(policy.take(true,true,true))
        policy.open(true);assertTrue(policy.take(true,true,true))
        policy.open(true);assertFalse(policy.take(false,true,true));assertFalse(policy.take(true,true,true))
    }
    private fun field(activity:GeelyActivity,name:String):Any? = GeelyActivity::class.java.getDeclaredField(name).apply {isAccessible=true}.get(activity)
    @Test fun preferenceIsOffByDefaultPersistsAndNeverConnectsImmediatelyWhenEnabled() {
        val first=Robolectric.buildActivity(GeelyActivity::class.java).setup()
        try {
            val activity=first.get();assertFalse(activity.ui.autoConnectOnOpen)
            activity.saveAutoConnectOnOpen(true)
            (field(activity,"video") as SurfaceView).layout(0,0,640,480)
            activity.maybeAutoConnect()
            assertNull(field(activity,"controller"));assertFalse(field(activity,"busy") as Boolean)
            assertTrue((field(activity,"store") as ReceiverStore).prefs.getBoolean("auto_connect_on_open",false))
        } finally {first.pause().stop().destroy()}
        val reopened=Robolectric.buildActivity(GeelyActivity::class.java).setup()
        try {
            val activity=reopened.get();assertTrue(activity.ui.autoConnectOnOpen)
            activity.ui.ssid=""
            (field(activity,"video") as SurfaceView).layout(0,0,640,480)
            activity.maybeAutoConnect()
            assertTrue(activity.ui.message.contains("配置"));assertNull(field(activity,"controller"))
            activity.ui.message="consumed";activity.maybeAutoConnect();assertEquals("consumed",activity.ui.message)
            activity.saveAutoConnectOnOpen(false)
        } finally {reopened.pause().stop().destroy()}
    }
}
