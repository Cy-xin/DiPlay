package com.shilapi.xcertplay.media

import com.shilapi.xcertplay.airplay.AudioCodecKind
import com.shilapi.xcertplay.airplay.AudioFormat
import com.shilapi.xcertplay.airplay.AudioStreamId
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class PlaybackAudioGainTest {
    @Test fun boostClipsSafelyInsteadOfWrappingTheSample() {
        val pcm = samples(20000, -20000, 1000, -1000)
        PlaybackAudioGain.scalePcm16(pcm, 0, pcm.size, 6)
        assertArrayEquals(samples(32767, -32768, 1995, -1995), pcm)
    }
    @Test fun attenuationAndZeroOnlyAffectTheRequestedCompleteSamples() {
        val original = samples(20000, -20000, 1234) + byteArrayOf(99)
        val pcm = original.copyOf()
        PlaybackAudioGain.scalePcm16(pcm, 2, 3, -6)
        assertArrayEquals(samples(20000, -10024, 1234) + byteArrayOf(99), pcm)
        PlaybackAudioGain.scalePcm16(original, 0, original.size, 0)
        assertArrayEquals(samples(20000, -20000, 1234) + byteArrayOf(99), original)
    }
    @Test fun savedGainReachesNewAndExistingRenderersWithoutReconnect() {
        val context = RuntimeEnvironment.getApplication()
        PlaybackAudioGain.save(context, -6)
        val sink = AndroidMediaSink(context = context)
        try {
            val create = AndroidMediaSink::class.java.getDeclaredMethod("audioRenderer", AudioStreamId::class.java, AudioFormat::class.java).apply { isAccessible = true }
            val renderer = create.invoke(sink, AudioStreamId(96, "media"), AudioFormat(AudioCodecKind.LPCM, 48000, 2, 96, "media"))
            val field = renderer.javaClass.getDeclaredField("playbackGainDb").apply { isAccessible = true }
            assertEquals(-6, field.getInt(renderer))
            sink.setPlaybackGainDb(3)
            assertEquals(3, field.getInt(renderer))
            assertEquals(-6, PlaybackAudioGain.load(context)) // Applying does not silently overwrite user settings.
        } finally { sink.close(); PlaybackAudioGain.save(context, 0) }
    }
    private fun samples(vararg values: Int): ByteArray = ByteArray(values.size * 2).also { data ->
        values.forEachIndexed { index, value -> data[index * 2] = value.toByte(); data[index * 2 + 1] = (value shr 8).toByte() }
    }
}
