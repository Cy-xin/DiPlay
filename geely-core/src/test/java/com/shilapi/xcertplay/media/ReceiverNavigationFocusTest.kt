package com.shilapi.xcertplay.media

import android.media.AudioAttributes
import android.media.AudioManager
import android.media.AudioTrack
import android.os.SystemClock
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[30],manifest=Config.NONE,shadows=[ReceiverNavigationMixTest.TrackVolumes::class])
class ReceiverNavigationFocusTest {
    private val context get()=RuntimeEnvironment.getApplication()
    private val manager get()=context.getSystemService(AudioManager::class.java)
    private val tracks=mutableListOf<AudioTrack>()
    private val reports=mutableListOf<String>()
    private val mixer by lazy {AudioFocusCoordinator(context,true,report=reports::add,
        navigationMixEnabled=true,navigationFocusEnabled=true,navigationDuckVolume=.5f)}
    @Before fun setup() {
        ReceiverNavigationMixTest.TrackVolumes.values.clear()
        shadowOf(manager).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
    }
    @After fun close() {mixer.close();tracks.forEach(AudioTrack::release);ReceiverNavigationMixTest.TrackVolumes.values.clear()}
    private fun track(channel:AudioChannel,usage:Int=when(channel) {
        AudioChannel.NAVIGATION->AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE
        AudioChannel.ASSISTANT->AudioAttributes.USAGE_ASSISTANT
        else->AudioAttributes.USAGE_MEDIA
    }):AudioTrack {
        val attrs=AudioAttributes.Builder().setUsage(usage).setContentType(
            if(channel==AudioChannel.MEDIA) AudioAttributes.CONTENT_TYPE_MUSIC else AudioAttributes.CONTENT_TYPE_SPEECH).build()
        return AudioTrack.Builder().setAudioAttributes(attrs).setAudioFormat(android.media.AudioFormat.Builder()
            .setSampleRate(48000).setEncoding(android.media.AudioFormat.ENCODING_PCM_16BIT)
            .setChannelMask(android.media.AudioFormat.CHANNEL_OUT_STEREO).build())
            .setBufferSizeInBytes(4096).build().also {tracks+=it;mixer.acquire(it,channel,attrs)}
    }
    private fun volume(track:AudioTrack)=ReceiverNavigationMixTest.TrackVolumes.values[track]?.lastOrNull() ?: 1f

    private fun navigationListener():AudioManager.OnAudioFocusChangeListener {
        val request=AudioFocusCoordinator::class.java.getDeclaredField("navigationRequest")
            .apply {isAccessible=true}.get(mixer)!!
        return request.javaClass.getDeclaredField("listener").apply {isAccessible=true}.get(request)
            as AudioManager.OnAudioFocusChangeListener
    }

    @Test fun navigationFocusFollowsActualPromptAndReleasesBackToMusic() {
        val music=track(AudioChannel.MEDIA);val nav=track(AudioChannel.NAVIGATION)
        assertEquals(AudioAttributes.USAGE_MEDIA,shadowOf(manager).lastAudioFocusRequest.audioFocusRequest.audioAttributes.usage)
        mixer.onNavigationPlayback(nav,200)
        val requested=shadowOf(manager).lastAudioFocusRequest
        val retiredListener=navigationListener()
        assertEquals(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE,requested.audioFocusRequest.audioAttributes.usage)
        assertEquals(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK,requested.audioFocusRequest.focusGain)
        assertEquals(.5f,volume(music));assertEquals(1f,volume(nav))
        mixer.onNavigationPlayback(nav,200)
        assertSame(requested,shadowOf(manager).lastAudioFocusRequest)
        SystemClock.sleep(400);mixer.refreshSpeechPlayback()
        assertSame(requested.audioFocusRequest,shadowOf(manager).lastAbandonedAudioFocusRequest)
        assertEquals(1f,volume(music))
        // Delayed callbacks from the retired prompt cannot touch music or request focus.
        retiredListener.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN)
        assertSame(requested,shadowOf(manager).lastAudioFocusRequest)
        assertEquals(1f,volume(music))
    }

    @Test fun systemDuckingAndLocalGuidanceDoNotMultiplyIntoNearSilence() {
        val music=track(AudioChannel.MEDIA);val nav=track(AudioChannel.NAVIGATION)
        mixer.onNavigationPlayback(nav,2000)
        mixer.listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK)
        assertEquals(.2f,volume(music)) // Not 0.2 * 0.5.
        mixer.listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN)
        assertEquals(.5f,volume(music))
        mixer.release(nav);assertEquals(1f,volume(music))
    }

    @Test fun siriStillMutesAndNavigationResumesAfterSpeechEnds() {
        val music=track(AudioChannel.MEDIA);val nav=track(AudioChannel.NAVIGATION)
        mixer.onNavigationPlayback(nav,2000)
        val old=shadowOf(manager).lastAudioFocusRequest.audioFocusRequest
        val siri=track(AudioChannel.ASSISTANT);mixer.onSpeechPlayback(siri,200)
        assertEquals(0f,volume(music));assertSame(old,shadowOf(manager).lastAbandonedAudioFocusRequest)
        mixer.release(siri)
        assertEquals(.5f,volume(music))
        assertNotSame(old,shadowOf(manager).lastAudioFocusRequest.audioFocusRequest)
    }

    @Test fun deniedFocusDoesNotMuteOrRetryForEveryPcmPacket() {
        val music=track(AudioChannel.MEDIA);val nav=track(AudioChannel.NAVIGATION)
        shadowOf(manager).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_FAILED)
        mixer.onNavigationPlayback(nav,100)
        val denied=shadowOf(manager).lastAudioFocusRequest
        repeat(20) {mixer.onNavigationPlayback(nav,100);mixer.refreshSpeechPlayback()}
        assertSame(denied,shadowOf(manager).lastAudioFocusRequest)
        assertEquals(.5f,volume(music))
        assertEquals(1,reports.count {it.startsWith("Audio: navigation focus requested")})
    }

    @Test fun externalCallAndRemappedMediaUsageDoNotAcquireNavigationFocus() {
        val music=track(AudioChannel.MEDIA)
        val remapped=track(AudioChannel.NAVIGATION,AudioAttributes.USAGE_MEDIA)
        mixer.onNavigationPlayback(remapped,200)
        assertEquals(AudioAttributes.USAGE_MEDIA,shadowOf(manager).lastAudioFocusRequest.audioFocusRequest.audioAttributes.usage)
        mixer.release(remapped)
        mixer.setExternalCall(true)
        val nav=track(AudioChannel.NAVIGATION);mixer.onNavigationPlayback(nav,200)
        assertEquals(0f,volume(music))
        assertFalse(reports.any {it.startsWith("Audio: navigation focus requested")})
    }

    @Test fun anotherOwnerTakingNavigationFocusDoesNotCauseARequestLoop() {
        track(AudioChannel.MEDIA);val nav=track(AudioChannel.NAVIGATION)
        mixer.onNavigationPlayback(nav,1000)
        val requested=shadowOf(manager).lastAudioFocusRequest
        navigationListener().onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        repeat(20) {mixer.onNavigationPlayback(nav,1000);mixer.refreshSpeechPlayback()}
        assertEquals(1,reports.count {it.startsWith("Audio: navigation focus requested")})
        SystemClock.sleep(1300);mixer.refreshSpeechPlayback()
        mixer.onNavigationPlayback(nav,100)
        assertEquals(2,reports.count {it.startsWith("Audio: navigation focus requested")})
    }
}
