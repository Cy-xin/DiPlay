package com.shilapi.xcertplay.geely

import android.app.Activity
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController

/** Mirrors upstream VehicleSystemBars' OEM immersive flags without importing vehicle-probe. */
internal object ReceiverSystemBars {
    @Suppress("DEPRECATION")
    fun apply(activity:Activity,hidden:Boolean) {
        val window=activity.window
        val appearance=window.decorView.systemUiVisibility and
            (View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR)
        var flags=View.SYSTEM_UI_FLAG_LAYOUT_STABLE or appearance
        if(hidden) flags=flags or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        window.decorView.systemUiVisibility=flags
        // Keep the existing edge-to-edge SurfaceView bounds stable; Compose consumes visible safe insets.
        window.setDecorFitsSystemWindows(false)
        window.insetsController?.apply {
            systemBarsBehavior=WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            if(hidden) hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
            else show(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
        }
    }
}
