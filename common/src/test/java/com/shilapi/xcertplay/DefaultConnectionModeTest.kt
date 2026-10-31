package com.shilapi.xcertplay

import android.app.AlertDialog
import android.content.Intent
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 33], qualifiers = "en", manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class DefaultConnectionModeTest {
    private val app get() = RuntimeEnvironment.getApplication()

    @Before fun clearPreferences() {
        app.getSharedPreferences("diplay", 0).edit().clear().commit()
        app.getSharedPreferences("xcertplay_airplay", 0).edit().clear().commit()
        CarPlayBackgroundSession.clear()
    }

    @After fun cleanup() {
        CarPlayBackgroundSession.clear()
    }

    @Test fun initialLaunchUsesTheFixedDefaultInsteadOfTheLastManualTransport() {
        DiPlayPreferences.saveAutoConnect(app, true)
        DiPlayPreferences.savePhone(app, "00:11:22:33:44:55", "Test iPhone")
        AirPlayPersistence.saveWirelessHotspotMode(app, WirelessHotspotMode.WIFI_P2P)
        app.getSharedPreferences("diplay", 0).edit().putBoolean("notification_asked", true).commit()
        for (mode in listOf(DefaultConnectionMode.USB, DefaultConnectionMode.WIRELESS)) {
            val expectedWireless = mode == DefaultConnectionMode.WIRELESS
            DiPlayPreferences.saveDefaultConnectionMode(app, mode)
            AirPlayPersistence.saveWirelessEnabled(app, !expectedWireless)
            val controller = Robolectric.buildActivity(DiPlayActivity::class.java, Intent(Intent.ACTION_MAIN)).create()
            val activity = controller.get()
            // Source-only test builds have no authentication assets; exercise the startup decision.
            ReflectionHelpers.setField(activity, "setupError", null)
            controller.start().resume()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(expectedWireless, AirPlayPersistence.loadWirelessEnabled(app))
            assertEquals(CarPlayHostActivity::class.java.name,
                shadowOf(activity).nextStartedActivity?.component?.className)
            assertEquals(mode, DiPlayPreferences.defaultConnectionMode(app))
            controller.pause().stop().destroy()
        }
    }

    @Test fun openingSettingsDoesNotAutoConnectOrChangeTheLastTransport() {
        DiPlayPreferences.saveAutoConnect(app, true)
        DiPlayPreferences.saveDefaultConnectionMode(app, DefaultConnectionMode.USB)
        AirPlayPersistence.saveWirelessEnabled(app, true)
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java,
            Intent().putExtra("page", "settings")).create()
        val activity = controller.get()
        ReflectionHelpers.setField(activity, "setupError", null)
        controller.start().resume()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(AirPlayPersistence.loadWirelessEnabled(app))
        assertNull(shadowOf(activity).nextStartedActivity)
        controller.pause().stop().destroy()
    }

    @Test fun automaticStartDeliveredToExistingHomeHonoursBothOptInsAndConfiguredTransport() {
        app.getSharedPreferences("diplay", 0).edit().putBoolean("notification_asked", true).commit()
        DiPlayPreferences.savePhone(app, "00:11:22:33:44:55", "Test iPhone")
        DiPlayPreferences.saveDefaultConnectionMode(app, DefaultConnectionMode.USB)
        AirPlayPersistence.saveWirelessEnabled(app, true)
        AirPlayPersistence.saveWirelessHotspotMode(app, WirelessHotspotMode.WIFI_P2P)
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java, Intent(Intent.ACTION_MAIN)).create()
        val activity = controller.get()
        ReflectionHelpers.setField(activity, "setupError", null)
        controller.start().resume()
        shadowOf(Looper.getMainLooper()).idle()
        fun deliver(intent: Intent) {
            ReflectionHelpers.callInstanceMethod<Unit>(activity, "onNewIntent",
                ReflectionHelpers.ClassParameter(Intent::class.java, intent))
            shadowOf(Looper.getMainLooper()).idle()
        }
        fun automatic() = Intent().putExtra(BootReceiver.EXTRA_AUTOMATIC_START, true)
        try {
            DiPlayPreferences.saveAutoConnect(app, true)
            deliver(automatic())
            assertNull(shadowOf(activity).nextStartedActivity)
            DiPlayPreferences.saveAutoConnect(app, false)
            AirPlayPersistence.saveAutoStartOnBoot(app, true)
            val disabled = automatic()
            deliver(disabled)
            assertFalse(disabled.hasExtra(BootReceiver.EXTRA_AUTOMATIC_START))
            assertNull(shadowOf(activity).nextStartedActivity)
            assertTrue(AirPlayPersistence.loadWirelessEnabled(app))

            DiPlayPreferences.saveAutoConnect(app, true)
            deliver(automatic().putExtra("page", "settings"))
            assertNull(shadowOf(activity).nextStartedActivity)
            assertTrue(AirPlayPersistence.loadWirelessEnabled(app))

            val activeSession: ((() -> Unit) -> Unit) = { done -> done() }
            ReflectionHelpers.setField(CarPlayBackgroundSession, "stopAction", activeSession)
            deliver(automatic())
            assertNull(shadowOf(activity).nextStartedActivity)
            assertTrue(CarPlayBackgroundSession.hasSession())
            CarPlayBackgroundSession.clear()

            deliver(automatic())
            assertEquals(CarPlayHostActivity::class.java.name,
                shadowOf(activity).nextStartedActivity?.component?.className)
            assertFalse(AirPlayPersistence.loadWirelessEnabled(app))
            assertEquals(DefaultConnectionMode.USB, DiPlayPreferences.defaultConnectionMode(app))
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test fun queuedAutomaticStartRechecksUserChoicesSessionAndPageBeforeConnecting() {
        app.getSharedPreferences("diplay", 0).edit().putBoolean("notification_asked", true).commit()
        DiPlayPreferences.savePhone(app, "00:11:22:33:44:55", "Test iPhone")
        DiPlayPreferences.saveDefaultConnectionMode(app, DefaultConnectionMode.USB)
        AirPlayPersistence.saveWirelessEnabled(app, true)
        AirPlayPersistence.saveWirelessHotspotMode(app, WirelessHotspotMode.WIFI_P2P)
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java, Intent(Intent.ACTION_MAIN)).create()
        val activity = controller.get()
        ReflectionHelpers.setField(activity, "setupError", null)
        controller.start().resume()
        shadowOf(Looper.getMainLooper()).idle()
        var stops = 0
        val activeSession: ((() -> Unit) -> Unit) = { done -> stops++; done() }
        val changes = listOf<() -> Unit>(
            { AirPlayPersistence.saveAutoStartOnBoot(app, false) },
            { DiPlayPreferences.saveAutoConnect(app, false) },
            { ReflectionHelpers.setField(CarPlayBackgroundSession, "stopAction", activeSession) },
            { ReflectionHelpers.setField(activity, "page", "settings") },
            { ReflectionHelpers.setField(activity, "page", "setup") },
            { ReflectionHelpers.setField(activity, "setupError", "Authentication unavailable") },
            { activity.finish() },
        )
        try {
            for (change in changes) {
                AirPlayPersistence.saveAutoStartOnBoot(app, true)
                DiPlayPreferences.saveAutoConnect(app, true)
                ReflectionHelpers.setField(activity, "page", "home")
                ReflectionHelpers.setField(activity, "setupError", null)
                val request = Intent().putExtra(BootReceiver.EXTRA_AUTOMATIC_START, true)
                // Keep the main looper paused until after the startup decision has changed.
                ReflectionHelpers.callInstanceMethod<Unit>(activity, "onNewIntent",
                    ReflectionHelpers.ClassParameter(Intent::class.java, request))
                assertFalse(request.hasExtra(BootReceiver.EXTRA_AUTOMATIC_START))
                change()
                shadowOf(Looper.getMainLooper()).idle()
                assertNull(shadowOf(activity).nextStartedActivity)
                assertTrue(AirPlayPersistence.loadWirelessEnabled(app))
                assertEquals(0, stops)
                CarPlayBackgroundSession.clear()
            }
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test fun existingInstallationsKeepLastUsedBehaviorAndUnknownValuesFallBack() {
        for (lastWireless in listOf(false, true)) {
            AirPlayPersistence.saveWirelessEnabled(app, lastWireless)
            assertEquals(lastWireless, DiPlayPreferences.autoConnectWireless(app))
            app.getSharedPreferences("diplay", 0).edit()
                .putString("default_connection_mode", "future_mode").commit()
            assertEquals(DefaultConnectionMode.LAST_USED, DiPlayPreferences.defaultConnectionMode(app))
            assertEquals(lastWireless, DiPlayPreferences.autoConnectWireless(app))
        }
    }

    @Test fun fixedDefaultSurvivesManualTransportChangesWithoutChangingAutoConnectOrPhone() {
        DiPlayPreferences.savePhone(app, "00:11:22:33:44:55", "Test iPhone")
        val expectedModes = mapOf(
            DefaultConnectionMode.LAST_USED to listOf(false, true),
            DefaultConnectionMode.WIRELESS to listOf(true, true),
            DefaultConnectionMode.USB to listOf(false, false),
        )
        for ((mode, expectedWireless) in expectedModes) {
            DiPlayPreferences.saveDefaultConnectionMode(app, mode)
            assertEquals(mode, DiPlayPreferences.defaultConnectionMode(app))
            for ((index, lastWireless) in listOf(false, true).withIndex()) {
                AirPlayPersistence.saveWirelessEnabled(app, lastWireless)
                assertEquals(expectedWireless[index], DiPlayPreferences.autoConnectWireless(app))
                assertEquals(lastWireless, AirPlayPersistence.loadWirelessEnabled(app))
            }
            assertFalse(DiPlayPreferences.autoConnect(app))
            assertEquals("00:11:22:33:44:55", DiPlayPreferences.phoneAddress(app))
        }
    }

    @Test fun settingsSavePersistsChoiceAndCancelKeepsSavedDefault() {
        val activity = Robolectric.buildActivity(DiPlayActivity::class.java).get()
        activity.setTheme(android.R.style.Theme_Material_NoActionBar)
        val controls = LinearLayout(activity)
        DiPlayActivity::class.java.getDeclaredMethod("connectionSettings", LinearLayout::class.java)
            .apply { isAccessible = true }.invoke(activity, controls)
        fun descendants(view: View): List<View> = listOf(view) +
            if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
        val button = descendants(controls).filterIsInstance<Button>().single {
            it.text.toString().startsWith(activity.getString(R.string.default_connection_mode) + " · ")
        }
        button.performClick()
        var dialog = ShadowAlertDialog.getLatestAlertDialog()
        dialog.listView.performItemClick(null, 2, 0)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(DefaultConnectionMode.USB, DiPlayPreferences.defaultConnectionMode(app))
        assertTrue(button.text.toString().endsWith(activity.getString(R.string.default_connection_usb)))
        button.performClick()
        dialog = ShadowAlertDialog.getLatestAlertDialog()
        dialog.listView.performItemClick(null, 1, 0)
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(DefaultConnectionMode.USB, DiPlayPreferences.defaultConnectionMode(app))
        assertFalse(DiPlayPreferences.autoConnect(app))
    }
}
