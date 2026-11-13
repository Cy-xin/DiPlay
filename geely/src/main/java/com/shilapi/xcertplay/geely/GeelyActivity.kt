package com.shilapi.xcertplay.geely

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.widget.FrameLayout
import android.view.WindowManager
import com.shilapi.xcertplay.airplay.*
import com.shilapi.xcertplay.media.AndroidMediaSink
import com.shilapi.xcertplay.orchestration.*
import com.shilapi.xcertplay.transport.Iap2IdentificationConfig
import java.io.File
import java.util.concurrent.Executors

/** One explicitly started session; a non-sticky foreground service supports home-screen switching. */
class GeelyActivity : androidx.activity.ComponentActivity() {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var journal: ReceiverDiagnostics
    private lateinit var uploader: ReceiverDiagnosticUpload
    private val uploadWorker=Executors.newSingleThreadExecutor()
    private lateinit var store: ReceiverStore
    private lateinit var root: FrameLayout
    internal val ui = ReceiverUiState()
    private lateinit var video: SurfaceView
    private lateinit var videoViewport:FrameLayout
    private var sessionRotation:ReceiverRotationPlan?=null
    private var previousOrientation=Configuration.ORIENTATION_UNDEFINED
    private var mode = ConnectionMode.CAR_HOTSPOT
    private var phone: String? = null
    private var phoneName = "自动选择已配对的 iPhone"
    private var controller: CarPlayController? = null
    private var sink: AndroidMediaSink? = null
    private var currentSurface: Surface? = null
    private var configuredWidth = 0
    private var configuredHeight = 0
    private var busy = false
    private var currentAirPlaySession: AirPlaySession? = null
    private var connected = false
    private var stopping = false
    private var leaseToken: Any? = null
    private var foreground = false
    private var generation = 0
    private var destroyed = false
    private var pendingPermissions = false
    private var timeout: Runnable? = null
    private var secret = ""
    private val health=ReceiverHealth()
    private val recovery=ReceiverRecovery()
    private var recoveryTask:Runnable?=null
    private var recovering=false
    private fun cancelRecovery() {
        recoveryTask?.let(main::removeCallbacks);recoveryTask=null;recovering=false
    }
    private fun recoverOrEnd(reason:String,transient:Boolean) {
        if(stopping || destroyed) return
        val delay=recovery.nextDelay(transient)
        endAttempt(reason,delay)
    }
    private val connectionTiming=ReceiverConnectionTiming(android.os.SystemClock::elapsedRealtime)
    private var message = "未连接。请先按说明连接网络并配对蓝牙。"
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        journal=ReceiverDiagnostics.get(this)
        uploader=ReceiverDiagnosticUpload(applicationContext,journal)
        ui.audit={event,detail -> journal.event("operation",event,detail)}
        ui.exportLocalLog={exportLocalDiagnostics()}
        ui.saveAudioMapping={role,value -> saveAudioMapping(role,value)}
        ui.resetAudioMappings={resetAudioMappings()}
        ui.refreshAudioOutputs={refreshAudioOutputs()}
        ui.saveLowLatencyVideo={enabled ->
            if(ui.displayEditable) {
                store.prefs.edit().putBoolean("low_latency_video",enabled).apply()
                ui.lowLatencyVideo=enabled;ui.markReconnectNeeded()
                journal.event("display","low_latency_saved","enabled=$enabled next_connection=true")
            }
        }
        ui.refreshLocalLog={uploadWorker.execute {
            val text=runCatching {journal.recent()}.getOrDefault("日志暂时不可用")
            main.post {if(!destroyed) ui.logs=text}
        }}
        journal.event("lifecycle","activity_create")
        ui.uploadPaired=uploader.enrolled;ui.lastReport=uploader.lastReport
        store=ReceiverStore(this)
        previousOrientation=resources.configuration.orientation
        ui.timingReport=store.prefs.getString("last_connection_timing","").orEmpty()
        ui.displayApplied=store.prefs.getString("last_display_summary","").orEmpty()
        window.setDecorFitsSystemWindows(false)
        window.statusBarColor=Color.TRANSPARENT;window.navigationBarColor=Color.TRANSPARENT
        onBackPressedDispatcher.addCallback(this,object:androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                ui.audit("system_back","")
                if(ui.dismissTopLayer()) return
                if(connected && ui.showCarPlay) showReceiverHome()
                else if(busy || connected) moveTaskToBack(true)
                else { isEnabled=false;onBackPressedDispatcher.onBackPressed() }
            }
        })
        mode=runCatching { ConnectionMode.valueOf(store.prefs.getString("mode", "CAR_HOTSPOT")!!) }.getOrDefault(ConnectionMode.CAR_HOTSPOT)
        phone=store.prefs.getString("phone", null); phoneName=store.prefs.getString("phone_name", phoneName)!!
        buildUi()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
    private fun buildUi() {
        root=FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        videoViewport=FrameLayout(this).apply {clipChildren=true;clipToPadding=true}
        video=SurfaceView(this)
        videoViewport.addView(video,FrameLayout.LayoutParams(-1,-1))
        root.addView(videoViewport, FrameLayout.LayoutParams(-1,-1))
        root.addOnLayoutChangeListener {_,left,top,right,bottom,oldLeft,oldTop,oldRight,oldBottom ->
            if(right-left!=oldRight-oldLeft || bottom-top!=oldBottom-oldTop) root.post {if(!destroyed) fitSessionSurface()}
        }
        video.holder.addCallback(object: SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) { journal.event("video","surface_created");currentSurface=holder.surface; sink?.setSurface(110,holder.surface) }
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) {journal.event("video","surface_changed","width=$w height=$h format=$format")}
            override fun surfaceDestroyed(holder: SurfaceHolder) {
                journal.event("video","surface_destroyed");currentSurface=null
                // Upstream's bounded detach handshake must complete before Surface destruction returns.
                sink?.beginSurfaceDetach(holder.surface, connected)?.await()
            }
        })
        video.setOnTouchListener { view,event ->
            if (connected && ui.showCarPlay) {
                if(event.actionMasked!=MotionEvent.ACTION_MOVE) journal.event("touch","carplay_touch","action=${event.actionMasked} pointers=${event.pointerCount}")
                val contacts=(0 until minOf(event.pointerCount,2)).map { i ->
                    val released=event.actionMasked==MotionEvent.ACTION_UP || event.actionMasked==MotionEvent.ACTION_CANCEL || (event.actionMasked==MotionEvent.ACTION_POINTER_UP && i==event.actionIndex)
                    AirPlayContact(event.getPointerId(i), (event.getX(i)/view.width).toDouble().coerceIn(0.0,1.0), (event.getY(i)/view.height).toDouble().coerceIn(0.0,1.0), !released)
                }
                controller?.sendTouch(contacts)
                if (event.actionMasked==MotionEvent.ACTION_UP) view.performClick()
            }
            true
        }
        val compose=androidx.compose.ui.platform.ComposeView(this).apply {
            setContent {
                ReceiverApp(ui,
                    onMode={ switchMode(it) },
                    onUpload={retry -> uploadDiagnostics(retry) },
                    onPairUpload={code -> pairDiagnostics(code) },
                    onPrimary={ journal.event("operation","primary_pressed","busy=$busy stopping=$stopping");if(connected) showCarPlay() else if(recovering || busy) endAttempt("已取消连接") else {recovery.reset();connect()} },
                    onDisconnect={ journal.event("operation","disconnect_pressed");endAttempt("已断开，可重新连接") },
                    onBluetooth={ journal.event("operation","open_system_bluetooth");saveInputs();startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) },
                    onPhone={ loadPhoneChoices() },
                    onPhoneSelected={ address,name ->
                        if(!busy && !stopping) {
                            journal.event("operation","phone_selected","automatic=${address==null}");phone=address;phoneName=name;ui.phoneName=name
                            store.prefs.edit().putString("phone",phone).putString("phone_name",phoneName).apply()
                        }
                    },
                    onPlayback={ audio,value ->
                        if(!busy && !stopping) {
                            journal.event("operation","playback_changed","setting=${if(audio) "buffer" else "fps"} value=$value");store.prefs.edit().putInt(if(audio) "media_buffer_ms" else "video_fps",value).apply()
                            refreshPlaybackLabels()
                        }
                    },
                    onDisplay=::saveDisplayPreference,
                    onDock=::saveDockPreference,
                    onFullscreen={ hidden ->
                        if(ui.editable) {
                            journal.event("operation","system_bars_changed","hidden=$hidden");ui.hideSystemBars=hidden
                            store.prefs.edit().putBoolean("hide_system_bars",hidden).apply()
                            ReceiverSystemBars.apply(this@GeelyActivity,hidden)
                        }
                    },
                    onLogs={ loadLogs() })
            }
        }
        root.addView(compose,FrameLayout.LayoutParams(-1,-1));setContentView(root)
        loadInputs();refreshPlaybackLabels();ui.hideSystemBars=store.prefs.getBoolean("hide_system_bars",false);applyState()
    }
    internal fun switchMode(next:ConnectionMode) {
        journal.event("operation","connection_mode_selected","mode=$next blocked=${busy || stopping}")
        if(busy || stopping || next==mode) return
        saveInputs();mode=next;store.prefs.edit().putString("mode",mode.name).apply();loadInputs()
    }
    internal fun saveDisplayPreference(quality:Boolean,value:Int) {
        if(!ui.displayEditable) return
        val saved=if(quality) ReceiverDisplaySettings.quality(value) else ReceiverDisplaySettings.size(value)
        store.prefs.edit().putInt(if(quality) "display_quality" else "carplay_ui_size",saved).apply()
        ui.markReconnectNeeded();refreshPlaybackLabels()
        journal.event("operation","display_preference_saved","setting=${if(quality) "quality" else "size"} value=$saved next_connection=true")
    }
    internal fun saveDockPreference(value:Int) {
        if(!ui.displayEditable) return
        val dock=ReceiverDockPosition.from(value)
        store.prefs.edit().putInt("carplay_dock",dock.value).apply()
        ui.markReconnectNeeded();refreshPlaybackLabels()
        journal.event("operation","dock_preference_saved","mode=${dock.name} next_connection=true")
    }
    private fun refreshAudioOutputs() {
        ui.audioOutputBuses=com.shilapi.xcertplay.media.AudioOutputDevice.outputs(getSystemService(android.media.AudioManager::class.java))
            .filter {it.type==android.media.AudioDeviceInfo.TYPE_BUS}.map {it.address}.toSet()
    }
    internal fun saveAudioMapping(role:ReceiverAudioRole,value:String) {
        if(!ui.displayEditable) return
        val choice=ReceiverAudioRouting.sanitize(value)
        store.prefs.edit().putString(role.preference,choice).apply()
        ui.audioMappings=ui.audioMappings+(role to choice);ui.markReconnectNeeded()
        journal.event("audio","output_mapping_saved","role=${role.name} choice=$choice next_connection=true")
    }
    internal fun resetAudioMappings() {
        if(!ui.displayEditable) return
        store.prefs.edit().apply {ReceiverAudioRole.entries.forEach {remove(it.preference)}}.apply()
        ui.audioMappings=ReceiverAudioRole.entries.associateWith {ReceiverAudioRouting.AUTO};ui.markReconnectNeeded()
        journal.event("audio","output_mapping_reset","next_connection=true")
    }
    private fun refreshPlaybackLabels() {
        ui.lowLatencyVideo=store.prefs.getBoolean("low_latency_video",false)
        ui.audioMappings=ReceiverAudioRole.entries.associateWith {ReceiverAudioRouting.sanitize(store.prefs.getString(it.preference,null))}
        refreshAudioOutputs()
        ui.bufferMs=ReceiverPlaybackSettings.sanitizeBuffer(store.prefs.getInt("media_buffer_ms",200))
        ui.fps=ReceiverPlaybackSettings.sanitizeFps(store.prefs.getInt("video_fps",30))
        ui.quality=ReceiverDisplaySettings.quality(store.prefs.getInt("display_quality",100))
        ui.carPlaySize=ReceiverDisplaySettings.size(store.prefs.getInt("carplay_ui_size",100))
        ui.dockPosition=ReceiverDockPosition.from(store.prefs.getInt("carplay_dock",0)).value
    }
    private fun key(name: String) = "${mode.name}_$name"
    private fun loadInputs() {
        ui.mode=mode;ui.ssid=store.prefs.getString(key("ssid"),"").orEmpty()
        ui.password=store.prefs.getString(key("password"),"").orEmpty()
        ui.networkInterface=store.prefs.getString("interface","wlan0").orEmpty()
        ui.phoneName=phoneName
        journal.protect(ui.ssid,ui.password,phoneName)
    }
    private fun saveInputs() {
        journal.protect(ui.ssid,ui.password,phoneName)
        store.prefs.edit().putString(key("ssid"),ui.ssid).putString(key("password"),ui.password)
            .putString("interface",ui.networkInterface.trim()).apply()
    }
    private fun missingPermissions(): Array<String> = (listOf(Manifest.permission.RECORD_AUDIO) + if(Build.VERSION.SDK_INT>=31) listOf(Manifest.permission.BLUETOOTH_CONNECT) else emptyList()).filter { checkSelfPermission(it)!=PackageManager.PERMISSION_GRANTED }.toTypedArray()
    private fun requestNecessaryPermissions(): Boolean {
        val missing=missingPermissions()
        if(missing.isEmpty()) return true
        update("CarPlay 音频协商需要麦克风权限；仅在 iPhone 请求麦克风通道时采集，不保存录音。")
        journal.event("permissions","request","permissions=${missing.joinToString()}");pendingPermissions=true;requestPermissions(missing,10);return false
    }
    override fun onRequestPermissionsResult(code:Int, permissions:Array<out String>, results:IntArray) {
        super.onRequestPermissionsResult(code,permissions,results)
        if(code==10 && pendingPermissions) {
            journal.event("permissions","result","granted=${missingPermissions().isEmpty()} denied=${missingPermissions().joinToString()}")
            pendingPermissions=false
            if(missingPermissions().isEmpty()) connect() else update(if(Manifest.permission.RECORD_AUDIO in missingPermissions()) "麦克风权限未允许，无法提供完整 CarPlay 音频能力。请允许麦克风权限后重试。" else "蓝牙权限被拒绝。请在系统应用权限中允许附近设备，然后重试。")
        }
    }
    private fun loadPhoneChoices() {
        journal.event("operation","phone_picker_open")
        try {
            if(missingPermissions().isNotEmpty()) { update("请先点击连接并允许所需权限，再选择手机。");return }
            val devices=getSystemService(BluetoothManager::class.java)?.adapter?.bondedDevices?.toList().orEmpty()
            if(devices.isEmpty()) { update("没有已配对手机，请先在设置中打开蓝牙配对。");return }
            devices.forEach {journal.protect(it.name.orEmpty(),it.address)}
            ui.phoneChoices=listOf(null to "自动选择已配对的 iPhone")+devices.map { it.address to (it.name ?: "已配对设备") }
            ui.dialog=ReceiverDialog.PHONE
        } catch (_: SecurityException) { update("蓝牙权限已变化，请允许权限后重试。") }
    }
    private fun connect() {
        if(busy || stopping || controller!=null || destroyed || !foreground) {journal.event("connection","duplicate_or_inactive_blocked");return}
        if(!requestNecessaryPermissions()) return
        try {getSystemService(BluetoothManager::class.java)?.adapter?.bondedDevices?.forEach {journal.protect(it.name.orEmpty(),it.address)}} catch(_:SecurityException) {journal.event("permissions","device_identity_unavailable",level="WARN")}
        journal.attempt=java.util.UUID.randomUUID().toString()
        journal.event("connection","attempt_start","mode=$mode bufferMs=${ui.bufferMs} fps=${ui.fps} interface=${ui.networkInterface.take(32).replace(Regex("[^A-Za-z0-9_.:-]"),"?")}")
        networkDiagnostic()
        val lease=Any()
        if (!ReceiverSessionGate.acquire(lease)) { update("上一连接仍在释放，请稍后重试。");return }
        leaseToken=lease
        connectionTiming.start()
        ui.connectionCheck=connectionTiming.checkReport()
        health.reset();ui.runtimeHealth=health.describe()
        saveInputs()
        val config=ReceiverConfig(mode,ui.ssid,ui.password,ui.networkInterface.trim(),phone)
        val mediaBuffer=ReceiverPlaybackSettings.sanitizeBuffer(store.prefs.getInt("media_buffer_ms",200))
        val videoFps=ReceiverPlaybackSettings.sanitizeFps(store.prefs.getInt("video_fps",30))
        val audioMappings=ui.audioMappings.toMap()
        val lowLatencyVideo=ui.lowLatencyVideo
        val displayQuality=ui.quality;val displaySize=ui.carPlaySize
        val dock=ReceiverDockPosition.from(ui.dockPosition)
        secret=config.password
        val token=++generation
        busy=true;update("正在检查配置和认证资产…");applyState()
        ReceiverSessionService.attach(this)
        try {startForegroundService(Intent(this,ReceiverSessionService::class.java))}
        catch(e:RuntimeException) {log("前台连接服务启动失败: ${e.javaClass.simpleName}");endAttempt("无法保持后台连接，请重新打开应用后重试。");return}
        timeout=Runnable { if(token==generation && busy && !connected) endAttempt("连接超时。请确认 iPhone 已加入网络、蓝牙已配对，并在手机上允许 CarPlay；局域网需关闭客户端隔离并允许组播，然后重试。") }.also { main.postDelayed(it,90_000) }
        val width=(video.width/2)*2;val height=(video.height/2)*2
        if(width<2 || height<2) {
            finishTiming("failed")
            busy=false;ReceiverSessionGate.release(leaseToken);leaseToken=null
            timeout?.let(main::removeCallbacks);timeout=null
            stopService(Intent(this,ReceiverSessionService::class.java));ReceiverSessionService.detach(this)
            update("显示区域尚未就绪，请稍后重试。");applyState();return
        }
        configuredWidth=width;configuredHeight=height
        val rotationEnabled=ReceiverRotationPlan.enabled(height>width,store.prefs.getBoolean("rotating_window_seen",false),
            packageManager.hasSystemFeature(PackageManager.FEATURE_SENSOR_ACCELEROMETER),packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE),Build.MODEL.contains("KX11",true))
        worker.execute {
            try {
                config.validate();store.installAuthentication()
                val identity=store.identity();val id=ReceiverStore.deviceId(identity)
                val runtime=CarPlayRuntimeConfig(mfiTarget=MfiTarget.LOCAL, identification=Iap2IdentificationConfig("DiPlay Geely","KX11_A2","DiPlay","DIPLAY-"+id.replace(":",""),"0.1.0","1.0",0),
                    label="DiPlay Geely",hostName="diplay-"+id.replace(":","").lowercase(),hostMac=id.split(":").map { it.toInt(16).toByte() }.toByteArray(),
                    transport=CarPlayTransport.WIRELESS,wirelessBluetoothDeviceAddress=config.phone,
                    wirelessHotspotMode=if(config.mode==ConnectionMode.CAR_HOTSPOT) WirelessHotspotMode.MANUAL else WirelessHotspotMode.EXISTING_WIFI,
                    manualHotspotSsid=config.ssid,manualHotspotPassphrase=config.password,manualHotspotInterface=config.networkInterface,
                    manualHotspotSecurity=if(config.password.isEmpty()) ManualHotspotSecurity.OPEN else ManualHotspotSecurity.WPA2,
                    existingWifiSsid=config.ssid,existingWifiPassphrase=config.password)
                val display=ReceiverDisplaySettings.plan(width,height,videoFps,displayQuality,displaySize)
                val rotating=rotationEnabled
                val square=if(rotating) ReceiverTurningCanvas.squareSide(maxOf(display.display.widthPixels,display.display.heightPixels),ReceiverTurningCanvas.Picture.SMOOTHER,false) else null
                require(height<=width || square!=null) {"当前设备不支持横竖切换画布，请将设备横屏后再连接。"}
                val rotation= square?.let {ReceiverRotationPlan.build(display.display,width,height,it,dock)}
                val air=receiverAirPlayConfig(identity,width,height,videoFps,receiverHomeIcons(applicationContext)).copy(main=rotation?.display ?: dock.singleDisplay(display.display),rightHandDrive=dock.rightHandDrive)
                val pairs=store.pairings()
                main.post {
                    if(destroyed || generation!=token) return@post
                    try {
                        sessionRotation=rotation
                        ui.reconnectNeeded=false
                        ui.displayApplied="请求画布：${air.main.widthPixels} × ${air.main.heightPixels} · ${air.main.fps} fps"+(if(rotation!=null) "（横竖屏旋转画布）" else if(display.fallback) "（设备能力限制，已回退）" else "")
                        store.prefs.edit().putString("last_display_summary",ui.displayApplied).apply()
                        journal.event("display","negotiation_plan","native=${width}x$height requestedQuality=$displayQuality requestedSize=$displaySize usedQuality=${display.quality} usedSize=${display.size} canvas=${air.main.widthPixels}x${air.main.heightPixels} fps=$videoFps fallback=${display.fallback} rotating=$rotating square=$square dock=${dock.name} rightHandDrive=${dock.rightHandDrive}")
                        val audioOutputs=com.shilapi.xcertplay.media.AudioOutputDevice.outputs(getSystemService(android.media.AudioManager::class.java)).map {com.shilapi.xcertplay.media.AudioOutputDevice.from(it)}
                        val audioRoutes=ReceiverAudioRouting.routes(audioMappings,Build.MODEL,audioOutputs)
                        audioMappings.forEach {(role,choice) ->
                            val bus=ReceiverAudioRouting.bus(role,choice,Build.MODEL)
                            journal.event("audio","output_mapping_plan","role=${role.name} choice=$choice bus=${bus ?: "system"} detected=${bus!=null && audioOutputs.any {it.address==bus}}")
                        }
                        val renderer=AndroidMediaSink(videoWidth=air.main.widthPixels,videoHeight=air.main.heightPixels,context=applicationContext,audioFocusEnabled=true,navigationMixEnabled=true,audioOutputRoutes=audioRoutes,audioUsageOverrides=ReceiverAudioRouting.usages(audioMappings),wirelessAudio=true,mediaBufferMillis=mediaBuffer,videoFps=videoFps,mainVideoFrameRate=videoFps,vendorLowLatencyDecoder=lowLatencyVideo,onAudioDiagnostic={ log(it);android.util.Log.i("DiPlay-Audio",it) })
                        sink=renderer;currentSurface?.let { renderer.setSurface(110,it) }
                        val next=CarPlayController(this,runtime,air,identity,pairs,listener(token),CarPlayMediaEngine(renderer,microphoneEnabled=air.microphone),{state -> main.post {onStatus(token,state)} })
                        log("Audio: capability output=${!air.disableAudioOutput} microphone=${air.microphone} mainBuffered=${air.mainBufferedAudio} bufferMs=$mediaBuffer requestedFps=$videoFps")
                        controller=next;root.post {if(!destroyed) fitSessionSurface()};next.start()
                    } catch(e:Exception) { log("启动失败: "+e.javaClass.simpleName);endAttempt("无法启动连接：请检查本地诊断并重试。") }
                }
            } catch(e:Exception) {
                main.post { if(token==generation && !destroyed) {
                    log("预检失败: "+e.javaClass.simpleName)
                    endAttempt(if(e is IllegalArgumentException) e.message ?: "配置无效" else "本地认证资产缺失或无效，无法启动 CarPlay。请使用已配置认证的 APK。")
                } }
            }
        }
    }
    private fun listener(token:Int)=object:AirPlaySessionListener {
        @Volatile private var controlFailure:String?=null
        override fun onTcpAccepted(event:AirPlayTcpAccepted) {main.post {if(token==generation && !destroyed) timingStage(ReceiverConnectionTiming.Stage.HANDSHAKE)}}
        override fun onSessionActive(session:AirPlaySession) { main.post { if(token==generation) { currentAirPlaySession=session;root.post {fitSessionSurface()};timingStage(ReceiverConnectionTiming.Stage.VIDEO); update("CarPlay 会话已建立，正在等待画面…") } } }
        override fun onVideoFrameRendered(session:AirPlaySession) { main.post { if(token==generation && !destroyed && !connected && currentAirPlaySession===session) {
            connected=true;ui.showCarPlay=true;timeout?.let(main::removeCallbacks);finishTiming("success");update("CarPlay 已连接，画面已解码显示");applyState()
        } } }
        override fun onSessionEnded(session:AirPlaySession) { main.post { if(token==generation && currentAirPlaySession===session) recoverOrEnd("iPhone 已断开，可重新连接。",controlFailure?.let(ReceiverRecovery::transient)==true) } }
        override fun onTransportError(message:String) { log("传输失败: $message");main.post { if(token==generation) recoverOrEnd("CarPlay 传输中断。请检查网络和手机上的 CarPlay 授权后重试。",ReceiverRecovery.transient(message)) } }
        override fun onHostUiRequested(session:AirPlaySession) { main.post { if(token==generation && currentAirPlaySession===session) showReceiverHome() } }
        override fun onCommand(session:AirPlaySession,type:String,params:Map<String,Any?>) {
            if(token!=generation || type!="modesChanged") return
            val resources=(params["resources"] as? List<*>).orEmpty().filterIsInstance<Map<*,*>>().map { row ->
                row.filter { (key,value) -> key in setOf("resourceID","entityID","transferType","transferPriority") && (value is Number || value is Boolean) }
            }
            val detail="Audio: modes resources=$resources"
            log(detail);android.util.Log.i("DiPlay-Audio",detail)
        }
        override fun onDebugLog(message:String) {
            if(message.startsWith("airplay control closing reason=control I/O failed:")) controlFailure=message
            if(token==generation) log(message)
        }
    }
    private fun onStatus(token:Int,state:CarPlayStatus) {
        if(token!=generation || destroyed || (connected && state !is CarPlayStatus.Failed)) return
        when(state) {
            CarPlayStatus.DiscoveringMfi -> timingStage(ReceiverConnectionTiming.Stage.AUTH)
            CarPlayStatus.MfiReady,CarPlayStatus.StartingHotspot -> timingStage(ReceiverConnectionTiming.Stage.NETWORK)
            is CarPlayStatus.HotspotReady,CarPlayStatus.WaitingForPairedIphone -> timingStage(ReceiverConnectionTiming.Stage.SELECT_PHONE)
            CarPlayStatus.AttachingNetwork -> timingStage(ReceiverConnectionTiming.Stage.DISCOVERY)
            CarPlayStatus.ConnectingBluetooth -> timingStage(ReceiverConnectionTiming.Stage.BLUETOOTH)
            CarPlayStatus.RunningWireless -> timingStage(ReceiverConnectionTiming.Stage.WAIT_PHONE)
            else -> Unit
        }
        when(state) {
            is CarPlayStatus.Failed -> { log("连接失败: "+state.message);recoverOrEnd("连接失败："+friendlyFailure(state.message),ReceiverRecovery.transient(state.message)) }
            CarPlayStatus.WaitingForPairedIphone -> update("正在选择已配对 iPhone；请保持手机蓝牙开启。")
            CarPlayStatus.ConnectingBluetooth -> update("正在通过蓝牙完成首次认证与网络交接…")
            is CarPlayStatus.HotspotReady -> update("网络接口就绪，正在建立 CarPlay（尚未连接）。")
            CarPlayStatus.RunningWireless -> update("正在等待 iPhone 建立 CarPlay；请在手机上允许连接。")
            CarPlayStatus.MfiReady -> update("本地认证已就绪，正在检查网络…")
            else -> Unit
        }
    }
    private fun timingStage(stage:ReceiverConnectionTiming.Stage) {
        if(connectionTiming.advance(stage)) {ui.connectionCheck=connectionTiming.checkReport();journal.event("connection","timing_stage","stage=${stage.name} elapsedMs=${connectionTiming.elapsedMs()}")}
    }
    private fun finishTiming(result:String) {
        if(connectionTiming.running) ui.connectionCheck=if(result=="success") "连接自检通过：网络、蓝牙交接、协议会话和首帧已完成。声音与触摸请实际体验确认。" else connectionTiming.checkReport(result=="failed")
        val report=connectionTiming.finish(result) ?: return
        ui.timingReport=report
        store.prefs.edit().putString("last_connection_timing",report).apply()
        journal.event("connection","timing_result",report)
    }
    internal fun showReceiverHome() {
        if(!connected) return
        ui.showCarPlay=false;ui.settingsOpen=false;ui.dialog=null
        update("CarPlay 已连接。可返回画面，或主动断开连接。")
        journal.event("operation","receiver_home_shown","session_retained=true")
    }
    private fun showCarPlay() {
        if(!connected) return
        ui.showCarPlay=true;ui.settingsOpen=false;ui.dialog=null
        root.post {fitSessionSurface()}
        journal.event("operation","carplay_shown","session_retained=true")
    }
    internal fun disconnectFromNotification() {if(!destroyed && !stopping) endAttempt("已主动断开，可重新连接。");else stopService(Intent(this,ReceiverSessionService::class.java))}
    override fun onNewIntent(intent:Intent) {
        super.onNewIntent(intent)
        if(intent.action==ReceiverSessionService.ACTION_OPEN) showCarPlay()
    }
    private fun friendlyFailure(message:String):String = when {
        message.contains("paired",true) || message.contains("bonded",true) -> "未找到明确的已配对 iPhone，请先完成蓝牙配对；多部手机时展开配置选择一部。"
        message.contains("bluetoothMac",true) -> "本机蓝牙标识无效，无法启动 CarPlay 握手。请查看本地诊断。"
        message.contains("Bluetooth",true) || message.contains("RFCOMM",true) -> "蓝牙握手失败；普通配对并不代表 CarPlay 握手成功。请查看本地诊断中的具体原因。"
        message.contains("SSID",true) || message.contains("configuration",true) -> "网络配置不匹配，请核对名称、密码和接口。"
        message.contains("network changed",true) -> "Wi-Fi 已变化或断开，请恢复网络后重试。"
        else -> "未能完成 CarPlay 握手。请核对网络、蓝牙配对及手机授权；同一局域网还需允许设备互通和组播。详细阶段见本地诊断。"
    }
    private fun endAttempt(reason:String)=endAttempt(reason,null)
    private fun endAttempt(reason:String,retryDelay:Long?) {
        cancelRecovery()
        if(stopping) return
        journal.event("connection","attempt_ending",reason,if(reason.contains("失败") || reason.contains("超时") || reason.contains("中断")) "ERROR" else "INFO")
        finishTiming(if(reason.contains("取消") || reason.contains("主动断开") || reason.contains("应用已关闭")) "cancelled" else "failed")
        stopService(Intent(this,ReceiverSessionService::class.java));ReceiverSessionService.detach(this)
        ui.showCarPlay=false;sessionRotation=null
        ++generation;timeout?.let(main::removeCallbacks);timeout=null
        currentAirPlaySession=null;connected=false;busy=false;stopping=true;update(reason+"\n正在释放连接…");applyState()
        val old=controller;val renderer=sink;val lease=leaseToken;leaseToken=null;controller=null;sink=null
        // close() cancels the blocking Bluetooth/network operations before queued preparation finishes.
        old?.close()
        worker.execute {
            val transportReleased=old?.awaitClosed(8_000) ?: true
            val mediaReleased=runCatching { renderer?.close() }.isSuccess
            val released=transportReleased && mediaReleased
            journal.event("connection","resources_released","transport=$transportReleased media=$mediaReleased",if(released) "INFO" else "ERROR")
            if (released) ReceiverSessionGate.release(lease)
            main.post { if(!destroyed) {
                stopping=!released
                update(if(released) reason else "连接资源尚未释放，已阻止新任务。请在系统应用信息中强行停止本应用后重新打开。")
                applyState()
                if(released && retryDelay!=null && foreground) {
                    recovering=true;ui.busy=true
                    update(reason+"\n将在 ${retryDelay/1000} 秒后尝试恢复（${recovery.attempts}/2），可点击取消。")
                    journal.event("connection","recovery_scheduled","attempt=${recovery.attempts} delayMs=$retryDelay")
                    recoveryTask=Runnable {
                        recoveryTask=null;recovering=false;ui.busy=false
                        if(!destroyed && foreground && !stopping) connect()
                    }.also {main.postDelayed(it,retryDelay)}
                }
            } }
        }
    }
    private fun fitSessionSurface() {
        if(root.width<=0 || root.height<=0) return
        if((busy || connected) && configuredWidth>0 && configuredHeight>0) {
            val rotation=sessionRotation
            if(rotation!=null) {
                val target=rotation.target(root.width,root.height)
                if(target!=rotation.current && rotation.pending<0) {
                    val token=generation;rotation.pending=target
                    val queued=controller?.showViewArea(target) {sent -> main.post {
                        if(!destroyed && generation==token && sessionRotation===rotation) {
                            rotation.pending=-1
                            if(sent) {rotation.current=target;fitSessionSurface()}
                            journal.event("display","rotation_area_result","index=$target sent=$sent")
                        }
                    }}==true
                    if(!queued) rotation.pending=-1
                }
                val b=rotation.bounds(root.width,root.height)
                videoViewport.layoutParams=FrameLayout.LayoutParams(b.viewportWidth,b.viewportHeight,android.view.Gravity.CENTER)
                video.layoutParams=FrameLayout.LayoutParams(b.surfaceWidth,b.surfaceHeight,android.view.Gravity.TOP or android.view.Gravity.LEFT).apply {leftMargin=b.surfaceLeft;topMargin=b.surfaceTop}
                return
            }
            val scale=minOf(root.width.toFloat()/configuredWidth,root.height.toFloat()/configuredHeight)
            val width=(configuredWidth*scale).toInt().coerceAtLeast(1)
            val height=(configuredHeight*scale).toInt().coerceAtLeast(1)
            videoViewport.layoutParams=FrameLayout.LayoutParams(width,height,android.view.Gravity.CENTER)
            video.layoutParams=FrameLayout.LayoutParams(width,height)
            journal.event("display","surface_fit","width=$width height=$height session_retained=true")
        } else if(!busy && !connected) {
            videoViewport.layoutParams=FrameLayout.LayoutParams(-1,-1)
            video.layoutParams=FrameLayout.LayoutParams(-1,-1)
        }
    }
    private fun applyState() {
        ui.busy=busy;ui.stopping=stopping;ui.connected=connected
        root.post {if(!destroyed) fitSessionSurface()}
        if(connected && ui.showCarPlay) { ui.settingsOpen=false;ui.dialog=null }
    }
    private fun update(value:String) { message=value;ui.message=value;if(ui.settingsOpen) ui.notice=value;log(value) }
    private fun log(value:String) {
        val measurement=value.removePrefix("Video: ")
        if(measurement.startsWith("decoder=") || measurement.startsWith("video stats") || measurement.startsWith("output format requested=") || measurement.startsWith("audio stats ")) {
            val token=generation
            main.post {if(!destroyed && token==generation && (busy || connected) && health.observe(measurement)) ui.runtimeHealth=health.describe()}
        }
        journal.event(if(value.startsWith("Audio:") || value.startsWith("audio ")) "audio" else "protocol","diagnostic",value,
            if(value.contains("失败") || value.contains("exception",true)) "WARN" else "INFO")
    }
    private fun loadLogs() {
        journal.event("operation","diagnostics_open");ui.dialog=ReceiverDialog.DIAGNOSTICS
        uploadWorker.execute {val text=runCatching {journal.recent()}.getOrDefault("日志暂时不可用")
            main.post {if(!destroyed) {ui.logs=text;ui.pendingUpload=journal.pending()!=null;ui.uploadPaired=uploader.enrolled;ui.lastReport=uploader.lastReport}}
        }
    }
    private fun exportLocalDiagnostics() {
        if(ui.uploadBusy) return
        ui.uploadBusy=true;ui.uploadMessage="正在导出脱敏日志…"
        val issue=ui.issue
        uploadWorker.execute {
            val result=runCatching {
                journal.event("operation","local_export_requested")
                val bundle=journal.snapshot(issue)
                val directory=File(requireNotNull(getExternalFilesDir(null)),"diagnostics-export").apply {check(isDirectory || mkdirs())}
                val destination=File(directory,"DiPlay-"+bundle.name)
                bundle.copyTo(destination,overwrite=true)
                directory.listFiles().orEmpty().filter {it!=destination}.forEach {it.delete()}
                destination.name
            }
            main.post {if(!destroyed) {
                ui.uploadBusy=false
                ui.uploadMessage=result.fold({"已导出：Android/data/$packageName/files/diagnostics-export/$it"},{"导出失败：请检查设备存储空间。"})
            }}
        }
    }
    private fun networkDiagnostic() {
        runCatching {
            val manager=getSystemService(android.net.ConnectivityManager::class.java)
            val caps=manager.getNetworkCapabilities(manager.activeNetwork)
            journal.event("network","snapshot","wifi=${caps?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)} cellular=${caps?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR)} validated=${caps?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)}")
        }
    }
    private fun pairDiagnostics(code:String) {
        if(ui.uploadBusy) return
        ui.uploadBusy=true;ui.uploadMessage="正在连接日志服务器…"
        uploadWorker.execute {
            val result=runCatching {uploader.enroll(code)}
            result.exceptionOrNull()?.let {journal.event("upload","pair_failed","type=${it.javaClass.simpleName} message=${it.message.orEmpty()}","ERROR")}
            main.post {if(!destroyed) {
                ui.uploadBusy=false;ui.uploadPaired=uploader.enrolled
                ui.uploadMessage=if(result.isSuccess) "日志服务器已连接，以后可一键上传。" else "连接失败：请检查配对码有效期和网络后重试。"
            }}
        }
    }
    private fun uploadDiagnostics(retry:Boolean) {
        if(ui.uploadBusy) return
        if(!uploader.enrolled) {ui.dialog=ReceiverDialog.PAIR_UPLOAD;return}
        ui.uploadBusy=true;ui.uploadMessage="正在整理并脱敏日志…"
        journal.event("operation","upload_requested","retry=$retry")
        val issue=ui.issue
        uploadWorker.execute {
            val result=runCatching {
                val bundle=if(retry) journal.pending() ?: journal.snapshot(issue) else journal.snapshot(issue)
                uploader.upload(bundle) {done,total ->main.post {if(!destroyed) ui.uploadMessage="上传中：${done/1024}/${total/1024}KB"}}
            }
            main.post {if(!destroyed) {
                ui.uploadBusy=false;ui.uploadPaired=uploader.enrolled;ui.pendingUpload=journal.pending()!=null
                ui.lastReport=uploader.lastReport
                ui.uploadMessage=if(result.isSuccess) "上传成功，诊断编号：${result.getOrNull()}" else "上传失败，日志已保留。请检查互联网连接后点重试；若凭据失效请重新配对。"
            }}
        }
    }
    override fun onConfigurationChanged(config:Configuration) {
        super.onConfigurationChanged(config)
        if(previousOrientation in listOf(Configuration.ORIENTATION_PORTRAIT,Configuration.ORIENTATION_LANDSCAPE) && config.orientation in listOf(Configuration.ORIENTATION_PORTRAIT,Configuration.ORIENTATION_LANDSCAPE) && previousOrientation!=config.orientation) store.prefs.edit().putBoolean("rotating_window_seen",true).apply()
        previousOrientation=config.orientation
        journal.event("display","configuration_changed","orientation=${config.orientation} widthDp=${config.screenWidthDp} heightDp=${config.screenHeightDp} density=${config.densityDpi} fontScale=${config.fontScale} night=${config.uiMode}")
        root.post {fitSessionSurface()}
    }
    override fun dispatchTouchEvent(event:MotionEvent):Boolean {
        if(::journal.isInitialized && (!connected || !ui.showCarPlay) && event.actionMasked!=MotionEvent.ACTION_MOVE)
            journal.event("touch","app_touch","action=${event.actionMasked} pointers=${event.pointerCount} settings=${ui.settingsOpen} dialog=${ui.dialog}")
        return super.dispatchTouchEvent(event)
    }
    override fun onResume() {super.onResume();journal.event("lifecycle","resume")}
    override fun onPause() {journal.event("lifecycle","pause");super.onPause()}
    override fun onStart() { super.onStart();foreground=true;journal.foreground(true);networkDiagnostic();ReceiverSystemBars.apply(this,ui.hideSystemBars) }
    override fun onWindowFocusChanged(hasFocus:Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if(::journal.isInitialized) journal.event("lifecycle","window_focus","focused=$hasFocus")
        if(hasFocus && foreground) ReceiverSystemBars.apply(this,ui.hideSystemBars)
    }
    override fun onStop() { if(recovering) {cancelRecovery();ui.busy=false;update("自动恢复已暂停，请返回后点击连接。")} ;journal.foreground(false);saveInputs();foreground=false;ReceiverSystemBars.apply(this,false);journal.event("connection","background_retained","connected=$connected connecting=$busy");super.onStop() }
    override fun onDestroy() {
        cancelRecovery();saveInputs();if(!stopping && (controller!=null || busy)) endAttempt("应用已关闭")
        journal.event("lifecycle","activity_destroy");uploadWorker.shutdown();destroyed=true;main.removeCallbacksAndMessages(null);worker.shutdown();super.onDestroy()
    }
}
