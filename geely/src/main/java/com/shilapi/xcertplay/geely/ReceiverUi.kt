package com.shilapi.xcertplay.geely

import android.app.Activity
import android.view.WindowInsetsController
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

internal enum class ReceiverDialog { AUDIO, FPS, PHONE, LOGS }
internal class ReceiverUiState {
    var mode by mutableStateOf(ConnectionMode.CAR_HOTSPOT)
    var attempted by mutableStateOf(false)
    var ssid by mutableStateOf("")
    var password by mutableStateOf("")
    var networkInterface by mutableStateOf("wlan0")
    var phoneName by mutableStateOf("自动选择已配对的 iPhone")
    var bufferMs by mutableIntStateOf(200)
    var fps by mutableIntStateOf(30)
    var busy by mutableStateOf(false)
    var stopping by mutableStateOf(false)
    var connected by mutableStateOf(false)
    var message by mutableStateOf("未连接。请先连接网络并配对蓝牙。")
    var settingsOpen by mutableStateOf(false)
    var dialog by mutableStateOf<ReceiverDialog?>(null)
    var phoneChoices by mutableStateOf<List<Pair<String?,String>>>(emptyList())
    var logs by mutableStateOf("")
    var notice by mutableStateOf("")
    val editable get()=!busy && !stopping && !connected
    fun dismissTopLayer():Boolean {
        if(dialog!=null) { dialog=null;return true }
        if(settingsOpen) { settingsOpen=false;return true }
        return false
    }
}
@Composable private fun tr(zh:String,en:String):String =
    if(LocalConfiguration.current.locales[0].language=="en") en else zh

@Composable internal fun ReceiverApp(
    ui:ReceiverUiState,onMode:(ConnectionMode)->Unit,onPrimary:()->Unit,onDisconnect:()->Unit,
    onBluetooth:()->Unit,onPhone:()->Unit,onPhoneSelected:(String?,String)->Unit,
    onPlayback:(Boolean,Int)->Unit,onLogs:()->Unit,
) {
    val context=LocalContext.current
    val appearance=remember { ReceiverAppearance(context) }
    val theme by appearance.mode.collectAsState(initial=null)
    val scope=rememberCoroutineScope()
    var themeError by remember { mutableStateOf(false) }
    ReceiverTheme(theme ?: ReceiverThemeMode.SYSTEM) {
        val dark=LocalReceiverDark.current
        SideEffect {
            (context as? Activity)?.window?.insetsController?.setSystemBarsAppearance(
                if(dark || ui.connected) 0 else WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
                WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS)
        }
        // Wait for the stored mode instead of briefly presenting the wrong themed controls.
        if(theme==null) Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background))
        if(theme!=null) {
            if(ui.connected) {
                Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                    FilledTonalButton(onDisconnect,Modifier.align(Alignment.TopEnd).padding(16.dp).heightIn(min=56.dp),shape=ReceiverTokens.control) {
                        Text(tr("断开 CarPlay","Disconnect CarPlay"),fontSize=16.sp)
                    }
                }
            } else {
                ReceiverScene(Modifier.fillMaxSize()) {
                    ConnectionPage(ui,onMode,onPrimary,onLogs)
                    if(ui.settingsOpen) SettingsPanel(ui,theme!!,themeError,onBluetooth,onPhone,onLogs) { mode ->
                        scope.launch { try { appearance.save(mode);themeError=false } catch(_:java.io.IOException) { themeError=true } }
                    }
                }
            }
            ReceiverDialogs(ui,onPhoneSelected,onPlayback)
        }
    }
    BackHandler(ui.settingsOpen || ui.dialog!=null) { ui.dismissTopLayer() }
}

@Composable private fun ConnectionPage(ui:ReceiverUiState,onMode:(ConnectionMode)->Unit,onPrimary:()->Unit,onLogs:()->Unit) {
    BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
        val wide=ReceiverLayoutPolicy.twoColumns(maxWidth.value,LocalDensity.current.fontScale)
        val horizontalFooter=wide || maxWidth>=600.dp
        val short=maxHeight/LocalDensity.current.fontScale<340.dp
        val padding=if(short) 8.dp else if(maxWidth<600.dp) 16.dp else ReceiverTokens.page
        Column(Modifier.fillMaxSize().padding(padding),verticalArrangement=Arrangement.spacedBy(if(short) 8.dp else 12.dp)) {
            GlassSurface(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=4.dp),verticalAlignment=Alignment.CenterVertically) {
                    Text("DiPlay Geely",Modifier.weight(1f),fontSize=22.sp,fontWeight=FontWeight.SemiBold,
                        color=MaterialTheme.colorScheme.onSurface,maxLines=1,overflow=TextOverflow.Ellipsis)
                    TextButton({ ui.notice="";ui.settingsOpen=true },Modifier.heightIn(min=48.dp)) { Text(tr("设置","Settings"),fontSize=16.sp) }
                }
            }
            if(wide) {
                Row(Modifier.weight(1f).fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(24.dp)) {
                    Column(Modifier.width(208.dp).verticalScroll(rememberScrollState())) { ModeChoices(ui,onMode,false) }
                    ConnectionForm(ui,Modifier.weight(1f).fillMaxHeight())
                }
            } else {
                Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(16.dp)) {
                    ModeChoices(ui,onMode,true)
                    ConnectionFields(ui)
                }
            }
            ConnectionFooter(ui,horizontalFooter,onPrimary,onLogs)
        }
    }
}
@Composable private fun ModeChoices(ui:ReceiverUiState,onMode:(ConnectionMode)->Unit,horizontal:Boolean) {
    @Composable fun choice(mode:ConnectionMode,modifier:Modifier) {
        val selected=ui.mode==mode
        val haptic=LocalHapticFeedback.current
        Surface(onClick={ haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove);onMode(mode) },enabled=ui.editable,
            modifier=modifier.heightIn(min=56.dp).semantics { role=Role.RadioButton;this.selected=selected },
            shape=ReceiverTokens.control,color=if(selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent) {
            Row(Modifier.padding(horizontal=4.dp,vertical=4.dp),verticalAlignment=Alignment.CenterVertically) {
                RadioButton(selected,null,enabled=ui.editable)
                Text(if(mode==ConnectionMode.CAR_HOTSPOT) tr("车机热点","Car hotspot") else tr("同一局域网","Same Wi-Fi"),
                    Modifier.padding(end=8.dp),fontSize=16.sp,fontWeight=if(selected) FontWeight.SemiBold else FontWeight.Normal)
            }
        }
    }
    if(horizontal) Row(Modifier.fillMaxWidth().selectableGroup(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        choice(ConnectionMode.CAR_HOTSPOT,Modifier.weight(1f));choice(ConnectionMode.SAME_LAN,Modifier.weight(1f))
    } else Column(Modifier.selectableGroup(),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        choice(ConnectionMode.CAR_HOTSPOT,Modifier.fillMaxWidth());choice(ConnectionMode.SAME_LAN,Modifier.fillMaxWidth())
    }
}
@Composable private fun ConnectionForm(ui:ReceiverUiState,modifier:Modifier) {
    Column(modifier.verticalScroll(rememberScrollState())) { ConnectionFields(ui,true) }
}
@Composable private fun ConnectionFields(ui:ReceiverUiState,inline:Boolean=false) {
    var reveal by rememberSaveable { mutableStateOf(false) }
    Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Text(if(ui.mode==ConnectionMode.CAR_HOTSPOT) tr("开启原车热点，让 iPhone 加入并完成蓝牙配对。","Turn on the car hotspot. Join it on iPhone and pair Bluetooth.")
            else tr("两台设备加入同一 Wi-Fi 并配对蓝牙；网络需允许设备互通和组播。","Join the same Wi-Fi and pair Bluetooth. Allow local traffic and multicast."),
            color=MaterialTheme.colorScheme.onSurfaceVariant,fontSize=14.sp)
        @Composable fun name(modifier:Modifier) {
            OutlinedTextField(ui.ssid,{ ui.ssid=it },modifier,enabled=ui.editable,
                textStyle=LocalTextStyle.current.copy(fontSize=16.sp),label={Text(tr("网络名称","Network name"))},singleLine=true,shape=ReceiverTokens.control,
                isError=ui.attempted && ui.ssid.isBlank(),
                supportingText=if(ui.attempted && ui.ssid.isBlank()) ({Text(tr("请输入网络名称","Enter the network name"))}) else null,
                keyboardOptions=KeyboardOptions(autoCorrectEnabled=false,imeAction=ImeAction.Next))
        }
        @Composable fun password(modifier:Modifier) {
            OutlinedTextField(ui.password,{ui.password=it},modifier,enabled=ui.editable,
                textStyle=LocalTextStyle.current.copy(fontSize=16.sp),label={Text(tr("网络密码","Network password"))},placeholder={Text(tr("开放网络可留空","Leave empty for an open network"))},
                visualTransformation=if(reveal) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Password,imeAction=ImeAction.Done),
                trailingIcon={ TextButton({reveal=!reveal},Modifier.sizeIn(minWidth=56.dp,minHeight=56.dp)) {Text(if(reveal) tr("隐藏","Hide") else tr("显示","Show"))} },
                singleLine=true,shape=ReceiverTokens.control)
        }
        if(inline) Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(16.dp),verticalAlignment=Alignment.Top) {
            name(Modifier.weight(1f));password(Modifier.weight(1f))
        } else { name(Modifier.fillMaxWidth());password(Modifier.fillMaxWidth()) }
        TextButton({ui.notice="";ui.settingsOpen=true},Modifier.heightIn(min=56.dp)) {Text(tr("连接与播放设置","Connection & playback settings"),fontSize=14.sp)}
    }
}
@Composable private fun ConnectionFooter(ui:ReceiverUiState,wide:Boolean,onPrimary:()->Unit,onLogs:()->Unit) {
    val keyboard=LocalSoftwareKeyboardController.current
    val focus=LocalFocusManager.current
    val haptic=LocalHapticFeedback.current
    @Composable fun status(modifier:Modifier) {
        Column(modifier.semantics { liveRegion=LiveRegionMode.Polite }) {
            if(ui.busy && !ui.stopping) LinearProgressIndicator(Modifier.fillMaxWidth().padding(bottom=8.dp))
            Text(tr(ui.message,receiverStatusEnglish(ui.message)),fontSize=14.sp,color=MaterialTheme.colorScheme.onSurface,maxLines=2,overflow=TextOverflow.Ellipsis)
        }
    }
    @Composable fun action(modifier:Modifier) {
        Button({ ui.attempted=true;keyboard?.hide();focus.clearFocus();haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove);onPrimary() },
            modifier.heightIn(min=56.dp),enabled=!ui.stopping,shape=ReceiverTokens.control) {
            Text(if(ui.stopping) tr("正在结束连接…","Finishing…") else if(ui.busy) tr("取消连接","Cancel connection") else tr("连接 CarPlay","Connect CarPlay"),fontSize=16.sp)
        }
    }
    GlassSurface(Modifier.fillMaxWidth()) {
        if(wide) Row(Modifier.padding(12.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(24.dp)) {
            status(Modifier.weight(1f))
            if(!ui.busy && ui.message!="未连接。请先连接网络并配对蓝牙。") TextButton(onLogs,Modifier.heightIn(min=48.dp)) {Text(tr("详情","Details"))}
            action(Modifier.width(224.dp))
        } else Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {status(Modifier.fillMaxWidth());action(Modifier.fillMaxWidth())}
    }
}
@Composable private fun SettingsPanel(ui:ReceiverUiState,theme:ReceiverThemeMode,themeError:Boolean,onBluetooth:()->Unit,onPhone:()->Unit,onLogs:()->Unit,onTheme:(ReceiverThemeMode)->Unit) {
    var query by rememberSaveable {mutableStateOf("")}
    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black.copy(alpha=.24f)).safeDrawingPadding().imePadding()) {
        val wide=maxWidth>=840.dp
        GlassSurface(Modifier.align(Alignment.CenterEnd).padding(if(wide) 16.dp else 8.dp)
            .widthIn(max=if(wide) 540.dp else maxWidth).fillMaxHeight()) {
            Column(Modifier.fillMaxSize().padding(20.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment=Alignment.CenterVertically) {
                    Text(tr("设置","Settings"),Modifier.weight(1f),fontSize=22.sp,fontWeight=FontWeight.SemiBold)
                    TextButton({ui.settingsOpen=false},Modifier.heightIn(min=56.dp)) { Text(tr("返回","Back")) }
                }
                OutlinedTextField(query,{query=it},Modifier.fillMaxWidth(),label={Text(tr("查找设置","Find a setting"))},singleLine=true,shape=ReceiverTokens.control)
                if(ui.notice.isNotEmpty()) Text(tr(ui.notice,receiverStatusEnglish(ui.notice)),
                    Modifier.semantics {liveRegion=LiveRegionMode.Polite},fontSize=14.sp)
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    fun matches(vararg words:String)=query.isBlank() || words.any {it.contains(query,true)}
                    @Composable fun section(title:String) { Text(title,Modifier.padding(top=16.dp,bottom=4.dp),fontSize=14.sp,fontWeight=FontWeight.SemiBold,color=MaterialTheme.colorScheme.primary) }
                    val connection=tr("连接","Connection");val playback=tr("播放","Playback");val appearance=tr("外观","Appearance");val diagnostics=tr("诊断","Diagnostics")
                    var found=false
                    if(matches(connection,"蓝牙配对","Bluetooth")) {found=true;section(connection);SettingsRow(tr("蓝牙配对","Pair Bluetooth"),tr("打开系统蓝牙设置","Open system Bluetooth settings"),ui.editable,onBluetooth)}
                    if(matches(connection,"iPhone","手机")) {found=true;SettingsRow(tr("已配对 iPhone","Paired iPhone"),(if(ui.phoneName=="自动选择已配对的 iPhone") tr(ui.phoneName,"Choose paired iPhone automatically") else ui.phoneName),ui.editable,onPhone)}
                    if(ui.mode==ConnectionMode.CAR_HOTSPOT && matches(connection,"接口","interface")) {
                        found=true;OutlinedTextField(ui.networkInterface,{ui.networkInterface=it},Modifier.fillMaxWidth(),enabled=ui.editable,
                            label={Text(tr("热点网络接口","Hotspot network interface"))},singleLine=true,shape=ReceiverTokens.control)
                    }
                    if(matches(playback,"音频","缓冲","audio","buffer")) {
                        found=true;section(playback);SettingsRow(tr("音频缓冲","Audio buffer"),"${ui.bufferMs}ms · "+tr("下次连接生效","Next connection"),ui.editable) {ui.dialog=ReceiverDialog.AUDIO}
                    }
                    if(matches(playback,"帧率","fps","frame")) {found=true;SettingsRow(tr("CarPlay 帧率","CarPlay frame rate"),"${ui.fps} fps · "+tr("下次连接生效","Next connection"),ui.editable) {ui.dialog=ReceiverDialog.FPS}}
                    if(matches(appearance,"主题","theme","浅色","深色","系统")) {
                        found=true;section(appearance)
                        ReceiverThemeMode.entries.forEach { mode ->
                            Row(Modifier.fillMaxWidth().heightIn(min=56.dp).clickable {onTheme(mode)}.semantics {role=Role.RadioButton;selected=mode==theme},verticalAlignment=Alignment.CenterVertically) {
                                RadioButton(mode==theme,null)
                                Text(when(mode) {ReceiverThemeMode.SYSTEM->tr("跟随系统","Follow system");ReceiverThemeMode.LIGHT->tr("浅色","Light");else->tr("深色","Dark")},fontSize=14.sp)
                            }
                        }
                        if(themeError) Text(tr("外观保存失败，请重试。","Could not save appearance. Try again."),color=MaterialTheme.colorScheme.error)
                    }
                    if(matches(diagnostics,"日志","记录","log")) {found=true;section(diagnostics);SettingsRow(tr("本地连接记录","Local connection log"),tr("用于查看失败阶段与音视频诊断","Connection stages and media diagnostics"),true,onLogs)}
                    if(!found) Text(tr("未找到设置，请换个关键词。","No settings found. Try another keyword."),Modifier.padding(16.dp))
                }
            }
        }
    }
}
@Composable private fun SettingsRow(title:String,subtitle:String,enabled:Boolean,onClick:()->Unit) {
    Surface(onClick,enabled=enabled,shape=ReceiverTokens.control,color=MaterialTheme.colorScheme.surface.copy(alpha=.7f),modifier=Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(title,fontSize=16.sp,color=MaterialTheme.colorScheme.onSurface)
            Text(subtitle,fontSize=14.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
@Composable private fun ReceiverDialogs(ui:ReceiverUiState,onPhone:(String?,String)->Unit,onPlayback:(Boolean,Int)->Unit) {
    val dialog=ui.dialog ?: return
    if(dialog==ReceiverDialog.LOGS) {
        AlertDialog(onDismissRequest={ui.dialog=null},title={Text(tr("本地连接记录","Local connection log"))},
            text={SelectionContainer {Text(ui.logs,Modifier.heightIn(max=360.dp).verticalScroll(rememberScrollState()),fontSize=14.sp)}},
            confirmButton={TextButton({ui.dialog=null}) {Text(tr("关闭","Close"))}})
        return
    }
    val title=when(dialog) { ReceiverDialog.AUDIO->tr("音频缓冲","Audio buffer");ReceiverDialog.FPS->tr("CarPlay 帧率","CarPlay frame rate");else->tr("已配对 iPhone","Paired iPhone") }
    AlertDialog(onDismissRequest={ui.dialog=null},title={Text(title)},text={
        Column(Modifier.heightIn(max=360.dp).verticalScroll(rememberScrollState())) {
            if(dialog!=ReceiverDialog.PHONE) Text(if(dialog==ReceiverDialog.AUDIO)
                tr("下次连接生效。较低缓冲响应更快，但更易断音；这不是整条链路的总延迟。","Applies next connection. Lower buffering can respond faster but may stutter; this is not total link latency.")
                else tr("下次连接生效。60 帧负载更高；当前实现暂不支持 120 帧。","Applies next connection. 60 fps increases load; 120 fps is not supported by this implementation."),fontSize=14.sp)
            val options=when(dialog) {ReceiverDialog.AUDIO->ReceiverPlaybackSettings.bufferChoices.map {"${it}ms"};ReceiverDialog.FPS->ReceiverPlaybackSettings.fpsChoices.map {"$it fps"};else->ui.phoneChoices.map {it.second}}
            options.forEachIndexed {index,label ->
                val selected=when(dialog) {ReceiverDialog.AUDIO->ReceiverPlaybackSettings.bufferChoices[index]==ui.bufferMs;ReceiverDialog.FPS->ReceiverPlaybackSettings.fpsChoices[index]==ui.fps;else->false}
                Row(Modifier.fillMaxWidth().heightIn(min=56.dp).clickable(enabled=ui.editable) {
                    if(ui.editable) {
                        if(dialog==ReceiverDialog.PHONE) {val item=ui.phoneChoices[index];onPhone(item.first,item.second)}
                        else onPlayback(dialog==ReceiverDialog.AUDIO,if(dialog==ReceiverDialog.AUDIO) ReceiverPlaybackSettings.bufferChoices[index] else ReceiverPlaybackSettings.fpsChoices[index])
                        ui.dialog=null
                    }
                }.semantics { role=Role.RadioButton;this.selected=selected },verticalAlignment=Alignment.CenterVertically) {
                    RadioButton(selected,null,enabled=ui.editable);Text(label,fontSize=16.sp)
                }
            }
        }
    },confirmButton={TextButton({ui.dialog=null}) {Text(tr("取消","Cancel"))}})
}

internal object ReceiverLayoutPolicy {
    fun twoColumns(widthDp:Float,fontScale:Float)=widthDp/fontScale.coerceAtLeast(1f)>=720f
}
