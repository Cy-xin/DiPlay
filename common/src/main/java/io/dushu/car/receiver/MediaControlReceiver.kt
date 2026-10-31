package io.dushu.car.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.shilapi.xcertplay.GeelyCompatibilityMediaControls

/** The component name is fixed by the vehicle's OnlineMusicService. */
class MediaControlReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        GeelyCompatibilityMediaControls.receive(context, intent)
    }
}
