package com.shilapi.xcertplay.media

import android.Manifest
import android.media.AudioRecord
import com.shilapi.xcertplay.airplay.AudioStreamId
import com.shilapi.xcertplay.airplay.MicrophoneConfig
import java.net.InetAddress
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAudioRecord
import org.robolectric.shadows.ShadowSystemClock
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class MicrophoneRouteRecoveryTest {
    @Test fun aDeadVoiceRecorderRecoversWithinTheSameStreamAndStaysStoppedAfterTeardown() {
        val context = RuntimeEnvironment.getApplication()
        shadowOf(context).grantPermissions(Manifest.permission.RECORD_AUDIO, Manifest.permission.MODIFY_AUDIO_SETTINGS)
        val created = AtomicInteger()
        val deadRead = CountDownLatch(1)
        val recovered = CountDownLatch(1)
        ShadowAudioRecord.setSourceProvider {
            val attempt = created.incrementAndGet()
            object : ShadowAudioRecord.AudioRecordSource {
                override fun readInByteArray(buffer: ByteArray, offset: Int, size: Int, blocking: Boolean): Int {
                    if (attempt == 1) { deadRead.countDown(); return AudioRecord.ERROR_DEAD_OBJECT }
                    recovered.countDown()
                    Thread.sleep(5)
                    return size
                }
            }
        }
        val sink = AndroidMediaSink(context = context, unifiedMediaOutput = true)
        fun refresh() = ReflectionHelpers.callInstanceMethod<Unit>(sink, "refreshCommunication")
        try {
            val id = AudioStreamId(100, "speechrecognition")
            sink.onMicrophoneStarted(id, MicrophoneConfig(audioType = "speechrecognition", sampleRate = 16000,
                channels = 1, payloadType = 100, frameMillis = 20, host = InetAddress.getLoopbackAddress(), port = 9, key = ByteArray(32)))
            assertTrue(deadRead.await(2, TimeUnit.SECONDS))
            val deadline = System.nanoTime() + 2_000_000_000L
            while (System.nanoTime() < deadline) {
                val active = ReflectionHelpers.getField<Map<*, *>>(sink, "microphoneUplinks")[id]
                if (active == null || !ReflectionHelpers.getField<MicrophoneUplink>(active, "uplink").isRunning) break
                Thread.sleep(5)
            }
            refresh()
            ShadowSystemClock.advanceBy(Duration.ofMillis(500))
            refresh()
            assertTrue("Same input stream should recover without reopening Siri", recovered.await(2, TimeUnit.SECONDS))
            sink.onMicrophoneStopped(id)
            val afterStop = created.get()
            ShadowSystemClock.advanceBy(Duration.ofSeconds(10))
            refresh()
            assertEquals(afterStop, created.get())
        } finally { sink.close(); ShadowAudioRecord.clearSource() }
    }
}
