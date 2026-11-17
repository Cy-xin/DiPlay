package com.shilapi.xcertplay.media

import android.media.*
import android.os.Looper
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[30],manifest=Config.NONE,shadows=[ReceiverNavigationMixTest.TrackVolumes::class])
class ReceiverNavigationFadeTest {
    @Test fun guidanceFadesBothWaysButPrioritySpeechMutesImmediatelyAndCloseStopsAnimation() {
        val context=RuntimeEnvironment.getApplication()
        Shadows.shadowOf(context.getSystemService(AudioManager::class.java)).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        val attrs=AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
        val mixer=AudioFocusCoordinator(context,true,navigationMixEnabled=true,navigationDuckVolume=.5f,navigationFadeMillis=250)
        val tracks=mutableListOf<AudioTrack>()
        fun track(channel:AudioChannel)=AudioTrack.Builder().setAudioAttributes(attrs).setAudioFormat(AudioFormat.Builder().setSampleRate(48000).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build()).setBufferSizeInBytes(4096).build().also {tracks+=it;mixer.acquire(it,channel,attrs)}
        fun volume(t:AudioTrack)=ReceiverNavigationMixTest.TrackVolumes.values[t]?.lastOrNull() ?: 1f
        val main=Shadows.shadowOf(Looper.getMainLooper())
        try {
            val media=track(AudioChannel.MEDIA);val nav=track(AudioChannel.NAVIGATION)
            mixer.onNavigationPlayback(nav,2000);assertEquals(1f,volume(media),.001f)
            main.idleFor(Duration.ofMillis(128));assertTrue(volume(media) in .51f.. .99f)
            main.idleFor(Duration.ofMillis(160));assertEquals(.5f,volume(media),.001f)
            mixer.release(nav);main.idleFor(Duration.ofMillis(240));assertTrue(volume(media) in .51f.. .99f)
            main.idleFor(Duration.ofMillis(300));assertEquals(1f,volume(media),.001f)
            val voice=track(AudioChannel.ASSISTANT);mixer.onSpeechPlayback(voice,1000);assertEquals(0f,volume(media),.001f)
            mixer.close();val count=ReceiverNavigationMixTest.TrackVolumes.values[media]?.size
            main.idleFor(Duration.ofSeconds(2));assertEquals(count,ReceiverNavigationMixTest.TrackVolumes.values[media]?.size)
        } finally {mixer.close();tracks.forEach(AudioTrack::release);ReceiverNavigationMixTest.TrackVolumes.values.clear()}
    }
    @Test fun calibrationDoesNotAdoptExistingOneSecondCompressedBacklogAsTarget() {
        MediaClockTrim.learnedTrim=0.0
        val trim=MediaClockTrim(48000,14400,measuresTarget=true,maxMeasuredTargetFrames=33600)
        repeat(100) {n->trim.sample(14400,n*100_000_000L,compressedFrames=48000);trim.rateDue(n*100_000_000L)}
        assertFalse(trim.calibrating);assertTrue(trim.targetLevelFrames<=33600)
        assertTrue(trim.rate>=48000)
        MediaClockTrim.learnedTrim=0.0
    }
}
