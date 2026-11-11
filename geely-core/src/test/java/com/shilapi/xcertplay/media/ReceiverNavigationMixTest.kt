package com.shilapi.xcertplay.media

import android.media.*
import android.os.SystemClock
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[30],manifest=Config.NONE,shadows=[ReceiverNavigationMixTest.TrackVolumes::class])
class ReceiverNavigationMixTest {
    private val context get()=RuntimeEnvironment.getApplication()
    private val attrs=AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
    private val tracks=mutableListOf<AudioTrack>()
    private val mixer by lazy {AudioFocusCoordinator(context,true,navigationMixEnabled=true)}
    @Before fun setup() {TrackVolumes.values.clear();Shadows.shadowOf(context.getSystemService(AudioManager::class.java)).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)}
    @After fun close() {mixer.close();tracks.forEach(AudioTrack::release);TrackVolumes.values.clear()}
    private fun track(channel:AudioChannel)=AudioTrack.Builder().setAudioAttributes(attrs).setAudioFormat(AudioFormat.Builder().setSampleRate(48000).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build()).setBufferSizeInBytes(4096).setTransferMode(AudioTrack.MODE_STREAM).build().also {tracks+=it;mixer.acquire(it,channel,attrs)}
    private fun volume(t:AudioTrack)=TrackVolumes.values[t]?.lastOrNull() ?: 1f
    @Test fun negotiatedNavigationDoesNotDuckUntilPcmArrivesAndNeverMutesMusic() {
        val music=track(AudioChannel.MEDIA);val nav=track(AudioChannel.NAVIGATION)
        assertEquals(1f,volume(music));mixer.onNavigationPlayback(nav,200)
        assertEquals(.2f,volume(music));assertEquals(1f,volume(nav))
        SystemClock.sleep(400);mixer.refreshSpeechPlayback();assertEquals(1f,volume(music))
    }
    @Test fun overlappingNavigationAndNewMediaInheritDuckUntilAllPromptsEnd() {
        val music=track(AudioChannel.MEDIA);val first=track(AudioChannel.NAVIGATION);val second=track(AudioChannel.NAVIGATION)
        mixer.onNavigationPlayback(first,100);mixer.onNavigationPlayback(second,800)
        val late=track(AudioChannel.MEDIA);assertEquals(.2f,volume(late))
        mixer.release(first);assertEquals(.2f,volume(music));mixer.release(second)
        assertEquals(1f,volume(music));assertEquals(1f,volume(late))
    }
    @Test fun callsAndSiriStillInterruptAndResumeToNavigationDuckNotFullVolume() {
        val music=track(AudioChannel.MEDIA);val nav=track(AudioChannel.NAVIGATION);val voice=track(AudioChannel.ASSISTANT)
        mixer.onNavigationPlayback(nav,2000);mixer.onSpeechPlayback(voice,200)
        assertEquals(0f,volume(music));assertEquals(1f,volume(voice))
        mixer.release(voice);assertEquals(.2f,volume(music));mixer.release(nav);assertEquals(1f,volume(music))
    }
    @Implements(AudioTrack::class) class TrackVolumes {
        @RealObject private lateinit var real:AudioTrack
        @Implementation fun setStereoVolume(left:Float,right:Float):Int {values.getOrPut(real){mutableListOf()}.add(left);return AudioTrack.SUCCESS}
        @Implementation fun setVolume(value:Float):Int {values.getOrPut(real){mutableListOf()}.add(value);return AudioTrack.SUCCESS}
        companion object {val values=linkedMapOf<AudioTrack,MutableList<Float>>()}
    }
}
