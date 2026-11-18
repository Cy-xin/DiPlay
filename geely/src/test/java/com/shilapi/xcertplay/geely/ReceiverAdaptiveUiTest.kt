package com.shilapi.xcertplay.geely

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Recomposer
import androidx.compose.ui.platform.ComposeView
import kotlinx.coroutines.*
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowBuild
import java.io.File
import java.time.Duration
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[30])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReceiverAdaptiveUiTest {
    private var drawFrame:(()->Unit)?=null
    private var clock:BroadcastFrameClock?=null
    private var frameNanos=0L
    private fun settle() {repeat(25) {Snapshot.sendApplyNotifications();frameNanos+=40_000_000L;clock?.sendFrame(frameNanos);Thread.sleep(40);Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(40));drawFrame?.invoke()}}
    private fun nodes(view:View):List<SemanticsNode> {
        val owner=view.javaClass.methods.firstOrNull {it.name=="getSemanticsOwner" && it.parameterCount==0}?.invoke(view) as? SemanticsOwner
        if(owner!=null) {
            fun flatten(node:SemanticsNode):List<SemanticsNode> = listOf(node)+node.children.flatMap {flatten(it)}
            return flatten(owner.unmergedRootSemanticsNode)
        }
        return if(view is ViewGroup) (0 until view.childCount).flatMap {nodes(view.getChildAt(it))} else emptyList()
    }
    private fun tag(view:View,tag:String):SemanticsNode {
        fun views(v:View):String = v.javaClass.name+" methods="+v.javaClass.methods.filter {it.name.contains("Semantics")}.joinToString {it.name}+if(v is ViewGroup) (0 until v.childCount).joinToString {views(v.getChildAt(it))} else ""
        return nodes(view).firstOrNull {it.config.getOrNull(SemanticsProperties.TestTag)==tag} ?: throw AssertionError("Missing $tag; nodes="+nodes(view).joinToString {it.config.toString()}+" views="+views(view))
    }
    private fun text(view:View)=nodes(view).flatMap {it.config.getOrNull(SemanticsProperties.Text).orEmpty()}.joinToString(" ") {it.text}
    private fun click(node:SemanticsNode) {assertTrue(node.config[SemanticsActions.OnClick].action!!.invoke());settle()}
    private fun save(view:View,name:String,width:Int,height:Int) {
        val bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888)
        try {
            view.draw(Canvas(bitmap))
            val pixels=IntArray(width*height);bitmap.getPixels(pixels,0,width,0,0,width,height)
            assertTrue(pixels.toSet().size>30)
            val file=File("build/reports/receiver-ui/adaptive/$name.png");file.parentFile?.mkdirs()
            file.outputStream().use {assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,it))}
        } finally {bitmap.recycle()}
    }
    private fun render(width:Int,height:Int,scale:Float,dark:Boolean,rtl:Boolean=false,car:Boolean=false,highDensity:Boolean=false,body:(GeelyActivity,View)->Unit) {
        val densityFactor=if(highDensity) 2 else 1
        RuntimeEnvironment.setQualifiers("w${width/densityFactor}dp-h${height/densityFactor}dp-${if(highDensity) "xhdpi" else "mdpi"}")
        ShadowBuild.setModel(if(car) "KX11_A2" else "Redmi K40")
        val app=RuntimeEnvironment.getApplication()
        val config=Configuration(app.resources.configuration).apply {
            fontScale=scale;uiMode=(uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or (if(dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO)
            val locale=Locale.forLanguageTag(if(rtl) "ar-SA" else "zh-CN");setLocale(locale);setLayoutDirection(locale)
        }
        @Suppress("DEPRECATION") app.resources.updateConfiguration(config,app.resources.displayMetrics)
        val lifecycle=Robolectric.buildActivity(GeelyActivity::class.java).setup().visible()
        var frameBitmap:Bitmap?=null
        val frames=BroadcastFrameClock();clock=frames;frameNanos=0L
        val recomposer=Recomposer(Dispatchers.Unconfined+frames)
        val rendering=CoroutineScope(Dispatchers.Unconfined+frames).launch {recomposer.runRecomposeAndApplyChanges()}
        try {
            val view=lifecycle.get().window.decorView
            view.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY));view.layout(0,0,width,height)
            val compose=ComposeView(lifecycle.get()).apply {setParentCompositionContext(recomposer)}
            lifecycle.get().setContentView(compose)
            compose.setContent {
                val density=LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density*ReceiverTokens.vehicleScale(android.os.Build.MODEL),density.fontScale)) {
                    val mode=if(dark) ReceiverThemeMode.DARK else ReceiverThemeMode.LIGHT
                    ReceiverTheme(mode) {ReceiverPages(lifecycle.get().ui,mode,false,{},{},{},{},{},{},{},{},{})}
                }
            }
            val warmup=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);frameBitmap=warmup
            drawFrame={view.draw(Canvas(warmup))}
            settle()
            body(lifecycle.get(),view)
        } finally {drawFrame=null;clock=null;recomposer.cancel();rendering.cancel();frameBitmap?.recycle();lifecycle.pause().stop().destroy()}
    }
    @Test fun primaryRemainsReachableAcrossStatesThemesSizesAndFontScales() {
        for((width,height) in listOf(420 to 900,1280 to 720)) for(scale in listOf(1f,1.5f)) for(dark in listOf(false,true)) {
            render(width,height,scale,dark) {activity,view ->
                for(state in listOf("idle","connecting","connected","failed")) {
                    val ui=activity.ui;ui.busy=state=="connecting";ui.connected=state=="connected";ui.showCarPlay=false;ui.attempted=state=="failed"
                    ui.message=when(state) {"failed"->"连接失败，请检查设备是否处于同一网络、是否存在客户端隔离。".repeat(12)+"末尾说明：可重试。";"connecting"->"正在等待 iPhone 连接…";"connected"->"CarPlay 已连接。可返回画面。";else->"未连接。请先连接网络并配对蓝牙。"}
                    settle()
                    val primary=tag(view,"connection-primary").boundsInRoot
                    assertTrue("$width/$scale/$state: $primary",primary.height>=48f && primary.width>=48f && primary.top>=0 && primary.bottom<=height && primary.left>=0 && primary.right<=width)
                    val label=when(state) {"failed"->"重试连接";"connecting"->"取消连接";"connected"->"返回 CarPlay";else->"连接 CarPlay"}
                    assertTrue("Expected $label in "+text(view),text(view).contains(label))
                    if(state=="failed") {
                        click(tag(view,"status-expand"))
                        val node=tag(view,"connection-message")
                        assertTrue(node.boundsInRoot.height<=120*scale+2)
                        assertTrue(node.config[SemanticsProperties.Text].joinToString {it.text}.endsWith("末尾说明：可重试。"))
                        assertTrue(node.config.contains(SemanticsActions.ScrollBy))
                    }
                    save(view,"$width-${scale}-${if(dark) "dark" else "light"}-$state",width,height)
                }
            }
        }
    }
    @Test fun carDensityShortLandscapeRtlAndSettingsSearchExposeHiddenTestsWithoutChangingPreferences() {
        for((width,height,rtl,car,highDensity) in listOf(Case(1920,720,false,true),Case(1280,720,true,false),Case(720,420,false,false),Case(420,900,false,false),Case(720,1280,false,false,true))) {
            render(width,height,1.5f,false,rtl,car,highDensity) {activity,view ->
                val ui=activity.ui
                assertTrue(tag(view,"connection-primary").boundsInRoot.bottom<=height)
                save(view,"extra-$width-$height-$rtl-$car",width,height)
                ui.settingsOpen=true;ui.settingsGroup=ReceiverSettingsGroup.PLAYBACK;settle()
                assertFalse(text(view).contains("测试播放 5 秒"));save(view,"settings-$width-$rtl-$car",width,height)
                ui.settingsQuery="导航";settle()
                assertTrue("Missing audio tests in "+text(view),text(view).contains("测试播放 5 秒"));assertFalse(ui.audioRoutingExpanded)
                save(view,"search-audio-$width-$rtl-$car",width,height)
                assertTrue(ui.dismissTopLayer());settle();assertTrue(ui.settingsOpen);assertEquals("",ui.settingsQuery)
                assertFalse(text(view).contains("测试播放 5 秒"))
                ui.settingsQuery="实体按键";settle();assertTrue(text(view).contains("检测实体按键"));assertFalse(ui.wheelTestsExpanded)
                assertEquals(ReceiverWheelMapping.defaults,ui.wheelMappings);assertEquals(200,ui.bufferMs)
                ui.settingsQuery="日志";settle();assertTrue(text(view).contains("诊断与日志"))
                ui.settingsQuery="no-such-setting";settle();assertTrue(text(view).contains("未找到设置"))
            }
        }
    }
    @Test fun actualCompositedGlassBackgroundKeepsNormalTextContrastInBothThemes() {
        for(dark in listOf(false,true)) render(1280,720,1f,dark) {activity,view ->
            activity.setContent {ReceiverTheme(if(dark) ReceiverThemeMode.DARK else ReceiverThemeMode.LIGHT) {ReceiverScene(Modifier.fillMaxSize()) {GlassSurface(Modifier.fillMaxSize()) {}}}}
            settle();save(view,if(dark) "contrast-dark" else "contrast-light",1280,720)
            val bitmap=Bitmap.createBitmap(1280,720,Bitmap.Config.ARGB_8888)
            try {
                view.draw(Canvas(bitmap))
                val ink=if(dark) intArrayOf(184,198,218) else intArrayOf(75,89,108)
                fun luminance(r:Int,g:Int,b:Int):Double {
                    fun channel(x:Int):Double {val v=x/255.0;return if(v<=.04045) v/12.92 else Math.pow((v+.055)/1.055,2.4)}
                    return .2126*channel(r)+.7152*channel(g)+.0722*channel(b)
                }
                val l=luminance(ink[0],ink[1],ink[2]);var minimum=Double.MAX_VALUE
                for(y in 40 until 680 step 4) for(x in 40 until 1240 step 4) {
                    val c=bitmap.getPixel(x,y);val bg=luminance(android.graphics.Color.red(c),android.graphics.Color.green(c),android.graphics.Color.blue(c))
                    minimum=minOf(minimum,(maxOf(l,bg)+.05)/(minOf(l,bg)+.05))
                }
                assertTrue("Composite contrast dark=$dark: $minimum",minimum>=4.5)
                File("build/reports/receiver-ui/adaptive/contrast-${if(dark) "dark" else "light"}.txt").writeText("onSurfaceVariant minimum=$minimum; API30, 1280x720, final rendered panel pixels (40px inset), step4px")
            } finally {bitmap.recycle()}
        }
    }
    private data class Case(val width:Int,val height:Int,val rtl:Boolean,val car:Boolean,val highDensity:Boolean=false)
}
