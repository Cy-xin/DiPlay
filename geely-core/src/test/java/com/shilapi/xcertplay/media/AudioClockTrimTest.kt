package com.shilapi.xcertplay.media

import android.media.AudioAttributes
import android.media.AudioTrack
import com.shilapi.xcertplay.airplay.AudioCodecKind
import com.shilapi.xcertplay.airplay.AudioFormat
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

/** The music renderer trims its track's playback rate from the queued level (#610). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], manifest = Config.NONE, shadows = [AudioClockTrimTest.RateTrack::class])
class AudioClockTrimTest {
    private val reports = mutableListOf<String>()
    private val type = Class.forName("com.shilapi.xcertplay.media.AudioRenderer")
    private lateinit var renderer: Any
    private lateinit var track: AudioTrack

    @Before fun setUp() {
        MediaClockTrim.learnedTrim = 0.0
        RateTrack.rates.clear()
        RateTrack.head = 0
        RateTrack.result = AudioTrack.SUCCESS
        track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().build())
            .setAudioFormat(android.media.AudioFormat.Builder()
                .setSampleRate(48_000)
                .setEncoding(android.media.AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(android.media.AudioFormat.CHANNEL_OUT_STEREO)
                .build())
            .setBufferSizeInBytes(96_000)
            .build()
        renderer = audioRendererFixture(AudioFormat(AudioCodecKind.LPCM, 48_000, 2, 96, "media"), 500) {
            message -> reports.add(message)
        }
        set("track", track)
        set("mappedChannel", AudioChannel.MEDIA)
        set("playbackStarted", true)
        set("startThresholdBytes", 57_600)
        set("bytesPerSecond", 48_000 * 4)
        // 300 ms of music is the target; createTrack sets this up for music only.
        set("clockTrim", MediaClockTrim(48_000, 14_400))
    }

    @After fun tearDown() {
        track.release()
        MediaClockTrim.learnedTrim = 0.0
    }

    private fun set(name: String, value: Any?) = type.getDeclaredField(name).apply { isAccessible = true }.set(renderer, value)
    private fun get(name: String): Any? = type.getDeclaredField(name).apply { isAccessible = true }.get(renderer)

    private fun write(bytes: Int) = type.getDeclaredMethod("writePcm", ByteArray::class.java,
        Int::class.javaPrimitiveType, Int::class.javaPrimitiveType).apply { isAccessible = true }
        .invoke(renderer, ByteArray(bytes), 0, bytes)

    @Test fun aLowLevelSlowsTheTrackAndTheStatsShowTheRate() {
        // 150 ms queued against a 300 ms target.
        write(28_800)
        assertEquals(listOf(47_280), RateTrack.rates)
        type.getDeclaredMethod("logStatsIfDue", Boolean::class.javaPrimitiveType).apply { isAccessible = true }
            .invoke(renderer, true)
        assertTrue(reports.toString(), reports.any { it.startsWith("audio stats") && it.contains(" playbackRate=47280 ended=true") })
    }

    @Test fun aTrackThatRefusesTheRateKeepsPlayingWithoutTheTrim() {
        RateTrack.result = AudioTrack.ERROR_INVALID_OPERATION
        write(28_800)
        assertNull(get("clockTrim"))
        assertTrue(reports.toString(), reports.contains("Audio: clock trim off audioType=media rate=47280 result=-3"))
        write(28_800)
        assertEquals(listOf(47_280), RateTrack.rates)
    }

    @Test fun nothingIsTrimmedBeforeMusicPlays() {
        set("playbackStarted", false)
        write(2_048)
        assertTrue(RateTrack.rates.isEmpty())
    }

    @Test fun maintenanceBrakesDrainingMusicWithoutWaitingForAnotherPacket() {
        write(96_000)
        fun maintain() = type.getDeclaredMethod("maintainPlaybackBuffer")
            .apply { isAccessible = true }.invoke(renderer)
        maintain()
        assertEquals(48_720, RateTrack.rates.last())
        RateTrack.head = 23_000 // 1,000 playable frames left; no new PCM write.
        maintain()
        assertEquals(48_000, RateTrack.rates.last())
        assertEquals(0.0, MediaClockTrim.learnedTrim, 1e-8)
    }

    @Test fun rendererAccountsOnlyForQueuedArrivalsSinceLastSampleAndIgnoresTimestampResets() {
        val packet=Class.forName("com.shilapi.xcertplay.media.AudioRenderer\$AudioPacket")
            .declaredConstructors.single().apply {isAccessible=true}
        @Suppress("UNCHECKED_CAST")
        val queue=get("queue") as java.util.concurrent.LinkedBlockingQueue<Any>
        queue.add(packet.newInstance(ByteArray(12),0,900_000_000L))
        queue.add(packet.newInstance(ByteArray(12),1024,1_800_000_000L))
        // A concurrent producer can append after the consumer's snapshot time.
        queue.add(packet.newInstance(ByteArray(12),2048,2_100_000_000L))
        queue.add(packet.newInstance(ByteArray(12),1_000_000,1_900_000_000L))
        val method=type.getDeclaredMethod("queuedFrameNsSince",Long::class.javaPrimitiveType,Long::class.javaPrimitiveType)
            .apply {isAccessible=true}
        assertEquals(1024.0*200_000_000L,method.invoke(renderer,1_000_000_000L,2_000_000_000L) as Double,1.0)
        assertEquals(0.0,method.invoke(renderer,2_000_000_000L,2_000_000_000L) as Double,0.0)
    }

    @Test fun restartingPlaybackAndDiscardingPacketsRequireFreshCalibration() {
        val trim=MediaClockTrim(48_000,14_400,measuresTarget=true)
        fun calibrate() {
            trim.measureAgain()
            for(step in 0..40) trim.sample(24_000,1_000_000_000L+step*100_000_000L)
            assertTrue(!trim.calibrating)
        }
        set("clockTrim",trim)
        calibrate()
        type.getDeclaredMethod("startPlayback",AudioTrack::class.java).apply {isAccessible=true}.invoke(renderer,track)
        assertTrue(trim.calibrating)
        calibrate()
        (get("packetsDroppedTotal") as java.util.concurrent.atomic.AtomicInteger).incrementAndGet()
        type.getDeclaredMethod("trimClock",AudioTrack::class.java,Boolean::class.javaPrimitiveType)
            .apply {isAccessible=true}.invoke(renderer,track,false)
        assertTrue(trim.calibrating)
    }

    @Implements(AudioTrack::class)
    class RateTrack {
        @Implementation fun getPlaybackHeadPosition() = head
        @Implementation fun getUnderrunCount() = 0
        @Implementation fun play() = Unit
        @Implementation fun pause() = Unit
        @Implementation fun write(bytes: ByteArray, offset: Int, size: Int, mode: Int): Int = size
        @Implementation fun setPlaybackRate(rate: Int): Int {
            rates += rate
            return result
        }

        companion object {
            val rates = mutableListOf<Int>()
            var result = AudioTrack.SUCCESS
            var head = 0
        }
    }
}
