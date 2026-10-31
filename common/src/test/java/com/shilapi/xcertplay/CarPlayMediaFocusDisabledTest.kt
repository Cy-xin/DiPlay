package com.shilapi.xcertplay

import android.media.session.MediaSession
import android.os.Looper
import com.shilapi.xcertplay.media.CarPlayNowPlaying
import com.shilapi.xcertplay.orchestration.CarPlayController
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers

/** An opt-in compatibility mode keeps media keys active without owning audio focus. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [25, 28, 33], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class CarPlayMediaFocusDisabledTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val controllers = mutableListOf<CarPlayController>()

    @Before fun disableMediaKeyFocus() {
        AirPlayPersistence.saveDisableMediaKeyAudioFocus(app, true)
    }

    @After fun cleanup() {
        controllers.forEach(CarPlayMediaKeys::detach)
        CarPlayBackgroundSession.clear()
        AirPlayPersistence.saveDisableMediaKeyAudioFocus(app, false)
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test fun mediaAudioCreatesActiveSessionWithoutFocusRequest() {
        val session = start(mock(CarPlayController::class.java).also(controllers::add))

        assertTrue(session.isActive)
        assertFocusDisabled()
    }

    @Test fun repeatedMediaActivityDoesNotRequestFocusOrReplaceSession() {
        val controller = mock(CarPlayController::class.java).also(controllers::add)
        val session = start(controller)

        CarPlayMediaKeys.onMediaAudioChanged(controller, true)
        shadowOf(Looper.getMainLooper()).idle()

        assertSame(session, currentSession())
        assertFocusDisabled()
    }

    @Test fun replacementControllerRetiresSessionWithoutClaimingFocus() {
        val first = mock(CarPlayController::class.java).also(controllers::add)
        val firstSession = start(first)
        val second = mock(CarPlayController::class.java).also(controllers::add)

        val secondSession = start(second)

        assertFalse(firstSession.isActive)
        assertNotSame(firstSession, secondSession)
        assertTrue(secondSession.isActive)
        assertFocusDisabled()
    }

    @Test fun detachReleasesSessionAndLeavesFocusUnowned() {
        val controller = mock(CarPlayController::class.java).also(controllers::add)
        val session = start(controller)

        CarPlayMediaKeys.detach(controller)
        shadowOf(Looper.getMainLooper()).idle()

        assertFalse(session.isActive)
        assertNull(currentSession())
        assertFocusDisabled()
    }

    @Test fun factoryRendererMustConfirmOwnershipBeforeClaimingTheOemSource() {
        val controller = mock(CarPlayController::class.java).also(controllers::add)
        start(controller)
        ReflectionHelpers.getField<GeelyMediaCardPublisher>(CarPlayMediaKeys, "geelyMediaCard").close()
        ReflectionHelpers.setField(CarPlayMediaKeys, "geelyMediaCard", GeelyMediaCardPublisher(app) { true })
        fun packets() = shadowOf(app).broadcastIntents.filter { it.action == GeelyMediaCardPublisher.ACTION }
        CarPlayMediaKeys.onMediaAudioChanged(controller, true)
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(4))
        assertTrue(packets().isEmpty())

        CarPlayMediaKeys.onAudioOwnershipChanged(controller, true)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(packets().last().getBooleanExtra("RECEIVER_MEDIA_APP_STATUS", false))
        CarPlayMediaKeys.onAudioOwnershipChanged(controller, false)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(2, packets().last().getIntExtra("RECEIVER_MEDIA_PLAY_STATUS", -1))
        val count = packets().size
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(6))
        assertEquals(count, packets().size)
    }

    @Test fun queuedOldSinkActivityAndOwnershipCannotClaimOrClearTheReplacementSource() {
        val first = mock(CarPlayController::class.java).also(controllers::add)
        start(first)
        CarPlayMediaKeys.onMediaAudioChanged(first, true)
        CarPlayMediaKeys.onAudioOwnershipChanged(first, true)
        val second = mock(CarPlayController::class.java).also(controllers::add)
        `when`(second.nowPlayingSnapshot()).thenReturn(CarPlayNowPlaying())
        CarPlayMediaKeys.attach(app, second)
        ReflectionHelpers.getField<GeelyMediaCardPublisher>(CarPlayMediaKeys, "geelyMediaCard").close()
        ReflectionHelpers.setField(CarPlayMediaKeys, "geelyMediaCard", GeelyMediaCardPublisher(app) { true })
        shadowOf(Looper.getMainLooper()).idle()
        assertNull(currentSession())
        fun packets() = shadowOf(app).broadcastIntents.filter { it.action == GeelyMediaCardPublisher.ACTION }
        assertTrue(packets().isEmpty())

        CarPlayMediaKeys.onMediaAudioChanged(second, true)
        CarPlayMediaKeys.onAudioOwnershipChanged(second, true)
        shadowOf(Looper.getMainLooper()).idle()
        val count = packets().size
        CarPlayMediaKeys.onMediaAudioChanged(first, false)
        CarPlayMediaKeys.onAudioOwnershipChanged(first, false)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(ReflectionHelpers.getField(CarPlayMediaKeys, "mediaAudioActive"))
        assertTrue(ReflectionHelpers.getField(CarPlayMediaKeys, "sinkOwnsAudio"))
        assertEquals(count, packets().size)
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(2))
        assertTrue(packets().size > count)
        assertTrue(packets().last().getBooleanExtra("RECEIVER_MEDIA_APP_STATUS", false))
    }

    private fun start(controller: CarPlayController): MediaSession {
        `when`(controller.nowPlayingSnapshot()).thenReturn(CarPlayNowPlaying())
        CarPlayMediaKeys.attach(app, controller)
        CarPlayMediaKeys.onMediaAudioChanged(controller, true)
        shadowOf(Looper.getMainLooper()).idle()
        assertFocusDisabled()
        return currentSession().also(::assertNotNull)!!
    }

    private fun currentSession(): MediaSession? =
        ReflectionHelpers.getField(CarPlayMediaKeys, "session")

    private fun assertFocusDisabled() {
        assertNull(ReflectionHelpers.getField<Any?>(CarPlayMediaKeys, "focusRequest"))
        assertFalse(ReflectionHelpers.getField(CarPlayMediaKeys, "focusHeld"))
        assertNull(ReflectionHelpers.getField<Any?>(CarPlayMediaKeys, "focusOwner"))
    }
}
