package com.shilapi.xcertplay

import com.shilapi.xcertplay.GeelyCompatibilityMediaControls.Command
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The compatibility contract is fixed by this vehicle's OnlineMusicService, so these tests pin the
 * two things that would silently break it: responding to a package the OEM never calls, and
 * acknowledging a command the app does not actually perform.
 */
class GeelyCompatibilityMediaControlsTest {

    private val action = GeelyCompatibilityMediaControls.ACTION
    private val pkg = GeelyCompatibilityMediaControls.COMPATIBILITY_PACKAGE

    private fun resolve(control: String?, packageName: String = pkg, intentAction: String? = action) =
        GeelyCompatibilityMediaControls.resolveControl(packageName, intentAction, control)

    @Test
    fun `four media keys map to their CarPlay presses`() {
        assertEquals(Command.PAUSE, resolve("MEDIA_CONTROL_PAUSE"))
        assertEquals(Command.PLAY, resolve("MEDIA_CONTROL_PLAY"))
        assertEquals(Command.NEXT, resolve("MEDIA_CONTROL_SKIP_TO_NEXT"))
        assertEquals(Command.PREVIOUS, resolve("MEDIA_CONTROL_SKIP_TO_PREVIOUS"))
    }

    @Test
    fun `position query is answered as the heartbeat`() {
        assertEquals(Command.REQUEST_UPDATE, resolve("MEDIA_CONTROL_CURRENT_POSITION"))
    }

    @Test
    fun `open app is distinct from a media key`() {
        assertEquals(Command.OPEN_APP, resolve("MEDIA_CONTROL_OPEN_APP"))
    }

    @Test
    fun `unsupported commands are not acknowledged`() {
        for (command in listOf(
            "MEDIA_CONTROL_FAVORITE",
            "MEDIA_CONTROL_SEEK",
            "MEDIA_CONTROL_PLAY_BOOK",
            "MEDIA_CONTROL_PLAY_STATUS",
            "",
        )) {
            assertNull("$command must not be acknowledged", resolve(command))
        }
        assertNull("a missing control extra must be ignored", resolve(null))
    }

    @Test
    fun `the ordinary DiPlay package ignores the OEM contract`() {
        assertNull(resolve("MEDIA_CONTROL_PAUSE", packageName = "com.shihab.diplay"))
        assertNull(resolve("MEDIA_CONTROL_CURRENT_POSITION", packageName = "com.shihab.diplay"))
    }

    @Test
    fun `the hud test package ignores the OEM contract`() {
        // A debug build that kept its .hudtest suffix must never answer the OEM contract: the
        // vehicle addresses io.dushu.car exactly, so acting here would be unreachable code that
        // still runs if the suffix is ever reintroduced by accident.
        assertNull(resolve("MEDIA_CONTROL_PAUSE", packageName = "io.dushu.car.hudtest"))
    }

    @Test
    fun `a different action on the contract receiver is ignored`() {
        assertNull(resolve("MEDIA_CONTROL_PAUSE", intentAction = "android.intent.action.SOMETHING"))
        assertNull(resolve("MEDIA_CONTROL_PAUSE", intentAction = null))
    }

    @Test
    fun `trampoline is recognised by MAIN and OPEN_PLAY`() {
        assertTrue(
            GeelyCompatibilityMediaControls.resolveTrampoline(
                pkg,
                GeelyCompatibilityMediaControls.MAIN_ACTION,
                GeelyCompatibilityMediaControls.OPEN_PLAY,
            ),
        )
    }

    @Test
    fun `trampoline ignores other types, actions and packages`() {
        val main = GeelyCompatibilityMediaControls.MAIN_ACTION
        val openPlay = GeelyCompatibilityMediaControls.OPEN_PLAY
        assertFalse(GeelyCompatibilityMediaControls.resolveTrampoline(pkg, main, "OPEN_ONLY"))
        assertFalse(GeelyCompatibilityMediaControls.resolveTrampoline(pkg, main, null))
        assertFalse(GeelyCompatibilityMediaControls.resolveTrampoline(pkg, action, openPlay))
        assertFalse(GeelyCompatibilityMediaControls.resolveTrampoline("com.shihab.diplay", main, openPlay))
        assertFalse(GeelyCompatibilityMediaControls.resolveTrampoline("io.dushu.car.hudtest", main, openPlay))
    }
}
