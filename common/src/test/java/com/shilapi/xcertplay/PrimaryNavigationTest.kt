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
    @Test fun twoTabsKeepVehicleToolsInsideSettings() {
        val activity = Robolectric.buildActivity(DiPlayActivity::class.java).get()
        activity.setTheme(android.R.style.Theme_Material_NoActionBar)
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "render")
        fun tab(destination: String) = activity.window.decorView.findViewWithTag<View>("primary-navigation:$destination")
        try {
            assertNull(tab("vehicle"))
            tab("settings").performClick()
            assertEquals("settings", ReflectionHelpers.getField<String>(activity, "page"))
            assertTrue(tab("settings").isSelected)
            assertEquals(2, descendants(activity.window.decorView).count { it.tag?.toString()?.startsWith("primary-navigation:") == true })
            val expected = listOf(
                R.string.vehicle_map_title to VehicleMapProjectionActivity::class.java,
                R.string.projection_editor to GeelyProjectionEditorActivity::class.java,
                R.string.steering_identification to SteeringControlsActivity::class.java,
            )
            for ((title, type) in expected) {
                descendants(activity.window.decorView).single {
                    it.contentDescription == activity.getString(title)
                }.performClick()
                assertEquals(type.name, org.robolectric.Shadows.shadowOf(activity).nextStartedActivity.component!!.className)
            }
            tab("home").performClick()
            assertEquals("home", ReflectionHelpers.getField<String>(activity, "page"))
        } finally { activity.finish() }
    }

    @Test fun homeShortcutsKeepTheirReturnPath() {
        val activity = Robolectric.buildActivity(DiPlayActivity::class.java).get()
        activity.setTheme(android.R.style.Theme_Material_NoActionBar)
        try {
            for ((title, destination) in listOf(R.string.connection to "connection", R.string.settings_quick_settings to "quick-settings")) {
                ReflectionHelpers.setField(activity, "page", "home")
                ReflectionHelpers.callInstanceMethod<Unit>(activity, "render")
                descendants(activity.window.decorView).single {
                    it.contentDescription == activity.getString(title)
                }.performClick()
                assertEquals(destination, ReflectionHelpers.getField<String>(activity, "page"))
                assertTrue(activity.window.decorView.findViewWithTag<View>("primary-navigation:home").isSelected)
                ReflectionHelpers.callInstanceMethod<Unit>(activity, "navigateBack")
                assertEquals("home", ReflectionHelpers.getField<String>(activity, "page"))
            }
            assertEquals(1, descendants(activity.window.decorView).count {
                it.contentDescription == activity.getString(R.string.open_car_hotspot_settings)
            })
        } finally { activity.finish() }
    }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(descendants(view.getChildAt(i)))
    }
}
