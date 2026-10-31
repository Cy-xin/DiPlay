package com.shilapi.xcertplay

import android.content.Context
import android.content.Intent
import android.util.Log
import com.shilapi.xcertplay.airplay.CarPlayMediaButton

/**
 * Fixed control contract read from this vehicle's OnlineMusicService MediaPlayHelper.
 *
 * The component names live in `io.dushu.car`, so nothing here is reachable from the ordinary
 * `com.shihab.diplay` build: the manifest keeps both components disabled unless the compatibility
 * build is explicitly selected, and both entry points re-check the installed package below.
 *
 * The decision itself is a pure function of (package, action, extra). Keeping it free of Context
 * makes the contract testable without an Android runtime, and makes it impossible for a future
 * edit to "helpfully" widen what the OEM package responds to without a test failing.
 */
internal object GeelyCompatibilityMediaControls {
    const val COMPATIBILITY_PACKAGE = "io.dushu.car"
    const val ACTION = "ecarx.intent.broadcast.action.MEDIA_CONTROL_RECEIVER"
    const val MAIN_ACTION = "android.intent.action.MAIN"
    const val CONTROL = "RECEIVER_MEDIA_CONTROL"
    const val INTENT_TYPE = "INTENT_TYPE"
    const val OPEN_PLAY = "OPEN_PLAY"

    /** What the OEM asked for, or null when this contract does not cover the request. */
    enum class Command { PLAY, PAUSE, NEXT, PREVIOUS, REQUEST_UPDATE, OPEN_APP }

    /**
     * Maps one broadcast to the press or refresh it means. Anything unrecognised returns null and
     * is deliberately left unacknowledged: favorite, book IDs and seek positions have no mapping
     * to the iPhone library, and claiming them would report work the app did not do.
     */
    fun resolveControl(packageName: String, action: String?, control: String?): Command? {
        if (packageName != COMPATIBILITY_PACKAGE || action != ACTION) return null
        return when (control) {
            "MEDIA_CONTROL_PLAY" -> Command.PLAY
            "MEDIA_CONTROL_PAUSE" -> Command.PAUSE
            "MEDIA_CONTROL_SKIP_TO_NEXT" -> Command.NEXT
            "MEDIA_CONTROL_SKIP_TO_PREVIOUS" -> Command.PREVIOUS
            "MEDIA_CONTROL_OPEN_APP" -> Command.OPEN_APP
            // The card asks this every ~0.7 s while visible and drops the source after ~4 s of
            // silence, so answering it is the heartbeat rather than an optimisation.
            "MEDIA_CONTROL_CURRENT_POSITION" -> Command.REQUEST_UPDATE
            else -> null
        }
    }

    /** Whether this launch is the OEM card's trampoline rather than an ordinary start. */
    fun resolveTrampoline(packageName: String, action: String?, type: String?): Boolean =
        packageName == COMPATIBILITY_PACKAGE && action == MAIN_ACTION && type == OPEN_PLAY

    fun receive(context: Context, intent: Intent) {
        val control = runCatching { intent.getStringExtra(CONTROL) }.getOrNull()
        when (resolveControl(context.packageName, intent.action, control)) {
            Command.PLAY -> CarPlayMediaKeys.onExternalMediaKey(CarPlayMediaButton.PLAY)
            Command.PAUSE -> CarPlayMediaKeys.onExternalMediaKey(CarPlayMediaButton.PAUSE)
            Command.NEXT -> CarPlayMediaKeys.onExternalMediaKey(CarPlayMediaButton.NEXT)
            Command.PREVIOUS -> CarPlayMediaKeys.onExternalMediaKey(CarPlayMediaButton.PREVIOUS)
            Command.REQUEST_UPDATE -> CarPlayMediaKeys.requestGeelyMediaCardUpdate()
            Command.OPEN_APP -> launch(context)
            null -> Unit
        }
    }

    /**
     * The OEM card's play button. This vehicle never broadcasts PLAY: it starts the trampoline with
     * MAIN + OPEN_PLAY instead, so the press has to become a play request here.
     *
     * The intent also carries OEM book IDs and a position. Those are echoes of what this app
     * published, not iPhone library identifiers, so they are ignored.
     */
    fun openFromFactory(context: Context, intent: Intent) {
        val type = runCatching { intent.getStringExtra(INTENT_TYPE) }.getOrNull()
        if (!resolveTrampoline(context.packageName, intent.action, type)) return
        if (opensAppWindow(CarPlayMediaKeys.onGeelyCardPlayRequest())) launch(context)
    }

    /**
     * Whether the trampoline should also bring the app's own window forward.
     *
     * While CarPlay is connected the app is already projecting full-screen behind the card, so
     * starting DiPlayActivity covers the projection and then drops back once the window is
     * replaced — on screen that reads as the display jumping away and returning on every press.
     * The press is a transport control, so it is served without a window. Only when there is no
     * session to play through does the window do something useful: it is where the phone gets
     * connected.
     */
    fun opensAppWindow(playServed: Boolean): Boolean = !playServed

    /**
     * @return true when the launch was handed to the system. This vehicle's firmware silently
     * refuses background activity starts from a broadcast receiver, so a true return is not proof
     * that a window appeared; the trampoline activity is the path that actually works.
     */
    private fun launch(context: Context): Boolean {
        val launch = Intent(context, DiPlayActivity::class.java).apply {
            action = MAIN_ACTION
            addCategory(Intent.CATEGORY_LAUNCHER)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        return runCatching { context.startActivity(launch) }.onFailure {
            Log.w("DiPlayFactoryMedia", "App open failed: ${it.javaClass.simpleName}")
        }.isSuccess
    }
}
