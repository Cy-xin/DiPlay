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

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(descendants(view.getChildAt(i)))
    }
}
