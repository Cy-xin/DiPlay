package com.shilapi.xcertplay.geely

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import android.os.Build
import androidx.compose.ui.unit.Density
import android.app.Activity
import android.view.WindowInsetsController
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.clip
import kotlinx.coroutines.launch

internal enum class ReceiverDialog { AUDIO, FPS, QUALITY, SIZE, DOCK, PHONE, LOGS, DIAGNOSTICS, PAIR_UPLOAD, AUDIO_ROUTE, WHEEL_MAPPING }
internal class ReceiverUiState {
    var mode by mutableStateOf(ConnectionMode.CAR_HOTSPOT)
    var autoConnectOnOpen by mutableStateOf(false)
    var saveAutoConnectOnOpen:(Boolean)->Unit={}
    var audit:(String,String)->Unit={_,_->}
    var refreshLocalLog:()->Unit={}
    var exportLocalLog:()->Unit={}
    var audioMappings by mutableStateOf<Map<ReceiverAudioRole,String>>(emptyMap())
    var audioOutputBuses by mutableStateOf<Set<String>>(emptySet())
    var audioMappingRole by mutableStateOf(ReceiverAudioRole.MEDIA)
    var saveAudioMapping:(ReceiverAudioRole,String)->Unit={_,_->}
    var resetAudioMappings:()->Unit={}
    var refreshAudioOutputs:()->Unit={}
    var uploadPaired by mutableStateOf(false)
    var uploadBusy by mutableStateOf(false)
    var pendingUpload by mutableStateOf(false)
    var uploadMessage by mutableStateOf("")
    var issue by mutableStateOf("")
    var lastReport by mutableStateOf("")
    var hideSystemBars by mutableStateOf(false)
    var attempted by mutableStateOf(false)
    var ssid by mutableStateOf("")
    var password by mutableStateOf("")
    var networkInterface by mutableStateOf("wlan0")
    var phoneName by mutableStateOf("自动选择已配对的 iPhone")
    var bufferMs by mutableIntStateOf(200)
    var fps by mutableIntStateOf(30)
    var testingAudioRole by mutableStateOf<ReceiverAudioRole?>(null)
    var audioTestRemaining by mutableIntStateOf(5)
    var audioTestMessage by mutableStateOf("")
    var testAudio:(ReceiverAudioRole)->Unit={}
    var stopAudioTest:()->Unit={}
    var lowLatencyVideo by mutableStateOf(false)
    var saveLowLatencyVideo:(Boolean)->Unit={}
    var quality by mutableIntStateOf(100)
    var carPlaySize by mutableIntStateOf(100)
    var dockPosition by mutableIntStateOf(0)
    var displayApplied by mutableStateOf("")
    var timingReport by mutableStateOf("")
    var connectionCheck by mutableStateOf("点击连接后逐项检查配置、认证、网络、蓝牙和画面。")
    var runtimeHealth by mutableStateOf("尚无当前会话运行数据")
    var reconnectNeeded by mutableStateOf(false)
    fun markReconnectNeeded() {reconnectNeeded=true}
    var busy by mutableStateOf(false)
    var stopping by mutableStateOf(false)
    var connected by mutableStateOf(false)
    var showCarPlay by mutableStateOf(false)
    var pictureRestoreFailed by mutableStateOf(false)
    var retryPicture:()->Unit={}
    var dismissPictureHint:()->Unit={}
    var message by mutableStateOf("未连接。请先连接网络并配对蓝牙。")
    var wheelMappings by mutableStateOf(ReceiverWheelMapping.defaults)
    var wheelMappingKey by mutableIntStateOf(200087)
    var wheelDetecting by mutableStateOf(false)
    var wheelMessage by mutableStateOf("")
    var wheelLastEvent by mutableStateOf("")
    var saveWheelMapping:(Int,Int)->Unit={_,_->}
    var resetWheelMappings:()->Unit={}
    var startWheelDetection:()->Unit={}
    var stopWheelDetection:()->Unit={}
    var testWheelAction:(Int)->Unit={}
    var openVehicleHome:()->Unit={}
    var settingsOpen by mutableStateOf(false)
    var dialog by mutableStateOf<ReceiverDialog?>(null)
    var phoneChoices by mutableStateOf<List<Pair<String?,String>>>(emptyList())
    var logs by mutableStateOf("")
    var notice by mutableStateOf("")
    val editable get()=!busy && !stopping && !connected
    val displayEditable get()=!stopping && (!busy || connected)
    fun dismissTopLayer():Boolean {
        if(dialog!=null) { audit("dialog_back","dialog=$dialog");dialog=if(dialog==ReceiverDialog.LOGS || dialog==ReceiverDialog.PAIR_UPLOAD) ReceiverDialog.DIAGNOSTICS else null;return true }
        if(settingsOpen) {audit("settings_back","");settingsOpen=false;return true }
        return false
    }
}
@Composable private fun tr(zh:String,en:String):String =
    if(LocalConfiguration.current.locales[0].language=="en") en else zh

@Composable internal fun ReceiverApp(
    ui:ReceiverUiState,onMode:(ConnectionMode)->Unit,onPrimary:()->Unit,onDisconnect:()->Unit,
    onBluetooth:()->Unit,onPhone:()->Unit,onPhoneSelected:(String?,String)->Unit,
    onPlayback:(Boolean,Int)->Unit,onDisplay:(Boolean,Int)->Unit,onDock:(Int)->Unit,onFullscreen:(Boolean)->Unit,onUpload:(Boolean)->Unit,onPairUpload:(String)->Unit,onLogs:()->Unit,
) {
    val context=LocalContext.current
    val appearance=remember { ReceiverAppearance(context) }
    val theme by appearance.mode.collectAsState(initial=null)
    val scope=rememberCoroutineScope()
    var themeError by remember { mutableStateOf(false) }
    val density=LocalDensity.current
    val vehicleScale=if(Build.MODEL.contains("KX11",ignoreCase=true)) 1.35f else 1f
    CompositionLocalProvider(LocalDensity provides Density(density.density*vehicleScale,density.fontScale)) {
    ReceiverTheme(theme ?: ReceiverThemeMode.SYSTEM) {
        val dark=LocalReceiverDark.current
        SideEffect {
            (context as? Activity)?.window?.insetsController?.setSystemBarsAppearance(
                if(dark || ui.connected && ui.showCarPlay) 0 else WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
                WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS)
        }
        // Wait for the stored mode instead of briefly presenting the wrong themed controls.
        if(theme==null) Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background))
        if(theme!=null) {
            if(ui.connected && ui.showCarPlay) {
                // Keep the host transparent; only a failed picture return exposes an action.
                Box(Modifier.fillMaxSize()) {
                    if(ui.pictureRestoreFailed) PictureRecoveryHint(ui,Modifier.align(Alignment.TopStart)
                        .safeDrawingPadding().padding(ReceiverTokens.gap))
                }
            } else {
                ReceiverScene(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding().padding(horizontal=ReceiverTokens.page,vertical=8.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                        val page=when {
                            ui.dialog in listOf(ReceiverDialog.DIAGNOSTICS,ReceiverDialog.PAIR_UPLOAD,ReceiverDialog.LOGS) -> 2
                            ui.settingsOpen -> 1
                            else -> 0
                        }
                        ReceiverPageMotion(page,Modifier.weight(1f).fillMaxWidth()) {
                            when {
                                ui.dialog in listOf(ReceiverDialog.DIAGNOSTICS,ReceiverDialog.PAIR_UPLOAD,ReceiverDialog.LOGS) -> DiagnosticPage(ui,onUpload,onLogs)
                                ui.settingsOpen -> SettingsPanel(ui,theme!!,themeError,onBluetooth,onPhone,onLogs,onFullscreen) { mode ->
                                    ui.audit("theme_selected","mode=$mode")
                                    scope.launch { try { appearance.save(mode);themeError=false;ui.audit("theme_saved","mode=$mode") } catch(_:java.io.IOException) { themeError=true;ui.audit("theme_save_failed","") } }
                                }
                                else -> ConnectionPage(ui,onMode,onPrimary,onLogs,onDisconnect)
                            }
                        }
                        GlassNavigation(ui,onLogs)
                    }
                }
            }
            ReceiverDialogs(ui,onPhoneSelected,onPlayback,onDisplay,onDock,onUpload,onPairUpload)
        }
    }
    }
    BackHandler(ui.settingsOpen || ui.dialog!=null) { ui.dismissTopLayer() }
}

@Composable internal fun PictureRecoveryHint(ui:ReceiverUiState,modifier:Modifier=Modifier) {
    Surface(modifier.widthIn(max=ReceiverTokens.pictureHintWidth),shape=ReceiverTokens.control,
        color=MaterialTheme.colorScheme.surface.copy(alpha=.96f),shadowElevation=ReceiverTokens.pictureHintElevation) {
        BoxWithConstraints(Modifier.padding(horizontal=ReceiverTokens.gap)) {
            @Composable fun message(modifier:Modifier=Modifier) {
                Text(tr("暂未收到新画面","No new picture yet"),modifier.padding(vertical=ReceiverTokens.gap)
                    .semantics {liveRegion=LiveRegionMode.Polite},fontSize=14.sp)
            }
            @Composable fun actions() {
                TextButton({ui.retryPicture()},Modifier.heightIn(min=ReceiverTokens.touch)) {Text(tr("重试画面","Retry picture"))}
                TextButton({ui.dismissPictureHint()},Modifier.heightIn(min=ReceiverTokens.touch)) {Text(tr("忽略","Dismiss"))}
            }
            if(maxWidth/LocalDensity.current.fontScale>=ReceiverTokens.pictureHintInlineWidth) {
                Row(verticalAlignment=Alignment.CenterVertically) {message(Modifier.weight(1f));actions()}
            } else Column {message();Row {actions()}}
        }
    }
}

@Composable private fun GlassIcon(kind:Int,modifier:Modifier=Modifier) {
    val ink=LocalContentColor.current
    Canvas(modifier.size(22.dp)) {
        val scale=size.minDimension/24f
        val stroke=Stroke(1.8f*scale,cap=androidx.compose.ui.graphics.StrokeCap.Round,join=androidx.compose.ui.graphics.StrokeJoin.Round)
        val path=Path()
        fun line(vararg points:Float) {path.moveTo(points[0]*scale,points[1]*scale);for(i in 2 until points.size step 2) path.lineTo(points[i]*scale,points[i+1]*scale)}
        when(kind) {
            0 -> {line(3f,11f,7f,15f,11f,11f);line(13f,13f,17f,9f,21f,13f);line(7f,15f,14f,8f);line(10f,16f,17f,9f)}
            1 -> {drawCircle(ink,radius=8f*scale,center=center,style=stroke);drawCircle(ink,radius=3f*scale,center=center,style=stroke);for(i in 0..3) {val x=if(i%2==0) 12f else if(i==1) 2f else 22f;val y=if(i%2==1) 12f else if(i==0) 2f else 22f;drawCircle(ink,radius=1.4f*scale,center=Offset(x*scale,y*scale))}}
            2 -> line(2f,12f,6f,12f,9f,4f,13f,20f,16f,10f,19f,12f,22f,12f)
            3 -> {line(3f,9f,8f,5f,16f,5f,21f,9f);line(6f,12f,9f,9f,15f,9f,18f,12f);line(10f,15f,12f,13f,14f,15f);drawCircle(ink,radius=1.2f*scale,center=Offset(12f*scale,18f*scale))}
            else -> {line(12f,4f,12f,10f);line(5f,14f,5f,10f,19f,10f,19f,14f);drawRect(ink,Offset(2f*scale,14f*scale),androidx.compose.ui.geometry.Size(6f*scale,5f*scale),style=stroke);drawRect(ink,Offset(16f*scale,14f*scale),androidx.compose.ui.geometry.Size(6f*scale,5f*scale),style=stroke)}
        }
        drawPath(path,ink,style=stroke)
    }
}
@Composable private fun GlassNavigation(ui:ReceiverUiState,onLogs:()->Unit) {
    val diagnostic=ui.dialog in listOf(ReceiverDialog.DIAGNOSTICS,ReceiverDialog.PAIR_UPLOAD,ReceiverDialog.LOGS)
    val selected=if(diagnostic) 2 else if(ui.settingsOpen) 1 else 0
    Box(Modifier.fillMaxWidth(),contentAlignment=Alignment.Center) {
        GlassSurface(Modifier.widthIn(max=600.dp).fillMaxWidth()) {
            Row(Modifier.padding(5.dp).selectableGroup(),horizontalArrangement=Arrangement.spacedBy(4.dp)) {
                listOf(tr("连接","Connect"),tr("设置","Settings"),tr("诊断","Diagnostics")).forEachIndexed {i,label ->
                    val active=i==selected
                    val color by animateColorAsState(
                        if(active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        tween(ReceiverMotion.colorMs),label="tab ink")
                    val fill by animateColorAsState(
                        if(active) MaterialTheme.colorScheme.surface.copy(alpha=.68f) else Color.Transparent,
                        tween(ReceiverMotion.colorMs),label="tab selection")
                    Surface(onClick={ui.audit("navigation_selected","tab=$i");ui.settingsOpen=i==1;ui.dialog=null;if(i==2) onLogs()},
                        modifier=Modifier.weight(1f).semantics {role=Role.Tab;this.selected=active},shape=CircleShape,
                        color=fill) {
                        Row(Modifier.heightIn(min=48.dp).padding(horizontal=8.dp,vertical=8.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.Center) {
                            CompositionLocalProvider(LocalContentColor provides color) {GlassIcon(i);Spacer(Modifier.width(8.dp));Text(label,fontSize=14.sp,fontWeight=if(active) FontWeight.SemiBold else FontWeight.Normal)}
                        }
                    }
                }
            }
        }
    }
}
@Composable private fun PageHeading(title:String,subtitle:String?=null,aside:@Composable ()->Unit={}) {
    Row(Modifier.fillMaxWidth().padding(vertical=4.dp),verticalAlignment=Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title,fontSize=24.sp,fontWeight=FontWeight.Bold,color=MaterialTheme.colorScheme.onSurface)
            if(subtitle!=null) Text(subtitle,fontSize=13.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
        aside()
    }
}
@Composable private fun ConnectionPage(ui:ReceiverUiState,onMode:(ConnectionMode)->Unit,onPrimary:()->Unit,onLogs:()->Unit,onDisconnect:()->Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide=ReceiverLayoutPolicy.twoColumns(maxWidth.value,LocalDensity.current.fontScale)
        val short=maxHeight/LocalDensity.current.fontScale<420.dp
        val horizontalModes=wide || maxWidth/LocalDensity.current.fontScale>=360.dp
        val horizontalFooter=wide || maxWidth>=600.dp
        Column(Modifier.fillMaxSize(),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                TextButton({ui.openVehicleHome()},Modifier.heightIn(min=48.dp)) {
                    Text(tr("车机主页","Vehicle home"),fontSize=14.sp)
                }
                Text("DiPlay Geely",Modifier.weight(1f).padding(start=12.dp),fontSize=16.sp,fontWeight=FontWeight.SemiBold)
                GlassSurface {Text(if(ui.connected) tr("已连接","Connected") else if(ui.busy) tr("连接中","Connecting") else tr("未连接","Not connected"),Modifier.padding(horizontal=14.dp,vertical=8.dp),fontSize=12.sp)}
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(if(short) 8.dp else 16.dp)) {
                if(!short) Text(tr("连接，让旅途更轻松","Connect. Enjoy the journey."),fontSize=30.sp,fontWeight=FontWeight.Bold)
                ModeChoices(ui,onMode,horizontalModes)
                GlassSurface(Modifier.fillMaxWidth()) {
                    if(wide) Row(Modifier.padding(16.dp),horizontalArrangement=Arrangement.spacedBy(24.dp)) {
                        Column(Modifier.width(220.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {ConfigurationHeading(ui)}
                        Column(Modifier.weight(1f)) {ConnectionFields(ui,true)}
                    } else Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {ConfigurationHeading(ui);ConnectionFields(ui)}
                }
            }
            ConnectionFooter(ui,horizontalFooter,onPrimary,onLogs,onDisconnect)
        }
    }
}
@Composable private fun ConfigurationHeading(ui:ReceiverUiState) {
    Text(if(ui.mode==ConnectionMode.CAR_HOTSPOT) tr("热点配置","Hotspot setup") else tr("局域网配置","Wi-Fi setup"),fontSize=18.sp,fontWeight=FontWeight.SemiBold)
    Text(if(ui.mode==ConnectionMode.CAR_HOTSPOT) tr("开启车机热点，让 iPhone 加入同一网络，并完成蓝牙配对。","Turn on the car hotspot, join it on iPhone, and pair Bluetooth.")
        else tr("两台设备加入同一 Wi-Fi 并配对蓝牙。网络需允许设备互通和组播。","Join the same Wi-Fi and pair Bluetooth. Allow local traffic and multicast."),fontSize=13.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
}
@Composable private fun ModeChoices(ui:ReceiverUiState,onMode:(ConnectionMode)->Unit,horizontal:Boolean) {
    @Composable fun choice(mode:ConnectionMode,modifier:Modifier) {
        val selected=ui.mode==mode
        val haptic=LocalHapticFeedback.current
        GlassSurface(modifier) {
            val fill=if(selected) Modifier.background(Brush.linearGradient(listOf(Color(0xFF0871D5),Color(0xFF0065DE)))) else Modifier
            Row(Modifier.fillMaxWidth().then(fill).heightIn(min=56.dp).clickable(enabled=ui.editable) {haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove);onMode(mode)}
                .semantics {role=Role.RadioButton;this.selected=selected}.padding(horizontal=16.dp,vertical=12.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                CompositionLocalProvider(LocalContentColor provides if(selected) Color.White else MaterialTheme.colorScheme.onSurface) {
                    GlassIcon(if(mode==ConnectionMode.CAR_HOTSPOT) 3 else 4)
                    Text(if(mode==ConnectionMode.CAR_HOTSPOT) tr("车机热点","Car hotspot") else tr("同一局域网","Same Wi-Fi"),Modifier.weight(1f),fontSize=16.sp,fontWeight=FontWeight.SemiBold)
                    if(selected) Text("✓",fontSize=18.sp)
                }
            }
        }
    }
    if(horizontal) Row(Modifier.fillMaxWidth().selectableGroup(),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
        choice(ConnectionMode.CAR_HOTSPOT,Modifier.weight(1f));choice(ConnectionMode.SAME_LAN,Modifier.weight(1f))
    } else Column(Modifier.selectableGroup(),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        choice(ConnectionMode.CAR_HOTSPOT,Modifier.fillMaxWidth());choice(ConnectionMode.SAME_LAN,Modifier.fillMaxWidth())
    }
}
@Composable private fun ConnectionFields(ui:ReceiverUiState,inline:Boolean=false) {
    var reveal by rememberSaveable { mutableStateOf(false) }
    var advanced by rememberSaveable { mutableStateOf(false) }
    Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
        @Composable fun name(modifier:Modifier) {
            OutlinedTextField(ui.ssid,{ ui.audit("field_edited","field=network_name");ui.ssid=it },modifier,enabled=ui.editable,
                textStyle=LocalTextStyle.current.copy(fontSize=16.sp),label={Text(tr("网络名称","Network name"))},singleLine=true,shape=ReceiverTokens.control,
                isError=ui.attempted && ui.ssid.isBlank(),
                supportingText=if(ui.attempted && ui.ssid.isBlank()) ({Text(tr("请输入网络名称","Enter the network name"))}) else null,
                keyboardOptions=KeyboardOptions(autoCorrectEnabled=false,imeAction=ImeAction.Next))
        }
        @Composable fun password(modifier:Modifier) {
            OutlinedTextField(ui.password,{ui.audit("field_edited","field=network_password");ui.password=it},modifier,enabled=ui.editable,
                textStyle=LocalTextStyle.current.copy(fontSize=16.sp),label={Text(tr("网络密码","Network password"))},placeholder={Text(tr("开放网络可留空","Leave empty for an open network"))},
                visualTransformation=if(reveal) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Password,imeAction=ImeAction.Done),
                trailingIcon={ TextButton({ui.audit("password_visibility","visible=${!reveal}");reveal=!reveal},Modifier.sizeIn(minWidth=56.dp,minHeight=56.dp)) {Text(if(reveal) tr("隐藏","Hide") else tr("显示","Show"))} },
                singleLine=true,shape=ReceiverTokens.control)
        }
        if(inline) Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(16.dp),verticalAlignment=Alignment.Top) {
            name(Modifier.weight(1f));password(Modifier.weight(1f))
        } else { name(Modifier.fillMaxWidth());password(Modifier.fillMaxWidth()) }
        TextButton({advanced=!advanced;ui.audit("connection_advanced","expanded=$advanced")},Modifier.heightIn(min=48.dp)) {Text(tr("高级选项","Advanced options")+if(advanced) "  −" else "  +",fontSize=14.sp)}
        AnimatedVisibility(advanced,
            enter=expandVertically(tween(ReceiverMotion.expandMs,easing=ReceiverMotion.ease))+fadeIn(tween(160)),
            exit=shrinkVertically(tween(ReceiverMotion.expandMs,easing=ReceiverMotion.ease))+fadeOut(tween(120))) {
            Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
            if(ui.mode==ConnectionMode.CAR_HOTSPOT) OutlinedTextField(ui.networkInterface,{ui.audit("field_edited","field=network_interface");ui.networkInterface=it},Modifier.fillMaxWidth(),enabled=ui.editable,
                label={Text(tr("热点网络接口","Hotspot network interface"))},singleLine=true,shape=ReceiverTokens.control)
            Text(tr("已配对 iPhone 可在设置中选择。","Choose a paired iPhone in Settings."),fontSize=13.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
@Composable private fun ConnectionFooter(ui:ReceiverUiState,wide:Boolean,onPrimary:()->Unit,onLogs:()->Unit,onDisconnect:()->Unit) {
    val keyboard=LocalSoftwareKeyboardController.current
    val focus=LocalFocusManager.current
    val haptic=LocalHapticFeedback.current
    @Composable fun status(modifier:Modifier) {
        Column(modifier.semantics { liveRegion=LiveRegionMode.Polite }) {
            if(ui.busy && !ui.connected && !ui.stopping) LinearProgressIndicator(Modifier.fillMaxWidth().padding(bottom=8.dp))
            Text(tr(ui.message,receiverStatusEnglish(ui.message)),fontSize=14.sp,color=MaterialTheme.colorScheme.onSurface,maxLines=2,overflow=TextOverflow.Ellipsis)
            if(ui.connected) TextButton(onDisconnect,enabled=!ui.stopping) {Text(tr("断开连接","Disconnect"))}
        }
    }
    @Composable fun action(modifier:Modifier) {
        val presses=remember {MutableInteractionSource()}
        Button({ ui.attempted=true;keyboard?.hide();focus.clearFocus();haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove);onPrimary() },
            modifier.heightIn(min=56.dp).receiverPressMotion(presses),enabled=!ui.stopping,shape=CircleShape,interactionSource=presses) {
            Text(if(ui.stopping) tr("正在结束连接…","Finishing…") else if(ui.connected) tr("返回 CarPlay","Return to CarPlay") else if(ui.busy) tr("取消连接","Cancel connection") else tr("连接 CarPlay","Connect CarPlay"),fontSize=16.sp)
        }
    }
    Box(Modifier.fillMaxWidth()) {
        if(wide) Row(Modifier.padding(vertical=4.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(24.dp)) {
            action(Modifier.width(300.dp))
            status(Modifier.weight(1f))
            if(!ui.busy && ui.message!="未连接。请先连接网络并配对蓝牙。") TextButton(onLogs,Modifier.heightIn(min=48.dp)) {Text(tr("详情","Details"))}
        } else Column(Modifier.padding(vertical=4.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {status(Modifier.fillMaxWidth());action(Modifier.fillMaxWidth())}
    }
}
@Composable private fun SettingsPanel(ui:ReceiverUiState,theme:ReceiverThemeMode,themeError:Boolean,onBluetooth:()->Unit,onPhone:()->Unit,onLogs:()->Unit,onFullscreen:(Boolean)->Unit,onTheme:(ReceiverThemeMode)->Unit) {
    var query by rememberSaveable {mutableStateOf("")}
    var settingsGroup by rememberSaveable {mutableIntStateOf(1)}
    val keyboard=LocalSoftwareKeyboardController.current
    val focus=LocalFocusManager.current
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide=maxWidth>=840.dp
        GlassSurface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().padding(20.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment=Alignment.CenterVertically) {
                    Text(tr("设置","Settings"),Modifier.weight(1f),fontSize=24.sp,fontWeight=FontWeight.Bold)
                    if(wide) OutlinedTextField(query,{ui.audit("settings_search_edited","");query=it},Modifier.width(300.dp),label={Text(tr("查找设置","Find a setting"))},singleLine=true,shape=ReceiverTokens.control,
                        keyboardOptions=KeyboardOptions(imeAction=ImeAction.Done),keyboardActions=KeyboardActions(onDone={keyboard?.hide();focus.clearFocus()}))
                    TextButton({ui.audit("settings_close","");ui.settingsOpen=false},Modifier.heightIn(min=56.dp)) { Text(tr("返回","Back")) }
                }
                if(!wide) OutlinedTextField(query,{ui.audit("settings_search_edited","");query=it},Modifier.fillMaxWidth(),label={Text(tr("查找设置","Find a setting"))},singleLine=true,shape=ReceiverTokens.control,
                        keyboardOptions=KeyboardOptions(imeAction=ImeAction.Done),keyboardActions=KeyboardActions(onDone={keyboard?.hide();focus.clearFocus()}))
                if(ui.reconnectNeeded) Text(tr("设置已保存，下次连接生效。","Settings saved. Applies next connection."),fontSize=13.sp,color=MaterialTheme.colorScheme.primary)
                if(ui.notice.isNotEmpty()) Text(tr(ui.notice,receiverStatusEnglish(ui.notice)),
                    Modifier.semantics {liveRegion=LiveRegionMode.Polite},fontSize=14.sp)
                Row(Modifier.weight(1f),horizontalArrangement=Arrangement.spacedBy(20.dp)) {
                    val groups=listOf(tr("连接","Connection"),tr("音频与画面","Audio & display"),tr("外观","Appearance"),tr("方向盘","Steering wheel"))
                    if(wide && query.isBlank()) Column(Modifier.width(180.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                        groups.forEachIndexed {index,label ->
                            Surface(onClick={settingsGroup=index;ui.audit("settings_group","index=$index")},shape=ReceiverTokens.control,
                                color=if(index==settingsGroup) MaterialTheme.colorScheme.primaryContainer.copy(alpha=.75f) else Color.Transparent) {
                                Text(label,Modifier.fillMaxWidth().padding(16.dp),fontSize=15.sp,fontWeight=FontWeight.SemiBold)
                            }
                        }
                    }
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    fun matches(vararg words:String)=if(query.isNotBlank()) words.any {it.contains(query,true)} else !wide || words.firstOrNull()==groups[settingsGroup]
                    @Composable fun section(title:String) { Text(title,Modifier.padding(top=16.dp,bottom=4.dp),fontSize=14.sp,fontWeight=FontWeight.SemiBold,color=MaterialTheme.colorScheme.primary) }
                    val connection=tr("连接","Connection");val playback=tr("音频与画面","Audio & display");val appearance=tr("外观","Appearance");val diagnostics=tr("诊断","Diagnostics")
                    val wheel=tr("方向盘","Steering wheel")
                    var found=false
                    if(matches(connection,"自动连接","打开","启动","auto","launch")) {found=true;section(connection);AutoConnectSetting(ui)}
                    if(matches(connection,"蓝牙配对","Bluetooth")) {found=true;section(connection);SettingsRow(tr("蓝牙配对","Pair Bluetooth"),tr("打开系统蓝牙设置","Open system Bluetooth settings"),ui.editable,onBluetooth)}
                    if(matches(connection,"iPhone","手机")) {found=true;SettingsRow(tr("已配对 iPhone","Paired iPhone"),(if(ui.phoneName=="自动选择已配对的 iPhone") tr(ui.phoneName,"Choose paired iPhone automatically") else ui.phoneName),ui.editable,onPhone)}
                    if(matches(playback,"音频","缓冲","audio","buffer")) {
                        found=true;section(playback);SettingsRow(tr("音频缓冲","Audio buffer"),"${ui.bufferMs}ms · "+tr("下次连接生效","Next connection"),ui.editable) {ui.audit("audio_picker_open","");ui.dialog=ReceiverDialog.AUDIO}
                    }
                    if(matches(playback,"通道","映射","音乐","导航","电话","语音","铃声","routing","channel","Siri")) {
                        found=true;section(tr("音频通道映射","Audio output mapping"))
                        DisposableEffect(Unit) {onDispose {ui.stopAudioTest()}}
                        Text(tr("每项可播放 5 秒低电平测试音。先调低原车音量，再调整对应滑块核对；测试前请断开 CarPlay。","Each test plays a low-level tone for 5 seconds. Start with low car volume and adjust the corresponding slider. Disconnect CarPlay before testing."),fontSize=13.sp)
                        Text(ui.audioTestMessage.ifBlank {tr("选择一项开始测试，可随时停止。测试音不能代替真实 CarPlay 通话或导航验证。","Choose a test and stop at any time. Tones do not replace real CarPlay call or guidance validation.")},
                            Modifier.fillMaxWidth().height(ReceiverTokens.audioTestStatus).verticalScroll(rememberScrollState()).semantics {liveRegion=LiveRegionMode.Polite},fontSize=13.sp,lineHeight=18.sp)
                        Text(tr("下次连接生效。让原车对应音量分组控制 CarPlay；手动改错可恢复默认。","Applies next connection. Use the car's volume groups for CarPlay; reset defaults if needed."),fontSize=13.sp)
                        ReceiverAudioRole.entries.forEach {role ->
                            val choice=ui.audioMappings[role] ?: ReceiverAudioRouting.AUTO
                            val bus=ReceiverAudioRouting.bus(role,choice,Build.MODEL)
                            val note=if(bus==null) "" else if(bus in ui.audioOutputBuses) tr(" · 通道已检测"," · Output detected") else tr(" · 当前未检测，连接时回退系统"," · Not detected; system fallback if unavailable")
                            SettingsRow(tr(role.title,role.english),tr(ReceiverAudioRouting.title(choice),ReceiverAudioRouting.english(choice))+note,ui.displayEditable) {
                                ui.stopAudioTest();ui.refreshAudioOutputs();ui.audioMappingRole=role;ui.dialog=ReceiverDialog.AUDIO_ROUTE
                            }
                            val testing=ui.testingAudioRole==role
                            val testLabel=if(testing) tr("停止测试 · 剩余 ${ui.audioTestRemaining} 秒","Stop test · ${ui.audioTestRemaining}s remaining") else tr("测试播放 5 秒","Play test for 5s")
                            val testDescription=tr(role.title,role.english)+"，"+testLabel
                            OutlinedButton({if(testing) ui.stopAudioTest() else ui.testAudio(role)},
                                enabled=testing || (ui.editable && ui.testingAudioRole==null),modifier=Modifier.heightIn(min=ReceiverTokens.touch).semantics {contentDescription=testDescription}) {
                                Text(testLabel)
                            }
                        }
                        TextButton({ui.resetAudioMappings()},enabled=ui.displayEditable) {Text(tr("恢复默认音频映射","Reset audio mapping"))}
                        Text(tr("通道已检测不代表已成功切换；以连接诊断和实车音量效果为准。通话与铃声在本车配置中共用音量组。","Detection does not prove routing; verify diagnostics and actual volume behavior. Calls and ringtone share a volume group on this car."),fontSize=13.sp)
                    }
                    if(matches(playback,"低延迟","解码","latency","decoder")) {
                        found=true
                        Row(Modifier.fillMaxWidth().heightIn(min=64.dp),verticalAlignment=Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(tr("低延迟解码（实验性）","Low-latency decoding (experimental)"),fontSize=15.sp)
                                Text(tr("复用原版解码与独立输出线程，下次连接生效。开启后若画面异常可关闭恢复标准模式。","Uses upstream decoder tuning and a dedicated output thread. Applies next connection; switch off to restore standard mode if needed."),fontSize=13.sp)
                            }
                            Switch(ui.lowLatencyVideo,{ui.saveLowLatencyVideo(it)},enabled=ui.displayEditable)
                        }
                    }
                    if(matches(wheel,"按键","映射","下一首","上一首","播放","暂停","wheel","key","MODE","Siri")) {
                        found=true;section(tr("方向盘按键","Steering-wheel keys"))
                        ReceiverWheelSettings(ui)
                    }
                    if(matches(playback,"帧率","fps","frame")) {found=true;SettingsRow(tr("CarPlay 帧率","CarPlay frame rate"),"${ui.fps} fps · "+tr("下次连接生效","Next connection"),ui.editable) {ui.audit("fps_picker_open","");ui.dialog=ReceiverDialog.FPS}}
                    if(matches(playback,"清晰度","分辨率","resolution","quality")) {found=true;SettingsRow(tr("画面清晰度（实验性）","Picture detail (experimental)"),(if(ui.quality==125) tr("高清 125%","HD 125%") else tr("标准 · 匹配屏幕","Standard · match screen"))+tr(" · 下次连接生效"," · Next connection"),ui.displayEditable) {ui.dialog=ReceiverDialog.QUALITY}}
                    if(matches(playback,"界面大小","图标","缩放","size","scale")) {found=true;SettingsRow(tr("CarPlay 界面大小（实验性）","CarPlay size (experimental)"),"${ui.carPlaySize}% · "+tr("下次连接生效","Next connection"),ui.displayEditable) {ui.dialog=ReceiverDialog.SIZE}}
                    if(matches(playback,"菜单栏","位置","dock","position")) {found=true;SettingsRow(tr("CarPlay 菜单栏位置","CarPlay dock position"),ReceiverDockPosition.from(ui.dockPosition).title+tr(" · 下次连接生效"," · Next connection"),ui.displayEditable) {ui.dialog=ReceiverDialog.DOCK}}
                    if(matches(playback,"画面信息","display" ) && ui.displayApplied.isNotBlank()) {found=true;Text(ui.displayApplied,fontSize=13.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)}
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
                    if(matches(appearance,"全屏","状态栏","侧边栏","导航栏","fullscreen","system bars","sidebar")) {
                        found=true
                        Row(Modifier.fillMaxWidth().heightIn(min=56.dp),verticalAlignment=Alignment.CenterVertically) {
                            Column(Modifier.weight(1f).padding(end=12.dp)) {
                                Text(tr("隐藏状态栏和侧边栏","Hide status and navigation bars"),fontSize=16.sp)
                                Text(tr("仅在本应用内生效。车机侧栏是否支持取决于系统；边缘滑动可临时呼出。连接中不可修改。",
                                    "Applies in this app only. OEM sidebars may remain. Swipe from an edge to reveal bars. Change while disconnected."),
                                    fontSize=14.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Switch(ui.hideSystemBars,onCheckedChange=onFullscreen,enabled=ui.editable,
                                modifier=Modifier.semantics {contentDescription="隐藏状态栏和侧边栏 / Hide system bars"})
                        }
                    }

                    if(!found) Text(tr("未找到设置，请换个关键词。","No settings found. Try another keyword."),Modifier.padding(16.dp))
                    }
                }
            }
        }
    }
}
@Composable internal fun AutoConnectSetting(ui:ReceiverUiState) {
    val label=tr("打开 DiPlay 自动连接","Connect when opening DiPlay")
    Row(Modifier.fillMaxWidth().heightIn(min=ReceiverTokens.touch),verticalAlignment=Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end=ReceiverTokens.gap)) {
            Text(label,fontSize=15.sp)
            Text(tr("默认关闭，下次打开时生效。网络、蓝牙、权限及已保存配置就绪后尝试一次；已连接不重复连接，取消后不在当前页面反复尝试。不会打开 Wi-Fi。","Off by default; applies next time you open the app. Tries once when network, Bluetooth, permissions and saved settings are ready. Existing sessions are retained. Does not turn on Wi-Fi."),fontSize=13.sp)
        }
        Switch(ui.autoConnectOnOpen,{ui.saveAutoConnectOnOpen(it)},modifier=Modifier.heightIn(min=ReceiverTokens.touch)
            .semantics {contentDescription=label})
    }
}

@Composable internal fun ReceiverWheelSettings(ui:ReceiverUiState) {
    DisposableEffect(Unit) {onDispose {ui.stopWheelDetection()}}
    Text(tr("默认使用已实车确认的吉利按键。点击一项更改短按操作，保存后立即生效。","Defaults use verified Geely keys. Select a row to change its short-press action; changes apply immediately."),fontSize=13.sp)
    ReceiverWheelButton.entries.forEach {button ->
        val action=ui.wheelMappings[button.key] ?: button.defaultAction
        SettingsRow(tr(button.title,button.english),tr(ReceiverWheelMapping.title(action),ReceiverWheelMapping.english(action))+" · ${button.key}",ui.displayEditable && !ui.wheelDetecting) {
            ui.wheelMappingKey=button.key;ui.dialog=ReceiverDialog.WHEEL_MAPPING
        }
        OutlinedButton({ui.testWheelAction(action)},enabled=ui.connected && !ui.stopping && !ui.wheelDetecting && action!=0,modifier=Modifier.heightIn(min=48.dp)) {
            Text(tr("测试控制：","Test control: ")+tr(ReceiverWheelMapping.title(action),ReceiverWheelMapping.english(action)))
        }
    }
    Text(tr("测试控制会向当前 CarPlay 发送一次操作，请核对手机或 CarPlay 是否响应；它不代表实体按键已识别。","Control tests send one action to connected CarPlay. Verify its response; this does not test physical key reception."),fontSize=13.sp)
    Text(tr("实体按键检测持续 20 秒，只显示收到的按键，不执行 CarPlay 操作。原车自身操作仍可能触发。","Physical-key detection lasts 20 seconds and reports input without forwarding it to CarPlay. Factory actions may still occur."),fontSize=13.sp)
    OutlinedButton({if(ui.wheelDetecting) ui.stopWheelDetection() else ui.startWheelDetection()},enabled=!ui.stopping && (!ui.busy || ui.connected),modifier=Modifier.heightIn(min=48.dp)) {
        Text(if(ui.wheelDetecting) tr("停止按键检测","Stop key detection") else tr("检测实体按键","Detect physical keys"))
    }
    if(ui.wheelMessage.isNotEmpty()) Text(ui.wheelMessage,Modifier.semantics {liveRegion=LiveRegionMode.Polite},fontSize=14.sp)
    if(ui.wheelLastEvent.isNotEmpty()) Text(ui.wheelLastEvent,Modifier.semantics {liveRegion=LiveRegionMode.Polite},fontSize=14.sp)
    Text(tr("MODE 长按 → Siri：仅连接成功后启用；短按切换原车音源。","MODE long press → Siri: available only after connection. Short press keeps the factory source switch."),fontSize=13.sp)
    TextButton({ui.resetWheelMappings()},enabled=ui.displayEditable && !ui.wheelDetecting,modifier=Modifier.heightIn(min=48.dp)) {Text(tr("恢复吉利默认映射","Restore Geely defaults"))}
}

@Composable private fun SettingsRow(title:String,subtitle:String,enabled:Boolean,onClick:()->Unit) {
    Surface(onClick,enabled=enabled,shape=ReceiverTokens.control,color=MaterialTheme.colorScheme.surface.copy(alpha=.7f),modifier=Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(title,fontSize=16.sp,color=MaterialTheme.colorScheme.onSurface)
            Text(subtitle,fontSize=14.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
@Composable private fun DiagnosticPage(ui:ReceiverUiState,onUpload:(Boolean)->Unit,onLogs:()->Unit) {
    val clipboard=LocalClipboardManager.current
    Column(Modifier.fillMaxSize(),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        PageHeading(tr("诊断","Diagnostics"),tr("记录问题，方便下次排查","Record an issue for troubleshooting"))
        GlassSurface(Modifier.fillMaxWidth().weight(1f)) {
            Column(Modifier.fillMaxSize().padding(20.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                Text("连接自检",fontSize=16.sp)
                SelectionContainer {Text(ui.connectionCheck,fontSize=14.sp)}
                Text("运行指标",fontSize=16.sp)
                SelectionContainer {Text(ui.runtimeHealth,fontSize=14.sp)}
                if(ui.timingReport.isNotBlank()) SelectionContainer {Text(ReceiverConnectionTiming.describe(ui.timingReport),fontSize=14.sp)}
                if(ui.displayApplied.isNotBlank()) Text(ui.displayApplied,fontSize=13.sp)
                Text(tr("持续记录本应用操作与连接诊断，保留最近7天、最多约8MB；不含密码、密钥、输入内容、录音或画面。只在你点击后上传。",
                    "App operations and connection diagnostics: up to 7 days / about 8MiB. No credentials, input content, recordings or frames. Upload only when requested."),fontSize=14.sp)
                OutlinedTextField(ui.issue,{ui.audit("issue_note_edited","");ui.issue=it.take(1000)},Modifier.fillMaxWidth(),label={Text(tr("问题说明（可选）","Issue notes (optional)"))},enabled=!ui.uploadBusy,minLines=2,maxLines=3)
                if(!ui.uploadPaired) OutlinedButton({ui.audit("upload_pair_dialog_open","");ui.dialog=ReceiverDialog.PAIR_UPLOAD},enabled=!ui.uploadBusy) {Text(tr("连接日志服务器（首次）","Connect log server (first use)"))}
                Button({onUpload(false)},enabled=!ui.uploadBusy) {Text(if(ui.uploadBusy) tr("正在处理…","Working…") else tr("上传诊断日志","Upload diagnostics"))}
                if(ui.pendingUpload) TextButton({onUpload(true)},enabled=!ui.uploadBusy) {Text(tr("重试上次上传","Retry previous upload"))}
                if(ui.uploadMessage.isNotBlank()) Text(ui.uploadMessage,Modifier.semantics {liveRegion=LiveRegionMode.Polite},fontSize=14.sp)
                if(ui.lastReport.isNotBlank()) {
                    SelectionContainer {Text(tr("最近诊断编号：","Latest report: ")+ui.lastReport,fontSize=14.sp)}
                    TextButton({clipboard.setText(androidx.compose.ui.text.AnnotatedString(ui.lastReport));ui.audit("report_id_copied","report=${ui.lastReport}")}) {Text(tr("复制诊断编号","Copy report ID"))}
                }
                TextButton({ui.audit("local_log_preview","");ui.refreshLocalLog();ui.dialog=ReceiverDialog.LOGS}) {Text(tr("查看本地记录","View local log"))}
                TextButton({ui.exportLocalLog()},enabled=!ui.uploadBusy) {Text(tr("导出本地诊断文件","Export local diagnostics"))}
                if(ui.uploadPaired) TextButton({ui.dialog=ReceiverDialog.PAIR_UPLOAD},enabled=!ui.uploadBusy) {Text(tr("重新配对日志服务器","Pair log server again"))}
            }
        }
    }
}

@Composable private fun ReceiverDialogs(ui:ReceiverUiState,onPhone:(String?,String)->Unit,onPlayback:(Boolean,Int)->Unit,onDisplay:(Boolean,Int)->Unit,onDock:(Int)->Unit,onUpload:(Boolean)->Unit,onPairUpload:(String)->Unit) {
    val dialog=ui.dialog ?: return
    if(dialog==ReceiverDialog.DIAGNOSTICS) return
    if(dialog==ReceiverDialog.PAIR_UPLOAD) {
        var code by remember {mutableStateOf("")}
        ReceiverGlassDialog(onDismissRequest={ui.dialog=ReceiverDialog.DIAGNOSTICS},title={Text(tr("连接日志服务器","Connect log server"))},text={
            Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
                Text(tr("输入服务器生成的一次性配对码。以后上传无需再填写；配对凭据仅允许上传，不能查询服务器日志。",
                    "Enter the server's one-time pairing code. Future uploads need no code. This credential permits upload only, not report retrieval."),fontSize=14.sp)
                OutlinedTextField(code,{code=it.take(12)},singleLine=true,label={Text(tr("12位配对码","12-character code"))},visualTransformation=PasswordVisualTransformation(),keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Ascii,imeAction=ImeAction.Done),enabled=!ui.uploadBusy)
                if(ui.uploadMessage.isNotBlank()) Text(ui.uploadMessage,fontSize=14.sp)
            }
        },confirmButton={TextButton({onPairUpload(code)},enabled=!ui.uploadBusy && code.length==12) {Text(tr("配对","Pair"))}},dismissButton={TextButton({ui.dialog=ReceiverDialog.DIAGNOSTICS}) {Text(tr("返回","Back"))}})
        return
    }
    if(dialog==ReceiverDialog.LOGS) {
        ReceiverGlassDialog(onDismissRequest={ui.dialog=ReceiverDialog.DIAGNOSTICS},title={Text(tr("本地连接记录","Local connection log"))},
            text={SelectionContainer {Text(ui.logs.takeLast(12000),Modifier.heightIn(max=360.dp).verticalScroll(rememberScrollState()),fontSize=14.sp)}},
            confirmButton={TextButton({ui.dialog=ReceiverDialog.DIAGNOSTICS}) {Text(tr("返回","Back"))}})
        return
    }
    if(dialog==ReceiverDialog.WHEEL_MAPPING) {
        val button=ReceiverWheelButton.entries.first {it.key==ui.wheelMappingKey}
        ReceiverGlassDialog(onDismissRequest={ui.dialog=null},title={Text(tr(button.title,button.english))},text={
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(tr("保存后立即生效，不会断开 CarPlay。只调整短按操作；MODE 长按仍用于 Siri。","Changes apply immediately without disconnecting. MODE long press remains Siri."),fontSize=14.sp)
                ReceiverWheelMapping.choices.forEach {action ->
                    val selected=ui.wheelMappings[button.key]==action
                    Row(Modifier.fillMaxWidth().heightIn(min=56.dp).clickable(enabled=ui.displayEditable) {
                        ui.saveWheelMapping(button.key,action);ui.dialog=null
                    }.semantics {role=Role.RadioButton;this.selected=selected},verticalAlignment=Alignment.CenterVertically) {
                        RadioButton(selected,null,enabled=ui.displayEditable)
                        Text(tr(ReceiverWheelMapping.title(action),ReceiverWheelMapping.english(action)),fontSize=16.sp)
                    }
                }
            }
        },confirmButton={TextButton({ui.dialog=null}) {Text(tr("取消","Cancel"))}})
        return
    }
    if(dialog==ReceiverDialog.AUDIO_ROUTE) {
        val role=ui.audioMappingRole
        val choice=ui.audioMappings[role] ?: ReceiverAudioRouting.AUTO
        ReceiverGlassDialog(onDismissRequest={ui.dialog=null},title={Text(tr(role.title,role.english)+tr("输出通道"," output"))},text={
            Column(Modifier.heightIn(max=360.dp).verticalScroll(rememberScrollState())) {
                Text(tr("下次连接生效。选择原车音量分类对应的输出通道。跨类映射会改变声音分类和优先级；通道不可用时回退系统路由。","Applies next connection. Choose the car volume category. Cross-category mapping also changes audio priority; unavailable outputs use system routing."),fontSize=14.sp)
                ReceiverAudioRouting.choices.forEach {value ->
                    Row(Modifier.fillMaxWidth().heightIn(min=56.dp).clickable(enabled=ui.displayEditable) {
                        ui.saveAudioMapping(role,value);ui.dialog=null
                    }.semantics {this.role=Role.RadioButton;selected=choice==value},verticalAlignment=Alignment.CenterVertically) {
                        RadioButton(choice==value,null,enabled=ui.displayEditable)
                        Column(Modifier.weight(1f).padding(vertical=8.dp)) {
                            Text(tr(ReceiverAudioRouting.title(value),ReceiverAudioRouting.english(value)),fontSize=16.sp)
                            if(value !in listOf(ReceiverAudioRouting.AUTO,ReceiverAudioRouting.SYSTEM)) Text(value+if(value in ui.audioOutputBuses) tr(" · 已检测"," · Detected") else tr(" · 当前未检测"," · Not detected"),fontSize=12.sp)
                        }
                    }
                }
            }
        },confirmButton={TextButton({ui.dialog=null}) {Text(tr("取消","Cancel"))}})
        return
    }
    if(dialog==ReceiverDialog.DOCK) {
        ReceiverGlassDialog(onDismissRequest={ui.dialog=null},title={Text(tr("CarPlay 菜单栏位置","CarPlay dock position"))},text={
            Column(Modifier.heightIn(max=360.dp).verticalScroll(rememberScrollState())) {
                Text(tr("下次连接生效。自动模式：竖屏在底部，横屏在左侧，旋转时不断连。右侧选项采用 CarPlay 右舵布局，部分面板也会换边。","Applies next connection. Auto: bottom in portrait, left in landscape, without disconnecting on rotation. Right uses CarPlay's right-hand-drive layout and may move other panels too."),fontSize=14.sp)
                ReceiverDockPosition.entries.forEach {dock ->
                    val selected=ui.dockPosition==dock.value
                    Row(Modifier.fillMaxWidth().heightIn(min=56.dp).clickable(enabled=ui.displayEditable) {onDock(dock.value);ui.dialog=null}.semantics {role=Role.RadioButton;this.selected=selected},verticalAlignment=Alignment.CenterVertically) {
                        RadioButton(selected,null,enabled=ui.displayEditable)
                        Text(tr(dock.title,when(dock) {ReceiverDockPosition.AUTO->"Auto: portrait bottom / landscape left";ReceiverDockPosition.LEFT->"Left";ReceiverDockPosition.BOTTOM->"Bottom";ReceiverDockPosition.RIGHT->"Right"}),fontSize=16.sp)
                    }
                }
            }
        },confirmButton={TextButton({ui.dialog=null}) {Text(tr("取消","Cancel"))}})
        return
    }
    if(dialog==ReceiverDialog.QUALITY || dialog==ReceiverDialog.SIZE) {
        val quality=dialog==ReceiverDialog.QUALITY
        ReceiverGlassDialog(onDismissRequest={ui.dialog=null},title={Text(if(quality) tr("画面清晰度（实验性）","Picture detail (experimental)") else tr("CarPlay 界面大小（实验性）","CarPlay size (experimental)"))},text={
            Column(Modifier.heightIn(max=360.dp).verticalScroll(rememberScrollState())) {
                Text(if(quality) tr("下次连接生效。高清请求更大画布，增加解码负载；硬件不支持时自动回退。屏幕本身的像素不会增加。","Applies next connection. HD requests a larger canvas with higher load; unsupported hardware falls back. Panel pixels do not increase.")
                    else tr("下次连接生效。沿用原版画布缩放：小界面容纳更多内容，也会改变协商分辨率；实际布局由 iPhone 决定。","Applies next connection. Uses the original canvas scaling; smaller controls fit more content and change the negotiated resolution. iPhone determines the layout."),fontSize=14.sp)
                val choices=if(quality) ReceiverDisplaySettings.qualityChoices else ReceiverDisplaySettings.sizeChoices
                choices.forEach {value ->
                    val selected=value==if(quality) ui.quality else ui.carPlaySize
                    Row(Modifier.fillMaxWidth().heightIn(min=56.dp).clickable(enabled=ui.displayEditable) {onDisplay(quality,value);ui.dialog=null}.semantics {role=Role.RadioButton;this.selected=selected},verticalAlignment=Alignment.CenterVertically) {
                        RadioButton(selected,null,enabled=ui.displayEditable)
                        Text(if(quality) {if(value==100) tr("标准 · 匹配屏幕","Standard · match screen") else tr("高清 · 125% 画布","HD · 125% canvas")} else when(value) {85->tr("小 · 更多内容","Small · more content");115->tr("大 · 更易阅读","Large · easier reading");else->tr("标准","Standard")},fontSize=16.sp)
                    }
                }
            }
        },confirmButton={TextButton({ui.dialog=null}) {Text(tr("取消","Cancel"))}})
        return
    }
    val title=when(dialog) { ReceiverDialog.AUDIO->tr("音频缓冲","Audio buffer");ReceiverDialog.FPS->tr("CarPlay 帧率","CarPlay frame rate");else->tr("已配对 iPhone","Paired iPhone") }
    ReceiverGlassDialog(onDismissRequest={ui.dialog=null},title={Text(title)},text={
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

@Composable private fun ReceiverGlassDialog(onDismissRequest:()->Unit,title:@Composable ()->Unit,text:@Composable ()->Unit,confirmButton:@Composable ()->Unit,dismissButton:(@Composable ()->Unit)?=null) {
    Dialog(onDismissRequest,properties=DialogProperties(usePlatformDefaultWidth=false)) {
        ReceiverScene(Modifier.widthIn(max=560.dp).fillMaxWidth().padding(16.dp).clip(ReceiverTokens.panel)) {
            GlassSurface(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                    ProvideTextStyle(MaterialTheme.typography.titleLarge) {title()}
                    Box(Modifier.weight(1f,fill=false)) {text()}
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.End,verticalAlignment=Alignment.CenterVertically) {dismissButton?.invoke();confirmButton()}
                }
            }
        }
    }
}
