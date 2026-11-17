package com.shilapi.xcertplay.geely

import android.graphics.SurfaceTexture
import android.os.Looper
import android.view.Surface
import com.shilapi.xcertplay.airplay.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config
import java.net.Socket
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[30])
class ReceiverLifecycleTest {
    private fun set(a:GeelyActivity,name:String,value:Any?) {GeelyActivity::class.java.getDeclaredField(name).apply {isAccessible=true}.set(a,value)}
    private fun get(a:GeelyActivity,name:String)=GeelyActivity::class.java.getDeclaredField(name).apply {isAccessible=true}.get(a)
    @Test fun cancelledPreparationRetainsGlobalLeaseUntilWorkerReallyExitsEvenAfterDestroy() {
        val lifecycle=Robolectric.buildActivity(GeelyActivity::class.java).setup();val activity=lifecycle.get()
        val prep=ReceiverPreparation();val entered=CountDownLatch(1);val leave=CountDownLatch(1);val lease=Any()
        assertTrue(ReceiverSessionGate.acquire(lease));set(activity,"leaseToken",lease);set(activity,"preparation",prep);set(activity,"busy",true)
        val worker=Thread {prep.run {entered.countDown();leave.await()}}
        try {
            worker.start();assertTrue(entered.await(3,TimeUnit.SECONDS));lifecycle.pause().stop().destroy()
            assertFalse(ReceiverSessionGate.acquire(Any()))
            leave.countDown();worker.join(3000)
            val next=Any();var acquired=false;val deadline=System.nanoTime()+3_000_000_000L
            while(!acquired && System.nanoTime()<deadline) {acquired=ReceiverSessionGate.acquire(next);if(!acquired) Thread.sleep(5)}
            assertTrue("late completion releases the original lease",acquired);ReceiverSessionGate.release(next)
        } finally {leave.countDown();worker.join(3000);ReceiverSessionGate.release(lease)}
    }
    @Test fun lateSessionActivationCannotTouchDestroyedActivityAndOldSurfaceCannotClearNewOne() {
        val lifecycle=Robolectric.buildActivity(GeelyActivity::class.java).setup();val activity=lifecycle.get()
        val textures=listOf(SurfaceTexture(0),SurfaceTexture(0));val surfaces=textures.map(::Surface)
        val listener=GeelyActivity::class.java.getDeclaredMethod("listener",Int::class.javaPrimitiveType).apply {isAccessible=true}.invoke(activity,get(activity,"generation")) as AirPlaySessionListener
        val session=AirPlaySession(Socket(),AirPlayConfig("test","00:11:22:33:44:55","00:11:22:33:44:55","1",AirPlayDisplayConfig(640,480)),AirPlayIdentity.generate(),PairingStore(),null,object:AirPlaySessionListener {},object:AirPlayMediaHandler {})
        try {
            set(activity,"currentSurface",surfaces[1]);activity.ui.pictureRestoreFailed=true
            activity.detachSessionSurface(surfaces[0]);assertSame(surfaces[1],get(activity,"currentSurface"));assertTrue(activity.ui.pictureRestoreFailed)
            activity.detachSessionSurface(surfaces[1]);assertNull(get(activity,"currentSurface"));assertFalse(activity.ui.pictureRestoreFailed)
            lifecycle.pause().stop().destroy();val before=activity.ui.message
            listener.onSessionActive(session);Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(40))
            assertNull(get(activity,"currentAirPlaySession"));assertEquals(before,activity.ui.message)
            assertNull(get(activity,"surfaceWork"))
        } finally {session.close();surfaces.forEach(Surface::release);textures.forEach(SurfaceTexture::release)}
    }
}
