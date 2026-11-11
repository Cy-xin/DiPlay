package com.shilapi.xcertplay.geely

import android.content.Context
import android.os.Build
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
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.IntSize
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
    val gap=8.dp
    val page=24.dp
    val touch=56.dp
    val panel=RoundedCornerShape(24.dp)
    val control=RoundedCornerShape(16.dp)
    val light=lightColorScheme(background=Color(0xFFEEF2F7),surface=Color(0xFFFAFCFF),
        onBackground=Color(0xFF172434),onSurface=Color(0xFF172434),onSurfaceVariant=Color(0xFF526174),
        primary=Color(0xFF2457C5),onPrimary=Color.White,primaryContainer=Color(0xFFDCE8FF),
        onPrimaryContainer=Color(0xFF183F87),outline=Color(0xFF748398),outlineVariant=Color(0xFFCAD4E2))
    val dark=darkColorScheme(background=Color(0xFF151D29),surface=Color(0xFF202B3A),
        onBackground=Color(0xFFEDF3FC),onSurface=Color(0xFFEDF3FC),onSurfaceVariant=Color(0xFFAAB8CC),
        primary=Color(0xFF9FBEFF),onPrimary=Color(0xFF122A57),primaryContainer=Color(0xFF344C73),
        onPrimaryContainer=Color(0xFFE0EAFF),outline=Color(0xFF8394AC),outlineVariant=Color(0xFF43536A))
}
internal val LocalReceiverDark=staticCompositionLocalOf { false }
private data class Backdrop(val size:IntSize=IntSize.Zero,val origin:Offset=Offset.Zero)
private val LocalBackdrop=compositionLocalOf { Backdrop() }

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
    drawRect(Brush.linearGradient(if(dark) listOf(Color(0xFF25354D),Color(0xFF151D29),Color(0xFF202C41))
        else listOf(Color(0xFFE3ECFA),Color(0xFFFAFCFF),Color(0xFFEAF0FA)),end=Offset(w,h)),size=androidx.compose.ui.geometry.Size(w,h))
}
@Composable internal fun ReceiverScene(modifier:Modifier=Modifier,content:@Composable BoxScope.()->Unit) {
    var scene by remember { mutableStateOf(Backdrop()) }
    val dark=LocalReceiverDark.current
    Box(modifier.onGloballyPositioned { scene=Backdrop(it.size,it.positionInRoot()) }) {
        Canvas(Modifier.matchParentSize()) { drawScene(scene.size,dark) }
        CompositionLocalProvider(LocalBackdrop provides scene) { content() }
    }
}
/** Samples only our static app backdrop, never SurfaceView/video. Foreground text is never blurred. */
@Composable internal fun GlassSurface(modifier:Modifier=Modifier,content:@Composable BoxScope.()->Unit) {
    val scene=LocalBackdrop.current
    val dark=LocalReceiverDark.current
    var offset by remember { mutableStateOf(Offset.Zero) }
    val shape=ReceiverTokens.panel
    val tint=MaterialTheme.colorScheme.surface
    val highlight=if(dark) Color.White.copy(alpha=.16f) else Color.White.copy(alpha=.9f)
    Box(modifier.shadow(2.dp,shape).clip(shape)
        .onGloballyPositioned { offset=it.positionInRoot()-scene.origin }
        .border(1.dp,highlight,shape)) {
        if(Build.VERSION.SDK_INT>=31) {
            Canvas(Modifier.matchParentSize().blur(16.dp)) {
                translate(-offset.x,-offset.y) { drawScene(scene.size,dark) }
            }
        }
        Box(Modifier.matchParentSize().background(tint.copy(alpha=if(Build.VERSION.SDK_INT<31) .98f else .92f)))
        content()
    }
}
