package com.shilapi.xcertplay.geely

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.*
import com.shilapi.xcertplay.airplay.*
import com.shilapi.xcertplay.media.AndroidMediaSink
import com.shilapi.xcertplay.orchestration.*
import com.shilapi.xcertplay.transport.Iap2IdentificationConfig
import java.io.File
import java.util.concurrent.Executors

/** Foreground-only receiver: one Activity, one explicit attempt, no services or boot receivers. */
class GeelyActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var store: ReceiverStore
    private lateinit var root: FrameLayout
    private lateinit var form: LinearLayout
    private lateinit var setupPage: LinearLayout
    private lateinit var body: LinearLayout
    private lateinit var modes: RadioGroup
    private lateinit var footer: LinearLayout
    private lateinit var configurationScroll: ScrollView
    private lateinit var scrollingContent: LinearLayout
    private var wideLayout: Boolean? = null
    private lateinit var status: TextView
    private lateinit var primary: Button
    private lateinit var modeHotspot: RadioButton
    private lateinit var modeLan: RadioButton
    private lateinit var ssid: EditText
    private lateinit var password: EditText
    private lateinit var iface: EditText
    private lateinit var help: TextView
    private lateinit var phoneButton: Button
    private lateinit var disconnect: Button
    private lateinit var video: SurfaceView
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
    private var message = "未连接。请先按说明连接网络并配对蓝牙。"
    private val panelColor = Color.rgb(23,44,50)
    private val inkColor = Color.rgb(242,244,239)
    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private fun text(value: String, size: Float = 18f) = TextView(this).apply { text=value; textSize=size; setTextColor(inkColor) }
    private fun button(value: String, action: () -> Unit) = Button(this).apply {
        text=value; isAllCaps=false; textSize=18f; minHeight=dp(56); setTextColor(inkColor); backgroundTintList=android.content.res.ColorStateList.valueOf(Color.rgb(36,61,67)); setOnClickListener { action() }
    }
    private fun field(label: String, hidden: Boolean = false) = EditText(this).apply {
        hint=label; textSize=20f; setTextColor(inkColor); setHintTextColor(Color.LTGRAY)
        minHeight=dp(56); isSingleLine=true; inputType=if(hidden) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD else InputType.TYPE_CLASS_TEXT
        importantForAutofill=View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        isSaveEnabled=false
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store=ReceiverStore(this)
        mode=runCatching { ConnectionMode.valueOf(store.prefs.getString("mode", "CAR_HOTSPOT")!!) }.getOrDefault(ConnectionMode.CAR_HOTSPOT)
        phone=store.prefs.getString("phone", null); phoneName=store.prefs.getString("phone_name", phoneName)!!
        buildUi()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
    private fun buildUi() {
        root=FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        video=SurfaceView(this)
        root.addView(video, FrameLayout.LayoutParams(-1,-1))
        video.holder.addCallback(object: SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) { currentSurface=holder.surface; sink?.setSurface(110,holder.surface) }
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) = Unit
            override fun surfaceDestroyed(holder: SurfaceHolder) {
                currentSurface=null
                // Upstream's bounded detach handshake must complete before Surface destruction returns.
                sink?.beginSurfaceDetach(holder.surface, false)?.await()
            }
        })
        video.setOnTouchListener { view,event ->
            if (connected) {
                val contacts=(0 until minOf(event.pointerCount,2)).map { i ->
                    val released=event.actionMasked==MotionEvent.ACTION_UP || event.actionMasked==MotionEvent.ACTION_CANCEL || (event.actionMasked==MotionEvent.ACTION_POINTER_UP && i==event.actionIndex)
                    AirPlayContact(event.getPointerId(i), (event.getX(i)/view.width).toDouble().coerceIn(0.0,1.0), (event.getY(i)/view.height).toDouble().coerceIn(0.0,1.0), !released)
                }
                controller?.sendTouch(contacts)
                if (event.actionMasked==MotionEvent.ACTION_UP) view.performClick()
            }
            true
        }
        setupPage=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL; setBackgroundColor(panelColor)
            setPadding(dp(20),dp(8),dp(20),dp(12))
        }
        root.addView(setupPage,FrameLayout.LayoutParams(-1,-1))
        val header=LinearLayout(this).apply { gravity=Gravity.CENTER_VERTICAL }
        header.addView(text("DiPlay Geely",26f).apply { setTypeface(null,Typeface.BOLD) },LinearLayout.LayoutParams(0,-2,1f))
        header.addView(button("蓝牙配对") { saveInputs(); startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) })
        header.addView(button("诊断") { showLogs() })
        setupPage.addView(header)
        body=LinearLayout(this)
        setupPage.addView(body,LinearLayout.LayoutParams(-1,0,1f))
        modes=RadioGroup(this)
        modeHotspot=RadioButton(this).apply { id=View.generateViewId();text="车机热点";textSize=20f;setTextColor(inkColor);minHeight=dp(64) }
        modeLan=RadioButton(this).apply { id=View.generateViewId();text="同一局域网";textSize=20f;setTextColor(inkColor);minHeight=dp(64) }
        modes.addView(modeHotspot);modes.addView(modeLan)
        body.addView(modes)
        configurationScroll=ScrollView(this).apply { isFillViewport=true }
        scrollingContent=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
        configurationScroll.addView(scrollingContent)
        form=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;setPadding(dp(12),dp(8),dp(12),dp(12)) }
        scrollingContent.addView(form);body.addView(configurationScroll)
        help=text("",16f).apply { setTextColor(Color.rgb(167,186,182));setPadding(0,0,0,dp(12)) };form.addView(help)
        ssid=field("热点或 Wi-Fi 名称");password=field("开放网络可留空",true);iface=field("网络接口，例如 wlan0")
        form.addView(text("网络名称",16f));form.addView(ssid,LinearLayout.LayoutParams(-1,-2))
        form.addView(text("网络密码",16f).apply { setPadding(0,dp(8),0,0) });form.addView(password,LinearLayout.LayoutParams(-1,-2))
        val options=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;visibility=View.GONE }
        form.addView(button("连接选项") { options.visibility=if(options.visibility==View.VISIBLE) View.GONE else View.VISIBLE })
        options.addView(text("热点网络接口",16f));options.addView(iface)
        phoneButton=button(phoneName) { choosePhone() };options.addView(phoneButton);form.addView(options)
        footer=LinearLayout(this).apply { gravity=Gravity.CENTER_VERTICAL;setPadding(0,dp(8),0,0) }
        status=text(message,16f).apply {
            maxLines=3;ellipsize=android.text.TextUtils.TruncateAt.END
            accessibilityLiveRegion=View.ACCESSIBILITY_LIVE_REGION_POLITE
            setOnClickListener { showLogs() }
        }
        footer.addView(status)
        primary=button("连接 CarPlay") { if(busy) endAttempt("已取消连接") else connect() }.apply {
            backgroundTintList=android.content.res.ColorStateList.valueOf(Color.rgb(201,222,176));setTextColor(panelColor)
        }
        footer.addView(primary);setupPage.addView(footer)
        // Measure the actual inset-adjusted window, including keyboard and split-screen changes.
        setupPage.addOnLayoutChangeListener { _,l,t,r,b,_,_,_,_ -> adaptLayout(r-l,b-t) }
        disconnect=button("断开 CarPlay") { endAttempt("已断开，可重新连接") }.apply { visibility=View.GONE;alpha=.9f }
        root.addView(disconnect,FrameLayout.LayoutParams(dp(164),dp(56),Gravity.TOP or Gravity.END))
        setContentView(root)
        modes.check(if(mode==ConnectionMode.CAR_HOTSPOT) modeHotspot.id else modeLan.id)
        modes.setOnCheckedChangeListener { _,id ->
            saveInputs(); mode=if(id==modeHotspot.id) ConnectionMode.CAR_HOTSPOT else ConnectionMode.SAME_LAN
            store.prefs.edit().putString("mode",mode.name).apply();loadInputs()
        }
        loadInputs();applyState()
    }
    private fun adaptLayout(width: Int, height: Int) {
        if(width<=0 || height<=0) return
        // Font scale affects the breakpoint: never squeeze large text into a fixed two-column layout.
        val effectiveWidth=width/resources.displayMetrics.density/resources.configuration.fontScale.coerceAtLeast(1f)
        val wide=effectiveWidth>=720f
        if(wideLayout==wide) return
        wideLayout=wide
        body.orientation=if(wide) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        modes.orientation=if(wide) RadioGroup.VERTICAL else RadioGroup.HORIZONTAL
        (modes.parent as android.view.ViewGroup).removeView(modes)
        if(wide) body.addView(modes,0) else scrollingContent.addView(modes,0)
        modes.layoutParams=LinearLayout.LayoutParams(if(wide) dp(208) else -1, -2)
        listOf(modeHotspot,modeLan).forEach {
            it.layoutParams=RadioGroup.LayoutParams(if(wide) -1 else 0,-2,if(wide) 0f else 1f)
        }
        configurationScroll.layoutParams=if(wide) LinearLayout.LayoutParams(0,-1,1f) else LinearLayout.LayoutParams(-1,0,1f)
        footer.orientation=if(wide) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        status.layoutParams=if(wide) LinearLayout.LayoutParams(0,-2,1f) else LinearLayout.LayoutParams(-1,-2)
        primary.layoutParams=LinearLayout.LayoutParams(if(wide) dp(224) else -1,-2)
    }
    private fun key(name: String) = "${mode.name}_$name"
    private fun loadInputs() {
        ssid.setText(store.prefs.getString(key("ssid"),""));password.setText(store.prefs.getString(key("password"),""));iface.setText(store.prefs.getString("interface","wlan0"))
        help.text=if(mode==ConnectionMode.CAR_HOTSPOT) "开启原车热点，让 iPhone 加入并完成蓝牙配对。" else "两台设备加入同一 Wi-Fi 并配对蓝牙；网络需允许设备互通和组播。"
        iface.isEnabled=mode==ConnectionMode.CAR_HOTSPOT
    }
    private fun saveInputs() { if(::ssid.isInitialized) store.prefs.edit().putString(key("ssid"),ssid.text.toString()).putString(key("password"),password.text.toString()).putString("interface",iface.text.toString().trim()).apply() }
    private fun missingPermissions(): Array<String> = (listOf(Manifest.permission.RECORD_AUDIO) + if(Build.VERSION.SDK_INT>=31) listOf(Manifest.permission.BLUETOOTH_CONNECT) else emptyList()).filter { checkSelfPermission(it)!=PackageManager.PERMISSION_GRANTED }.toTypedArray()
    private fun requestNecessaryPermissions(): Boolean {
        val missing=missingPermissions()
        if(missing.isEmpty()) return true
        update("CarPlay 音频协商需要麦克风权限；仅在 iPhone 请求麦克风通道时采集，不保存录音。")
        pendingPermissions=true;requestPermissions(missing,10);return false
    }
    override fun onRequestPermissionsResult(code:Int, permissions:Array<out String>, results:IntArray) {
        super.onRequestPermissionsResult(code,permissions,results)
        if(code==10 && pendingPermissions) {
            pendingPermissions=false
            if(missingPermissions().isEmpty()) connect() else update(if(Manifest.permission.RECORD_AUDIO in missingPermissions()) "麦克风权限未允许，无法提供完整 CarPlay 音频能力。请允许麦克风权限后重试。" else "蓝牙权限被拒绝。请在系统应用权限中允许附近设备，然后重试。")
        }
    }
    private fun choosePhone() {
        try {
        if(missingPermissions().isNotEmpty()) { update("请先点击连接并允许所需权限，再选择手机。");return }
        val devices=runCatching { getSystemService(BluetoothManager::class.java)?.adapter?.bondedDevices?.toList().orEmpty() }.getOrDefault(emptyList())
        if(devices.isEmpty()) { update("没有已配对手机，请先点击蓝牙配对。");return }
        val labels=arrayOf("自动选择")+devices.map { it.name ?: "已配对设备" }.toTypedArray()
        AlertDialog.Builder(this).setTitle("选择已配对 iPhone").setItems(labels) { _,n ->
            phone=if(n==0) null else devices[n-1].address;phoneName=if(n==0) "自动选择已配对的 iPhone" else labels[n]
            store.prefs.edit().putString("phone",phone).putString("phone_name",phoneName).apply();phoneButton.text=phoneName
        }.show()
        } catch (_: SecurityException) { update("蓝牙权限已变化，请允许权限后重试。") }
    }
    private fun connect() {
        if(busy || stopping || controller!=null || destroyed || !foreground) return
        if(!requestNecessaryPermissions()) return
        val lease=Any()
        if (!ReceiverSessionGate.acquire(lease)) { update("上一连接仍在释放，请稍后重试。");return }
        leaseToken=lease
        saveInputs()
        val config=ReceiverConfig(mode,ssid.text.toString(),password.text.toString(),iface.text.toString().trim(),phone)
        secret=config.password
        val token=++generation
        busy=true;update("正在检查配置和认证资产…");applyState()
        timeout=Runnable { if(token==generation && busy && !connected) endAttempt("连接超时。请确认 iPhone 已加入网络、蓝牙已配对，并在手机上允许 CarPlay；局域网需关闭客户端隔离并允许组播，然后重试。") }.also { main.postDelayed(it,90_000) }
        val width=(video.width/2)*2;val height=(video.height/2)*2
        if(width<2 || height<2) {
            busy=false;ReceiverSessionGate.release(leaseToken);leaseToken=null
            timeout?.let(main::removeCallbacks);timeout=null
            update("显示区域尚未就绪，请稍后重试。");applyState();return
        }
        configuredWidth=width;configuredHeight=height
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
                val air=receiverAirPlayConfig(identity,width,height)
                val pairs=store.pairings()
                main.post {
                    if(destroyed || generation!=token) return@post
                    try {
                        val renderer=AndroidMediaSink(videoWidth=width,videoHeight=height,context=this,audioFocusEnabled=true,wirelessAudio=true,mediaBufferMillis=200,videoFps=30,onAudioDiagnostic={ log(it);android.util.Log.i("DiPlay-Audio",it) })
                        sink=renderer;currentSurface?.let { renderer.setSurface(110,it) }
                        val next=CarPlayController(this,runtime,air,identity,pairs,listener(token),CarPlayMediaEngine(renderer,microphoneEnabled=air.microphone),{ onStatus(token,it) })
                        log("Audio: capability output=${!air.disableAudioOutput} microphone=${air.microphone} mainBuffered=${air.mainBufferedAudio}")
                        controller=next;next.start()
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
        override fun onSessionActive(session:AirPlaySession) { main.post { if(token==generation) { currentAirPlaySession=session; update("CarPlay 会话已建立，正在等待画面…") } } }
        override fun onVideoFrameRendered(session:AirPlaySession) { main.post { if(token==generation && !destroyed && currentAirPlaySession===session) {
            connected=true;timeout?.let(main::removeCallbacks);update("CarPlay 已连接，画面已解码显示");applyState()
        } } }
        override fun onSessionEnded(session:AirPlaySession) { main.post { if(token==generation && currentAirPlaySession===session) endAttempt("iPhone 已断开，可重新连接。") } }
        override fun onTransportError(message:String) { log("传输失败: $message");main.post { if(token==generation) endAttempt("CarPlay 传输中断。请检查网络和手机上的 CarPlay 授权后重试。") } }
        override fun onHostUiRequested(session:AirPlaySession) { main.post { if(token==generation) endAttempt("已返回连接页，可重新连接。") } }
        override fun onCommand(session:AirPlaySession,type:String,params:Map<String,Any?>) {
            if(token!=generation || type!="modesChanged") return
            val resources=(params["resources"] as? List<*>).orEmpty().filterIsInstance<Map<*,*>>().map { row ->
                row.filter { (key,value) -> key in setOf("resourceID","entityID","transferType","transferPriority") && (value is Number || value is Boolean) }
            }
            val detail="Audio: modes resources=$resources"
            log(detail);android.util.Log.i("DiPlay-Audio",detail)
        }
        override fun onDebugLog(message:String) { log(message) }
    }
    private fun onStatus(token:Int,state:CarPlayStatus) {
        if(token!=generation || destroyed || (connected && state !is CarPlayStatus.Failed)) return
        when(state) {
            is CarPlayStatus.Failed -> { log("连接失败: "+state.message);endAttempt("连接失败："+friendlyFailure(state.message)) }
            CarPlayStatus.WaitingForPairedIphone -> update("正在选择已配对 iPhone；请保持手机蓝牙开启。")
            CarPlayStatus.ConnectingBluetooth -> update("正在通过蓝牙完成首次认证与网络交接…")
            is CarPlayStatus.HotspotReady -> update("网络接口就绪，正在建立 CarPlay（尚未连接）。")
            CarPlayStatus.RunningWireless -> update("正在等待 iPhone 建立 CarPlay；请在手机上允许连接。")
            CarPlayStatus.MfiReady -> update("本地认证已就绪，正在检查网络…")
            else -> Unit
        }
    }
    private fun friendlyFailure(message:String):String = when {
        message.contains("paired",true) || message.contains("bonded",true) -> "未找到明确的已配对 iPhone，请先完成蓝牙配对；多部手机时展开配置选择一部。"
        message.contains("bluetoothMac",true) -> "本机蓝牙标识无效，无法启动 CarPlay 握手。请查看本地诊断。"
        message.contains("Bluetooth",true) || message.contains("RFCOMM",true) -> "蓝牙握手失败；普通配对并不代表 CarPlay 握手成功。请查看本地诊断中的具体原因。"
        message.contains("SSID",true) || message.contains("configuration",true) -> "网络配置不匹配，请核对名称、密码和接口。"
        message.contains("network changed",true) -> "Wi-Fi 已变化或断开，请恢复网络后重试。"
        else -> "未能完成 CarPlay 握手。请核对网络、蓝牙配对及手机授权；同一局域网还需允许设备互通和组播。详细阶段见本地诊断。"
    }
    private fun endAttempt(reason:String) {
        if(stopping) return
        ++generation;timeout?.let(main::removeCallbacks);timeout=null
        currentAirPlaySession=null;connected=false;busy=false;stopping=true;update(reason+"\n正在释放连接…");applyState()
        val old=controller;val renderer=sink;val lease=leaseToken;leaseToken=null;controller=null;sink=null
        // close() cancels the blocking Bluetooth/network operations before queued preparation finishes.
        old?.close()
        worker.execute {
            val transportReleased=old?.awaitClosed(8_000) ?: true
            val mediaReleased=runCatching { renderer?.close() }.isSuccess
            val released=transportReleased && mediaReleased
            if (released) ReceiverSessionGate.release(lease)
            main.post { if(!destroyed) {
                stopping=!released
                update(if(released) reason else "连接资源尚未释放，已阻止新任务。请在系统应用信息中强行停止本应用后重新打开。")
                applyState()
            } }
        }
    }
    private fun applyState() {
        setupPage.visibility=if(connected) View.GONE else View.VISIBLE
        disconnect.visibility=if(connected) View.VISIBLE else View.GONE
        primary.text=if(stopping) "正在结束连接…" else if(busy) "取消连接" else "连接 CarPlay"
        primary.isEnabled=!stopping
        listOf(modeHotspot,modeLan,ssid,password,iface,phoneButton).forEach { it.isEnabled=!busy && !stopping }
        if(mode==ConnectionMode.SAME_LAN) iface.isEnabled=false
    }
    private fun update(value:String) { message=value;status.text=value;log(value) }
    @Synchronized private fun log(value:String) {
        if(destroyed) return
        val safe=if(secret.isNotEmpty()) value.replace(secret,"[redacted]") else value
        runCatching {
            val file=File(filesDir,"connection.log")
            if(file.length()>256*1024) file.writeText(file.readText().takeLast(128*1024))
            file.appendText("${System.currentTimeMillis()} "+safe.take(2000)+"\n")
        }
    }
    private fun showLogs() {
        val content=runCatching { File(filesDir,"connection.log").readText().takeLast(20_000) }.getOrDefault("尚无连接记录")
        val view=text(content,14f).apply { setPadding(dp(16),dp(12),dp(16),dp(12));setTextIsSelectable(true) }
        val scroll=ScrollView(this);scroll.addView(view)
        AlertDialog.Builder(this).setTitle("本地连接诊断").setView(scroll).setPositiveButton("关闭",null).show()
    }
    @Deprecated("Uses Activity back for Android 11 target")
    override fun onBackPressed() { if(busy || connected) endAttempt("已返回连接页，可重新连接。") else super.onBackPressed() }
    override fun onConfigurationChanged(config:Configuration) {
        super.onConfigurationChanged(config)
        video.post {
            if ((busy || connected) && ((video.width/2)*2 != configuredWidth || (video.height/2)*2 != configuredHeight)) {
                endAttempt("显示尺寸已变化，请重新连接。")
            }
        }
    }
    override fun onStart() { super.onStart();foreground=true }
    override fun onStop() { foreground=false;if(busy || connected) endAttempt("应用已离开前台，连接已结束。重新打开后可连接。");super.onStop() }
    override fun onDestroy() {
        saveInputs();if(!stopping && (controller!=null || busy)) endAttempt("应用已关闭")
        destroyed=true;main.removeCallbacksAndMessages(null);worker.shutdown();super.onDestroy()
    }
}
