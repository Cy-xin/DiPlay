package com.shilapi.xcertplay

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.shilapi.xcertplay.media.CarPlayNowPlaying

/**
 * OneOS's media-card protocol, observed in the supplied GeelyCarPlay APK (hb1).
 * This complements MediaSession; it does not draw over or control the instrument display.
 * Relinquish the source when Android audio focus is lost.
 */
internal class GeelyMediaCardPublisher(
    context: Context,
    available: () -> Boolean = {
        runCatching {
            @Suppress("DEPRECATION")
            val info = context.packageManager.getReceiverInfo(RECEIVER, 0)
            info.enabled && info.exported && info.applicationInfo.enabled
        }.getOrDefault(false)
    },
) : AutoCloseable {
    private val context = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val supported by lazy(available)
    private var failed = false
    private var active = false
    private var closed = false
    private var nowPlaying = CarPlayNowPlaying()
    private var published: CarPlayNowPlaying? = null
    private var lastPublishedAt = 0L
    @Volatile var stage: String = "IDLE"
        private set

    private val heartbeat = object : Runnable {
        override fun run() {
            synchronized(this@GeelyMediaCardPublisher) {
                if (!active || closed) return
                if (publish(nowPlaying.playing)) handler.postDelayed(this, INTERVAL_MILLIS)
            }
        }
    }

    @Synchronized
    fun update(ownsMediaSource: Boolean, next: CarPlayNowPlaying) {
        if (closed || failed) return
        if (!supported) { stage = "RECEIVER_UNAVAILABLE"; return }
        val wasActive = active
        active = ownsMediaSource
        nowPlaying = next
        if (!active) {
            handler.removeCallbacks(heartbeat)
            if (wasActive) publish(false)
            stage = "RELEASED"
            return
        }
        val before = published
        val trackChanged = before != null && (before.title != next.title || before.artist != next.artist)
        val changed = before == null || before.title != next.title || before.artist != next.artist ||
            before.playing != next.playing || before.durationMillis != next.durationMillis
        if (!wasActive || changed || SystemClock.elapsedRealtime() - lastPublishedAt >= INTERVAL_MILLIS) {
            handler.removeCallbacks(heartbeat)
            // The observed receiver protocol refreshes a new track with a state edge first.
            if (trackChanged && !publish(!next.playing, announceSource = false)) return
            if (publish(next.playing)) handler.postDelayed(heartbeat, INTERVAL_MILLIS)
        }
    }

    /**
     * Answers an OEM position query without inventing state this app does not hold.
     * Does nothing unless the card currently owns the source, so a query arriving before
     * CarPlay connects is left unanswered instead of faking an online player.
     */
    @Synchronized
    fun publishNow() {
        if (closed || failed || !active) return
        // The vehicle queries every visible display, so several queries can arrive together.
        if (SystemClock.elapsedRealtime() - lastPublishedAt < MIN_REPLY_INTERVAL_MILLIS) return
        handler.removeCallbacks(heartbeat)
        if (publish(nowPlaying.playing)) handler.postDelayed(heartbeat, INTERVAL_MILLIS)
    }

    private fun publish(playing: Boolean, announceSource: Boolean = true): Boolean {
        val title = nowPlaying.title?.takeIf(String::isNotBlank) ?: "CarPlay"
        val artist = nowPlaying.artist?.takeIf(String::isNotBlank) ?: "iPhone"
        val trackId = ("$title\u0000$artist".hashCode().toLong() and 0xffffffffL)
        return try {
            context.sendBroadcast(Intent(ACTION).setComponent(RECEIVER)
                .putExtra("RECEIVER_MEDIA_BOOK_NAME", title)
                .putExtra("RECEIVER_MEDIA_BOOK_AUTHOR_NAME", artist)
                // Artwork continues through the existing Android MediaSession. Do not expose a
                // world-readable file or invent a public URL for this firmware-specific receiver.
                .putExtra("RECEIVER_MEDIA_BOOK_COVERURL", "")
                .putExtra("RECEIVER_MEDIA_BOOK_ID", trackId)
                .putExtra("RECEIVER_MEDIA_FRAGMENT_ID", trackId)
                .putExtra("RECEIVER_MEDIA_FAVORITE_STATUS", false)
                .putExtra("RECEIVER_MEDIA_TOTAL_DURATION", millis(nowPlaying.durationMillis))
                .putExtra("RECEIVER_MEDIA_CURRENT_POSITION", millis(nowPlaying.elapsedMillis))
                .putExtra("RECEIVER_MEDIA_PLAY_STATUS", if (playing) 3 else 2))
            if (active && announceSource) context.sendBroadcast(Intent(ACTION).setComponent(RECEIVER)
                .putExtra("RECEIVER_MEDIA_APP_STATUS", true))
            published = nowPlaying
            lastPublishedAt = SystemClock.elapsedRealtime()
            stage = if (active) "PUBLISHED_UNCONFIRMED" else "RELEASED"
            true
        } catch (error: RuntimeException) {
            // Delivery may be denied by OEM permissions; normal audio must keep working.
            failed = true
            stage = "PUBLISH_FAILED:${error.javaClass.simpleName}"
            false
        }
    }

    @Synchronized
    override fun close() {
        if (closed) return
        handler.removeCallbacks(heartbeat)
        val wasActive = active
        active = false
        if (wasActive) publish(false)
        closed = true
    }

    private fun millis(value: Long?): Int = (value ?: 0L).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()

    companion object {
        const val PACKAGE = "com.geely.online.service"
        const val ACTION = "ecarx.intent.broadcast.action.MEDIA_CONTROL_RECEIVER"
        val RECEIVER = ComponentName(PACKAGE, "com.media.control.MediaControlReceiver")
        private const val INTERVAL_MILLIS = 2_000L
        // The vehicle asks about every visible display at once; answer at most this often.
        private const val MIN_REPLY_INTERVAL_MILLIS = 250L
    }
}
