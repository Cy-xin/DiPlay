package com.shilapi.xcertplay

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import java.io.File
import java.util.Locale
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers

/** Real native Android renders, including the narrow/large-type and RTL layouts. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class GlassUiScreenshotsTest {
    @Test fun capturesHomeSettingsAndSetupInBothAppearances() {
        for (night in listOf(false, true)) {
            for (page in listOf("home", "vehicle", "settings", "connection", "quick-settings", "backup")) {
                capture(page, night, 1280, 800, 1.3f, "zh", "${if (night) "dark" else "light"}-$page")
            }
        }
        capture("settings", false, 600, 960, 1.5f, "zh", "compact-settings-large-text")
        capture("settings", true, 600, 960, 1.5f, "ar", "compact-settings-rtl")
    }

    @Test fun capturesTheVehicleToolsAndGlassDialog() {
        for (night in listOf(false, true)) {
            RuntimeEnvironment.setQualifiers("zh-w1280dp-h900dp-mdpi")
            val app = RuntimeEnvironment.getApplication()
            AppLocale.save(app, "zh")
            AirPlayPersistence.saveAppAppearance(app, if (night) AppAppearance.DARK else AppAppearance.LIGHT)
            val types = listOf<Class<out android.app.Activity>>(
                SteeringControlsActivity::class.java, GeelyProjectionEditorActivity::class.java,
                VehicleMapProjectionActivity::class.java)
            for (type in types) {
                val controller = Robolectric.buildActivity(type).create()
                try {
                    val activity = controller.get()
                    val root = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
                    val width = (1280 * activity.resources.displayMetrics.density).toInt()
                    val height = (900 * activity.resources.displayMetrics.density).toInt()
                    root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
                    root.layout(0, 0, width, height)
                    save(root, "${if (night) "dark" else "light"}-${type.simpleName}")
                } finally { controller.destroy() }
            }
            val host = Robolectric.buildActivity(DiPlayActivity::class.java).get()
            host.setTheme(android.R.style.Theme_Material_NoActionBar)
            ReflectionHelpers.callInstanceMethod<Boolean>(host, "refreshAppearance")
            val dialog = host.appDialogBuilder().setTitle("CarPlay")
                .setMessage(host.getString(com.shilapi.xcertplay.host.R.string.choose_iphone))
                .setPositiveButton(android.R.string.ok, null).setNegativeButton(android.R.string.cancel, null).show()
            try {
                val root = dialog.window!!.decorView
                root.measure(View.MeasureSpec.makeMeasureSpec(760, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.AT_MOST))
                root.layout(0, 0, root.measuredWidth, root.measuredHeight)
                save(root, "${if (night) "dark" else "light"}-dialog")
            } finally { dialog.dismiss(); host.finish() }
        }
    }

    private fun save(view: View, name: String) {
        shadowOf(Looper.getMainLooper()).idle()
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        try {
            view.draw(Canvas(bitmap))
            File("build/reports/glass-ui/$name.png").apply { parentFile?.mkdirs() }
                .outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
    }

    private fun capture(page: String, night: Boolean, width: Int, height: Int, scale: Float, language: String, name: String) {
        RuntimeEnvironment.setQualifiers("$language-w${width}dp-h${height}dp-mdpi")
        val app = RuntimeEnvironment.getApplication()
        AppLocale.save(app, language)
        AirPlayPersistence.saveAppAppearance(app, if (night) AppAppearance.DARK else AppAppearance.LIGHT)
        SettingsLayoutPreferences.saveForceFull(app, width >= 850)
        val activity = Robolectric.buildActivity(DiPlayActivity::class.java).get()
        activity.setTheme(android.R.style.Theme_Material_NoActionBar)
        val locale = Locale.forLanguageTag(language)
        @Suppress("DEPRECATION")
        activity.resources.updateConfiguration(Configuration(activity.resources.configuration).apply {
            fontScale = scale; screenWidthDp = width; screenHeightDp = height
            setLocale(locale); setLayoutDirection(locale)
        }, activity.resources.displayMetrics)
        try {
            ReflectionHelpers.setField(activity, "page", page)
            ReflectionHelpers.callInstanceMethod<Unit>(activity, "render")
            val content = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
            val w = (width * activity.resources.displayMetrics.density).toInt()
            val h = (height * activity.resources.displayMetrics.density).toInt()
            content.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
            content.layout(0, 0, w, h)
            shadowOf(Looper.getMainLooper()).idle()
            // Status and focus callbacks can request another traversal, as on a real ViewRoot.
            content.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
            content.layout(0, 0, w, h)
            assertTrue("$name has usable controls", descendants(content).any { it.isClickable && it.width > 0 })
            assertTrue("$name keeps button labels", descendants(content).filterIsInstance<Button>().all { it.text.isNotBlank() || it.contentDescription != null })
            val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            try {
                content.draw(Canvas(bitmap))
                val out = File("build/reports/glass-ui/$name.png").apply { parentFile?.mkdirs() }
                out.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            } finally { bitmap.recycle() }
        } finally { activity.finish() }
    }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(descendants(view.getChildAt(i)))
    }
}
