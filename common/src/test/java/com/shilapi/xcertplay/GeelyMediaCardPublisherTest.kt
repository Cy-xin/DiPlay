package com.shilapi.xcertplay

import android.content.Context
import android.os.Looper
import com.shilapi.xcertplay.media.CarPlayNowPlaying
import java.time.Duration
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class GeelyMediaCardPublisherTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private var publisher: GeelyMediaCardPublisher? = null
    private val broadcasts get() = shadowOf(app).broadcastIntents.filter { it.action == GeelyMediaCardPublisher.ACTION }
    private val songs get() = broadcasts.filter { it.hasExtra("RECEIVER_MEDIA_BOOK_NAME") }
    private val track = CarPlayNowPlaying(title = "Song", artist = "Artist", durationMillis = 180_000,
        elapsedMillis = 12_000, playing = true)

    @After fun cleanup() { publisher?.close(); shadowOf(Looper.getMainLooper()).idle() }

    @Test fun missingFirmwareReceiverDoesNotPublishOrScheduleBackgroundWork() {
        publisher = GeelyMediaCardPublisher(app)
        publisher!!.update(true, track)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(10))
        assertTrue(broadcasts.isEmpty())
        assertEquals("RECEIVER_UNAVAILABLE", publisher!!.stage)
    }

    @Test fun publishesExplicitTypedSongPacketAndOnlyAnnouncesWhileItOwnsTheSource() {
        val output = GeelyMediaCardPublisher(app) { true }.also { publisher = it }
        output.update(true, track)
        val song = songs.single()
        assertEquals(GeelyMediaCardPublisher.RECEIVER, song.component)
        assertEquals("Song", song.getStringExtra("RECEIVER_MEDIA_BOOK_NAME"))
        assertEquals("Artist", song.getStringExtra("RECEIVER_MEDIA_BOOK_AUTHOR_NAME"))
        assertEquals(180_000, song.getIntExtra("RECEIVER_MEDIA_TOTAL_DURATION", -1))
        assertEquals(12_000, song.getIntExtra("RECEIVER_MEDIA_CURRENT_POSITION", -1))
        assertEquals(3, song.getIntExtra("RECEIVER_MEDIA_PLAY_STATUS", -1))
        assertTrue(song.extras!!.get("RECEIVER_MEDIA_BOOK_ID") is Long)
        assertEquals(song.getLongExtra("RECEIVER_MEDIA_BOOK_ID", -1), song.getLongExtra("RECEIVER_MEDIA_FRAGMENT_ID", -2))
        assertTrue(broadcasts.last().getBooleanExtra("RECEIVER_MEDIA_APP_STATUS", false))
        assertEquals("PUBLISHED_UNCONFIRMED", output.stage)
    }

    @Test fun positionUpdatesAreBoundedButTrackAndPauseChangesAreImmediate() {
        val output = GeelyMediaCardPublisher(app) { true }.also { publisher = it }
        output.update(true, track)
        repeat(20) { output.update(true, track.copy(elapsedMillis = 12_000L + it)) }
        assertEquals(1, songs.size)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        assertEquals(2, songs.size)
        assertEquals(12_019, songs.last().getIntExtra("RECEIVER_MEDIA_CURRENT_POSITION", -1))
        output.update(true, track.copy(title = "Next"))
        assertEquals(listOf(2, 3), songs.takeLast(2).map { it.getIntExtra("RECEIVER_MEDIA_PLAY_STATUS", -1) })
        assertTrue(songs.takeLast(2).all { it.getStringExtra("RECEIVER_MEDIA_BOOK_NAME") == "Next" })
        output.update(true, track.copy(title = "Next", playing = false))
        assertEquals(2, songs.last().getIntExtra("RECEIVER_MEDIA_PLAY_STATUS", -1))
    }

    @Test fun lossAndClosePublishOnePauseAndNeverKeepReassertingTheOldSource() {
        val output = GeelyMediaCardPublisher(app) { true }.also { publisher = it }
        output.update(true, track)
        output.update(false, track)
        assertFalse(broadcasts.last().hasExtra("RECEIVER_MEDIA_APP_STATUS"))
        assertEquals(2, songs.last().getIntExtra("RECEIVER_MEDIA_PLAY_STATUS", -1))
        val count = broadcasts.size
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(10))
        output.close(); output.close(); output.update(true, track)
        assertEquals(count, broadcasts.size)
    }

    @Test fun disconnectStopsEvenWithoutAnEarlierFocusLossAndClampsWireIntegers() {
        val output = GeelyMediaCardPublisher(app) { true }.also { publisher = it }
        output.update(true, track.copy(durationMillis = Long.MAX_VALUE, elapsedMillis = -1))
        assertEquals(Int.MAX_VALUE, songs.last().getIntExtra("RECEIVER_MEDIA_TOTAL_DURATION", -1))
        assertEquals(0, songs.last().getIntExtra("RECEIVER_MEDIA_CURRENT_POSITION", -1))
        output.close()
        val count = broadcasts.size
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(10))
        assertEquals(count, broadcasts.size)
        assertEquals(2, songs.last().getIntExtra("RECEIVER_MEDIA_PLAY_STATUS", -1))
    }

    @Test fun deniedOemBroadcastDoesNotBreakPlaybackOrRetryEveryTwoSeconds() {
        val context = mock(Context::class.java)
        `when`(context.applicationContext).thenReturn(context)
        doThrow(SecurityException("OEM denied")).`when`(context).sendBroadcast(any())
        val output = GeelyMediaCardPublisher(context) { true }.also { publisher = it }
        output.update(true, track)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(10))
        assertEquals("PUBLISH_FAILED:SecurityException", output.stage)
        verify(context, times(1)).sendBroadcast(any())
    }

    @Test fun positionQueryIsAnsweredWithAFullStateAndSourceAnnouncement() {
        val output = GeelyMediaCardPublisher(app) { true }.also { publisher = it }
        output.update(true, track)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300))
        val before = songs.size
        output.publishNow()
        assertEquals(before + 1, songs.size)
        // The vehicle decides the source is gone when the announcement stops, so every answer
        // carries it rather than only the periodic heartbeat.
        assertTrue(broadcasts.last().getBooleanExtra("RECEIVER_MEDIA_APP_STATUS", false))
        assertEquals(3, songs.last().getIntExtra("RECEIVER_MEDIA_PLAY_STATUS", -1))
    }

    @Test fun positionQueryBeforeOwningTheSourceIsNotAnswered() {
        val output = GeelyMediaCardPublisher(app) { true }.also { publisher = it }
        // Never updated: CarPlay is not connected and this app has no state to report. Answering
        // here would claim an online player that cannot play anything.
        output.publishNow()
        assertTrue(broadcasts.isEmpty())
        assertEquals("IDLE", output.stage)
    }

    @Test fun repeatedQueriesWithinOneDisplayBurstCollapseToOneAnswer() {
        val output = GeelyMediaCardPublisher(app) { true }.also { publisher = it }
        output.update(true, track)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300))
        val before = songs.size
        // The vehicle asks about every visible display at once.
        repeat(5) { output.publishNow() }
        assertEquals(before + 1, songs.size)
        // ...and a later burst is answered again.
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300))
        output.publishNow()
        assertEquals(before + 2, songs.size)
    }

    @Test fun closingTheSourceSilencesFurtherQueries() {
        val output = GeelyMediaCardPublisher(app) { true }.also { publisher = it }
        output.update(true, track)
        output.update(false, track)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300))
        val count = broadcasts.size
        output.publishNow()
        assertEquals(count, broadcasts.size)
    }
}
