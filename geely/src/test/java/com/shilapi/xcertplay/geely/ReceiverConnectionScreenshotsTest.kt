package com.shilapi.xcertplay.geely

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.*
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
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
            Case("auto-compact-large",420,900,"zh-CN"),Case("auto-wide-large",1280,720,"zh-CN"),Case("auto-rtl-large",1280,720,"ar-SA"),
            Case("compact-large",420,900,"zh-CN"),Case("wide-large",1280,720,"zh-CN"),Case("wide-rtl-large",1280,720,"ar-SA"),
            Case("wheel-compact-large",420,900,"zh-CN"),Case("wheel-wide-large",1280,720,"zh-CN"),Case("wheel-rtl-large",1280,720,"ar-SA"),
            Case("picture-compact-light",420,900,"zh-CN"),Case("picture-wide-light",1280,720,"zh-CN"),Case("picture-rtl-light",1280,720,"ar-SA"),
            Case("picture-compact-dark",420,900,"zh-CN"),Case("picture-wide-dark",1280,720,"zh-CN"),Case("picture-rtl-dark",1280,720,"ar-SA")
        )) {
            RuntimeEnvironment.setQualifiers("w${width}dp-h${height}dp-mdpi")
            val app=RuntimeEnvironment.getApplication()
            val config=Configuration(app.resources.configuration).apply {fontScale=1.5f;uiMode=(uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or (if(name.endsWith("dark")) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO);val locale=Locale.forLanguageTag(language);setLocale(locale);setLayoutDirection(locale)}
            @Suppress("DEPRECATION") app.resources.updateConfiguration(config,app.resources.displayMetrics)
            val controller=Robolectric.buildActivity(GeelyActivity::class.java).setup().visible()
            try {
                if(name.startsWith("picture-")) {
                    controller.get().ui.connected=true;controller.get().ui.showCarPlay=true;controller.get().ui.pictureRestoreFailed=true
                }
                if(name.startsWith("auto-") || name.startsWith("wheel-")) controller.get().setContent {
                    ReceiverTheme(ReceiverThemeMode.SYSTEM) {
                        ReceiverScene(Modifier.fillMaxSize()) {GlassSurface(Modifier.fillMaxSize()) {
                            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                                if(name.startsWith("auto-")) AutoConnectSetting(controller.get().ui) else {Text("方向盘按键");ReceiverWheelSettings(controller.get().ui)}
                            }
                        }}
                    }
                }
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
