package com.shilapi.xcertplay

import android.view.View
import android.view.ViewGroup
import com.shilapi.xcertplay.host.R
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "zh-w1280dp-h800dp")
class PrimaryNavigationTest {
    @Test fun primaryTabsPreserveTheVehicleDetailReturnPath() {
        val activity = Robolectric.buildActivity(DiPlayActivity::class.java).get()
        activity.setTheme(android.R.style.Theme_Material_NoActionBar)
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "render")
        fun page() = ReflectionHelpers.getField<String>(activity, "page")
        fun tab(destination: String) = activity.window.decorView.findViewWithTag<View>("primary-navigation:$destination")
        try {
            tab("vehicle").performClick()
            assertEquals("vehicle", page())
            assertTrue(tab("vehicle").isSelected)
            descendants(activity.window.decorView).single {
                it.contentDescription == activity.getString(R.string.projection_editor)
            }.performClick()
            assertEquals("vehicle-hud", page())
            assertTrue(tab("vehicle").isSelected)
            ReflectionHelpers.callInstanceMethod<Unit>(activity, "navigateBack")
            assertEquals("vehicle", page())
            tab("settings").performClick()
            assertEquals("settings", page())
            assertTrue(tab("settings").isSelected)
            tab("home").performClick()
            assertEquals("home", page())
            assertTrue(tab("home").isSelected)
        } finally { activity.finish() }
    }

    @Test fun commonShortcutsReturnToTheirOriginatingTab() {
        val activity = Robolectric.buildActivity(DiPlayActivity::class.java).get()
        activity.setTheme(android.R.style.Theme_Material_NoActionBar)
        try {
            for (origin in listOf("home", "vehicle")) {
                for ((title, destination) in listOf(R.string.connection to "connection", R.string.settings_quick_settings to "quick-settings")) {
                    ReflectionHelpers.setField(activity, "page", origin)
                    ReflectionHelpers.callInstanceMethod<Unit>(activity, "render")
                    descendants(activity.window.decorView).single {
                        it.contentDescription == activity.getString(title)
                    }.performClick()
                    assertEquals(destination, ReflectionHelpers.getField<String>(activity, "page"))
                    assertTrue(activity.window.decorView.findViewWithTag<View>("primary-navigation:$origin").isSelected)
                    ReflectionHelpers.callInstanceMethod<Unit>(activity, "navigateBack")
                    assertEquals(origin, ReflectionHelpers.getField<String>(activity, "page"))
                }
                assertEquals(1, descendants(activity.window.decorView).count {
                    it.contentDescription == activity.getString(R.string.open_car_hotspot_settings)
                })
            }
        } finally { activity.finish() }
    }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(descendants(view.getChildAt(i)))
    }
}
