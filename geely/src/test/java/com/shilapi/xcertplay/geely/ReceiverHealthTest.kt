package com.shilapi.xcertplay.geely

import org.junit.Assert.*
import org.junit.Test

class ReceiverHealthTest {
    @Test fun recoveryIsBoundedAndResetOnlyExplicitly() {
        val policy=ReceiverRecovery()
        assertNull(policy.nextDelay(false));assertEquals(0,policy.attempts)
        assertEquals(2000L,policy.nextDelay(true));assertEquals(5000L,policy.nextDelay(true))
        assertNull(policy.nextDelay(true));policy.reset();assertEquals(2000L,policy.nextDelay(true))
    }
    @Test fun permanentFailuresNeverBecomeNetworkRetries() {
        for(message in listOf("network configuration mismatch","Bluetooth not paired","permission denied","authentication failed","certificate missing","cancel")) assertFalse(message,ReceiverRecovery.transient(message))
        for(message in listOf("Wi-Fi network changed or disconnected","connection reset","Hotspot network is not ready")) assertTrue(message,ReceiverRecovery.transient(message))
        assertFalse(ReceiverRecovery.transient("iPhone disconnected"))
    }
    @Test fun measurementsUseObservedValuesAndSeparateChannels() {
        val health=ReceiverHealth()
        assertTrue(health.describe().contains("等待"))
        health.observe("output format requested=1920x1920 coded=1280x720 crop=0,0,1279,719")
        health.observe("video stats rx=29.5fps shown=28.0fps maxGap=20ms kbps=8000")
        health.observe("audio stats audioType=100 channel=MEDIA sampleRate=48000 estimatedQueuedFrames=4800 underruns=+2")
        health.observe("audio stats audioType=100 channel=MEDIA sampleRate=48000 estimatedQueuedFrames=2400 underruns=+1")
        health.observe("audio stats channel=NAVIGATION sampleRate=0 estimatedQueuedFrames=-1 underruns=+1")
        val summary=health.describe()
        assertTrue(summary.contains("1280x720"));assertTrue(summary.contains("29.5fps"))
        assertTrue(summary.contains("MEDIA：本机待播约 50 ms · 欠载累计 3"))
        assertTrue(summary.contains("NAVIGATION：本机待播约 未知"))
        health.reset();assertFalse(health.describe().contains("1280x720"))
    }
}
