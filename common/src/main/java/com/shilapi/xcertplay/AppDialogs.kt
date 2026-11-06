package com.shilapi.xcertplay

import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.view.ContextThemeWrapper
import com.shilapi.xcertplay.host.R

internal interface AppAppearanceOwner {
    val currentAppNight: Boolean
}

internal fun Context.appDialogTheme(): Int = if (
    (this as? AppAppearanceOwner)?.currentAppNight ?: resolveAppNightNow()
) {
    R.style.Theme_Xcertplay_Dialog_Dark
} else {
    R.style.Theme_Xcertplay_Dialog_Light
}

internal fun Context.appDialogContext(): Context = ContextThemeWrapper(this, appDialogTheme())

internal fun Context.appDialogBuilder(): AlertDialog.Builder {
    val owner = this
    return object : AlertDialog.Builder(this, appDialogTheme()) {
        override fun create(): AlertDialog = super.create().apply {
            val palette = DiPlayPalette.of((owner as? AppAppearanceOwner)?.currentAppNight ?: owner.resolveAppNightNow())
            window?.setBackgroundDrawable(GlassDesign.surface(owner, palette.surface, palette.outline))
            window?.setDimAmount(.32f)
        }
    }
}

internal fun Context.appDialog(): Dialog = Dialog(this, appDialogTheme()).apply {
    val palette = DiPlayPalette.of((this@appDialog as? AppAppearanceOwner)?.currentAppNight ?: this@appDialog.resolveAppNightNow())
    window?.setBackgroundDrawable(GlassDesign.surface(this@appDialog, palette.surface, palette.outline))
    window?.setDimAmount(.32f)
}
