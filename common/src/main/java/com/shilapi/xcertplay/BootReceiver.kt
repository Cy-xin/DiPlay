package com.shilapi.xcertplay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.shilapi.xcertplay.telecom.PhoneAudioRoute

/** Opens DiPlay after boot or its update only when the user enabled startup. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        // Starts the watcher for other apps' calls when that setting is on, even without opening DiPlay.
        PhoneAudioRoute.attach(context)
        val launchEnabled = AirPlayPersistence.loadAutoStartOnBoot(context)
        StartupDiagnosticSnapshot.received(context, launchEnabled, intent.action == Intent.ACTION_MY_PACKAGE_REPLACED)
        if (!launchEnabled) return

        val launch = Intent(context, DiPlayActivity::class.java).apply {
            putExtra(EXTRA_AUTOMATIC_START, true)
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP,
            )
        }
        try {
            context.startActivity(launch)
            StartupDiagnosticSnapshot.launchResult(context)
        } catch (error: RuntimeException) {
            StartupDiagnosticSnapshot.launchResult(context, error)
            Log.w(TAG, "Boot auto-start could not launch DiPlayActivity", error)
        }
    }

    companion object {
        internal const val EXTRA_AUTOMATIC_START = "com.shihab.diplay.AUTOMATIC_START"
        private const val TAG = "xcertplay-boot"
    }
}
