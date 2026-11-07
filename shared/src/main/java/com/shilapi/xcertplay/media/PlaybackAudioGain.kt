package com.shilapi.xcertplay.media

import android.content.Context
import kotlin.math.pow
import kotlin.math.roundToInt

/** Local playback trim, without touching the head unit's shared system stream volume. */
object PlaybackAudioGain {
    const val MIN_DB = -18
    const val MAX_DB = 6
    val presets = (MIN_DB..MAX_DB step 3).toList()
    private val gains = (MIN_DB..MAX_DB).map { 10.0.pow(it / 20.0).toFloat() }
    fun clamp(db: Int) = db.coerceIn(MIN_DB, MAX_DB)
    fun load(context: Context) = clamp(context.getSharedPreferences("diplay_playback", Context.MODE_PRIVATE).getInt("gain_db", 0))
    fun save(context: Context, db: Int) {
        context.getSharedPreferences("diplay_playback", Context.MODE_PRIVATE).edit().putInt("gain_db", clamp(db)).apply()
    }
    fun scalePcm16(data: ByteArray, offset: Int, length: Int, db: Int) {
        val bounded = clamp(db)
        if (bounded == 0) return
        val gain = gains[bounded - MIN_DB]
        val end = offset + (length and 1.inv())
        var i = offset
        while (i < end) {
            val sample = (data[i + 1].toInt() shl 8) or (data[i].toInt() and 0xff)
            val scaled = (sample * gain).roundToInt().coerceIn(-32768, 32767)
            data[i] = scaled.toByte()
            data[i + 1] = (scaled shr 8).toByte()
            i += 2
        }
    }
}
