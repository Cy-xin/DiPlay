package com.shilapi.xcertplay.geely

import android.graphics.SurfaceTexture
import android.media.MediaFormat
import android.view.Surface
import com.shilapi.xcertplay.media.AndroidMediaSink
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowMediaCodec
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** An interrupt-resistant fake native call checks the Activity's real close/surface ownership path. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[30])
class ReceiverCodecLifetimeTest {
    @Test fun closeAndSurfaceDestroyKeepTheLeaseUntilTheCodecReallyExits() {
        val entered=CountDownLatch(1);val unblock=CountDownLatch(1)
        val codec=object:ShadowMediaCodec.CodecConfig.Codec {
            override fun process(input:ByteBuffer,output:ByteBuffer) {
                entered.countDown();var interrupted=false
                while(true) {try {unblock.await();break} catch(_:InterruptedException) {interrupted=true}}
                if(interrupted) Thread.currentThread().interrupt()
            }
        }
        ShadowMediaCodec.addDecoder(MediaFormat.MIMETYPE_VIDEO_AVC,ShadowMediaCodec.CodecConfig(65536,65536,codec))
        val lifecycle=Robolectric.buildActivity(GeelyActivity::class.java).setup();val activity=lifecycle.get()
        val texture=SurfaceTexture(0);val surface=Surface(texture);val sink=AndroidMediaSink()
        val lease=Any();val next=Any()
        fun set(name:String,value:Any) {GeelyActivity::class.java.getDeclaredField(name).apply {isAccessible=true}.set(activity,value)}
        try {
            val sps=byteArrayOf(0x67,0x42,0xC0.toByte(),0x1E);val pps=byteArrayOf(0x68,0xCE.toByte())
            val avcc=byteArrayOf(1,0x42,0xC0.toByte(),0x1E,0xFF.toByte(),0xE1.toByte(),0,sps.size.toByte())+sps+byteArrayOf(1,0,pps.size.toByte())+pps
            sink.setSurface(110,surface);sink.onVideoConfig(110,avcc);sink.onVideoFrame(110,byteArrayOf(0,0,0,1,0x65,0x88.toByte(),0x84.toByte(),0x21))
            assertTrue(entered.await(5,TimeUnit.SECONDS))
            AndroidMediaSink::class.java.getDeclaredField("detachTimeoutNanos").apply {isAccessible=true}.setLong(sink,100_000_000L)
            AndroidMediaSink::class.java.getDeclaredField("detachGraceNanos").apply {isAccessible=true}.setLong(sink,100_000_000L)
            assertTrue(ReceiverSessionGate.acquire(lease));set("leaseToken",lease);set("sink",sink);set("currentSurface",surface);set("connected",true);set("busy",true)
            activity.disconnectFromNotification()
            activity.detachSessionSurface(surface)
            val journal=GeelyActivity::class.java.getDeclaredField("journal").apply {isAccessible=true}.get(activity) as ReceiverDiagnostics
            assertTrue("Surface destruction must include the retiring renderer and report its unconfirmed handoff",journal.recent().contains("released=false sinks=1 current=true"))
            assertFalse("close() returning cannot release the connection while the codec is blocked",ReceiverSessionGate.acquire(next))
            unblock.countDown()
            val deadline=System.nanoTime()+5_000_000_000L;var acquired=false
            while(!acquired && System.nanoTime()<deadline) {acquired=ReceiverSessionGate.acquire(next);if(!acquired) Thread.sleep(5)}
            assertTrue("native exit must deliver late completion",acquired)
            val retiring=GeelyActivity::class.java.getDeclaredField("releasingSinks").apply {isAccessible=true}.get(activity) as Collection<*>
            assertTrue(retiring.isEmpty())
        } finally {
            unblock.countDown();sink.close();sink.awaitVideoReleased(5000)
            ReceiverSessionGate.release(lease);ReceiverSessionGate.release(next)
            lifecycle.pause().stop().destroy();surface.release();texture.release();ShadowMediaCodec.clearCodecs()
        }
    }
}
