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
    @Test fun videoDiagnosticsSeparateQueueDecoderAndEstimatedTouchSamples() {
        val health=ReceiverHealth()
        health.observe("decoder=c2.qti.avc.decoder mime=video/avc vendorLowLatency=true outputThread=true")
        health.observe("video stats rx=60.0fps shown=60.0fps kbps=8000 touch2frame avg=12ms max=20ms n=2 decode p50=15ms p90=25ms queueMax=70ms queuePeak=7")
        val report=health.describe()
        assertTrue(report.contains("c2.qti.avc.decoder"));assertTrue(report.contains("15ms / 25ms"))
        assertTrue(report.contains("70ms"));assertTrue(report.contains("12ms（估计）"))
        health.observe("video stats rx=0.2fps shown=0.2fps touch2frame avg=-1ms n=0")
        assertTrue(health.describe().contains("等待样本"));assertFalse(health.describe().contains("-1ms"))
        health.reset();assertFalse(health.describe().contains("c2.qti"))
    }
}
