package com.shilapi.xcertplay.geely

import org.junit.Assert.*
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs

class ReceiverAudioTestTest {
    private val plan=AudioTestPlan(ReceiverAudioRole.MEDIA,1,null)
    private class FakeOutput(val startAction:(ShortArray)->Unit={}):AudioTestOutput {
        val closes=AtomicInteger()
        override fun start(samples:ShortArray)=startAction(samples)
        override fun route()=AudioTestRoute(null,null,"bus0_media_out",21)
        override fun close() {closes.incrementAndGet()}
    }
    @Test fun everySignalHasFiveSecondsLowLevelAndFadedPulses() {
        for(role in ReceiverAudioRole.entries) {
            val samples=AudioTestSignal.samples(role)
            assertEquals(48000*5,samples.size)
            assertTrue(samples.any {it.toInt()!=0})
            assertTrue(samples.all {abs(it.toInt())<=AudioTestSignal.PEAK})
            for(second in 0..4) {
                assertEquals(0,samples[second*48000].toInt())
                assertTrue(samples.sliceArray(second*48000+28800 until (second+1)*48000).all {it.toInt()==0})
            }
        }
    }
    @Test fun countdownRunsFiveSecondsAndOutputClosesOnce() {
        val now=AtomicLong();val events=Collections.synchronizedList(mutableListOf<ReceiverAudioTest.Update>())
        val output=FakeOutput {assertEquals(240000,it.size)}
        val test=ReceiverAudioTest({_,_->output},now::get,{now.addAndGet(it)})
        assertTrue(test.start(plan) {events+=it});assertTrue(test.awaitStopped(2000))
        assertEquals(listOf(5,4,3,2,1),events.filter {it.phase==ReceiverAudioTest.Phase.PLAYING}.map {it.remaining})
        assertEquals(5000L,now.get());assertEquals(ReceiverAudioTest.Phase.FINISHED,events.last().phase)
        assertEquals(1,output.closes.get())
    }
    @Test fun duplicateStartIsRejectedAndStopDuringPreparationReleasesOutput() {
        val started=CountDownLatch(1);val block=CountDownLatch(1)
        val output=FakeOutput {started.countDown();block.await()}
        val events=Collections.synchronizedList(mutableListOf<ReceiverAudioTest.Update>())
        val test=ReceiverAudioTest({_,_->output},{0L},{Thread.sleep(it)})
        assertTrue(test.start(plan) {events+=it});assertTrue(started.await(2,TimeUnit.SECONDS))
        assertFalse(test.start(plan) {});test.stop();assertTrue(test.awaitStopped(2000))
        assertEquals(1,output.closes.get());assertEquals(ReceiverAudioTest.Phase.STOPPED,events.last().phase)
    }
    @Test fun failedStartReleasesResourcesAndReportsFailure() {
        val output=FakeOutput {throw IllegalStateException("focus unavailable")}
        val events=Collections.synchronizedList(mutableListOf<ReceiverAudioTest.Update>())
        val test=ReceiverAudioTest({_,_->output},{0L},{})
        test.start(plan) {events+=it};assertTrue(test.awaitStopped(2000))
        assertEquals(1,output.closes.get());assertEquals(ReceiverAudioTest.Phase.FAILED,events.last().phase)
        assertTrue(events.last().error!!.contains("focus unavailable"))
    }
    @Test fun testUsesSameSavedRoleUsageAndPreferredBusAndExposesMismatch() {
        val values=mapOf(ReceiverAudioRole.PHONE to "bus2_voice_command_out")
        val mapped=ReceiverAudioRouting.testPlan(ReceiverAudioRole.PHONE,values,"KX11_A2",emptyList())
        assertEquals(16,mapped.usage);assertEquals("bus2_voice_command_out",mapped.preferred!!.address)
        assertTrue(AudioTestRoute("bus1_navigation_out",true,"bus0_media_out",21).mismatch)
        assertFalse(AudioTestRoute("bus1_navigation_out",true,null,null).mismatch)
        assertTrue(AudioTestRoute("bus1_navigation_out",false,null,2).describe().contains("不一致"))
    }
    @Test fun losingFocusStopsTheFiniteWorkerAndReleasesOutput() {
        val playing=CountDownLatch(1)
        lateinit var focusLost:()->Unit
        val output=FakeOutput()
        val events=Collections.synchronizedList(mutableListOf<ReceiverAudioTest.Update>())
        val test=ReceiverAudioTest({_,cancel->focusLost=cancel;output},{0L},{Thread.sleep(1000)})
        test.start(plan) {events+=it;if(it.phase==ReceiverAudioTest.Phase.PLAYING) playing.countDown()}
        assertTrue(playing.await(2,TimeUnit.SECONDS));focusLost();assertTrue(test.awaitStopped(2000))
        assertEquals(1,output.closes.get());assertEquals(ReceiverAudioTest.Phase.STOPPED,events.last().phase)
        assertEquals("focus_lost",events.last().error)
    }
}
