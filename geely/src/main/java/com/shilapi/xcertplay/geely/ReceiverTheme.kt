package com.shilapi.xcertplay.geely

import android.content.Context
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

internal enum class ReceiverThemeMode(val title:String) { SYSTEM("跟随系统"), LIGHT("浅色"), DARK("深色") }
private val Context.receiverAppearance by preferencesDataStore("receiver_appearance")
internal class ReceiverAppearance(context:Context) {
    private val data=context.applicationContext.receiverAppearance
    private val themeKey=stringPreferencesKey("theme")
    val mode=data.data.map { prefs ->
        ReceiverThemeMode.entries.firstOrNull { it.name==prefs[themeKey] } ?: ReceiverThemeMode.SYSTEM
    }.catch { if(it is IOException) emit(ReceiverThemeMode.SYSTEM) else throw it }
    suspend fun save(mode:ReceiverThemeMode) { data.edit { it[themeKey]=mode.name } }
}
internal object ReceiverTokens {
    fun vehicleScale(model:String)=if(model.contains("KX11",ignoreCase=true)) 1.35f else 1f
    val heroText=30.sp
    val titleText=24.sp
    val sectionText=18.sp
    val bodyText=16.sp
    val labelText=15.sp
    val secondaryText=14.sp
    val captionText=13.sp
    val metaText=12.sp
    val captionLine=18.sp
    val controlMin=56.dp
    val statusMax=120.dp
    val statusPreviewMax=64.dp
    val actionWidth=280.dp
    val icon=22.dp
    val panelElevation=4.dp
    val glassLightAlpha=.76f
    val glassDarkAlpha=.86f
    val gap=8.dp
    val page=20.dp
    val touch=48.dp
    val audioTestStatus=80.dp
    val pictureHintWidth=560.dp
    val pictureHintInlineWidth=400.dp
    val pictureHintElevation=2.dp
    val panel=RoundedCornerShape(28.dp)
    val control=RoundedCornerShape(20.dp)
    val light=lightColorScheme(background=Color(0xFFDCEBFA),surface=Color(0xFFF3F8FF),
        onBackground=Color(0xFF172434),onSurface=Color(0xFF172434),onSurfaceVariant=Color(0xFF4B596C),
        primary=Color(0xFF0060C9),onPrimary=Color.White,primaryContainer=Color(0xFFDCE8FF),
        onPrimaryContainer=Color(0xFF183F87),outline=Color(0xFF748398),outlineVariant=Color(0xFFCAD4E2))
    val dark=darkColorScheme(background=Color(0xFF111F33),surface=Color(0xFF202B3A),
        onBackground=Color(0xFFEDF3FC),onSurface=Color(0xFFEDF3FC),onSurfaceVariant=Color(0xFFB8C6DA),
        primary=Color(0xFF9FBEFF),onPrimary=Color(0xFF122A57),primaryContainer=Color(0xFF344C73),
        onPrimaryContainer=Color(0xFFE0EAFF),outline=Color(0xFF8394AC),outlineVariant=Color(0xFF43536A))
}
internal val LocalReceiverDark=staticCompositionLocalOf { false }

@Composable internal fun ReceiverTheme(mode:ReceiverThemeMode, content:@Composable ()->Unit) {
    val dark=when(mode) { ReceiverThemeMode.SYSTEM -> isSystemInDarkTheme();ReceiverThemeMode.DARK -> true;else -> false }
    val target=if(dark) ReceiverTokens.dark else ReceiverTokens.light
    // Color interpolation follows the system animator duration scale; no video surface is recreated.
    val bg by animateColorAsState(target.background,tween(220),label="background")
    val surface by animateColorAsState(target.surface,tween(220),label="surface")
    val ink by animateColorAsState(target.onSurface,tween(220),label="ink")
    CompositionLocalProvider(LocalReceiverDark provides dark) {
        MaterialTheme(colorScheme=target.copy(background=bg,surface=surface,onSurface=ink,onBackground=ink),
            typography=Typography()) {
            CompositionLocalProvider(LocalContentColor provides ink) { content() }
        }
    }
}
private fun DrawScope.drawScene(size:IntSize,dark:Boolean) {
    val w=size.width.toFloat().coerceAtLeast(1f);val h=size.height.toFloat().coerceAtLeast(1f)
    drawRect(Brush.linearGradient(if(dark) listOf(Color(0xFF172B46),Color(0xFF0F1D30),Color(0xFF244E72))
        else listOf(Color(0xFFD9ECFC),Color(0xFFBBDCF6),Color(0xFFE8F4FF)),end=Offset(w,h)),size=androidx.compose.ui.geometry.Size(w,h))
    drawCircle(Brush.radialGradient(listOf(Color(0xFF69BAFF).copy(alpha=if(dark) .25f else .40f),Color.Transparent),
        center=Offset(w*.88f,h*.18f),radius=w*.64f),radius=w*.64f,center=Offset(w*.88f,h*.18f))
    val ribbon=Path().apply {moveTo(-w*.1f,h*.58f);cubicTo(w*.3f,h*.42f,w*.58f,h*1.2f,w*1.1f,h*.58f);lineTo(w*1.1f,h*1.1f);lineTo(-w*.1f,h*1.1f);close()}
    drawPath(ribbon,Brush.linearGradient(listOf(Color.White.copy(alpha=if(dark) .035f else .36f),Color(0xFF64B8ED).copy(alpha=.14f)),end=Offset(w,h)))
    drawPath(ribbon,Color.White.copy(alpha=if(dark) .10f else .62f),style=Stroke(1.3f))
}
@Composable internal fun ReceiverScene(modifier:Modifier=Modifier,content:@Composable BoxScope.()->Unit) {
    val dark=LocalReceiverDark.current
    Box(modifier) {
        Canvas(Modifier.matchParentSize().clipToBounds()) { drawScene(IntSize(size.width.toInt(),size.height.toInt()),dark) }
        content()
    }
}
/** One scene behind all panels. Translucent tint never samples or blurs CarPlay video. */
@Composable internal fun GlassSurface(modifier:Modifier=Modifier,content:@Composable BoxScope.()->Unit) {
    val dark=LocalReceiverDark.current
    val shape=ReceiverTokens.panel
    val tint=MaterialTheme.colorScheme.surface
    val highlight=if(dark) Color.White.copy(alpha=.22f) else Color.White.copy(alpha=.86f)
    Box(modifier.shadow(ReceiverTokens.panelElevation,shape,ambientColor=Color(0xFF12508F).copy(alpha=.18f),spotColor=Color(0xFF12508F).copy(alpha=.18f))
        .clip(shape).background(tint.copy(alpha=if(dark) ReceiverTokens.glassDarkAlpha else ReceiverTokens.glassLightAlpha))
        .border(1.dp,highlight,shape)) {content()}
}
