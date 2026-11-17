package com.shilapi.xcertplay.geely

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Duration
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[30])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReceiverConnectionScreenshotsTest {
    @Test fun connectionAtLargeFontsCompactWideAndRtl() {
        for((name,width,height,language) in listOf(
            Case("compact-large",420,900,"zh-CN"),Case("wide-large",1280,720,"zh-CN"),Case("wide-rtl-large",1280,720,"ar-SA")
        )) {
            RuntimeEnvironment.setQualifiers("w${width}dp-h${height}dp-mdpi")
            val app=RuntimeEnvironment.getApplication()
            val config=Configuration(app.resources.configuration).apply {fontScale=1.5f;val locale=Locale.forLanguageTag(language);setLocale(locale);setLayoutDirection(locale)}
            @Suppress("DEPRECATION") app.resources.updateConfiguration(config,app.resources.displayMetrics)
            val controller=Robolectric.buildActivity(GeelyActivity::class.java).setup().visible()
            try {
                val decor=controller.get().window.decorView
                decor.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY))
                decor.layout(0,0,width,height)
                // DataStore initializes on IO; advancing only the main clock leaves the loading surface.
                repeat(25) {
                    Thread.sleep(40)
                    Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(40))
                }
                val image=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888)
                try {
                    decor.draw(Canvas(image))
                    val pixels=IntArray(width*height);image.getPixels(pixels,0,width,0,0,width,height)
                    assertTrue("UI must contain rendered content",pixels.toSet().size>30)
                    val file=File("build/reports/receiver-ui/$name.png");file.parentFile.mkdirs()
                    file.outputStream().use {assertTrue(image.compress(Bitmap.CompressFormat.PNG,100,it))}
                } finally {image.recycle()}
            } finally {controller.pause().stop().destroy()}
        }
    }
    private data class Case(val name:String,val width:Int,val height:Int,val language:String)
}
