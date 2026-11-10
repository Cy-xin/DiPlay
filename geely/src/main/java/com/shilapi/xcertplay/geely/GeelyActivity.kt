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
    private var busy = false
    private var currentAirPlaySession: AirPlaySession? = null
    private var connected = false
    private var stopping = false
    private var generation = 0
    private var destroyed = false
    private var pendingPermissions = false
    private var timeout: Runnable? = null
    private var secret = ""
    private var message = "未连接。请先按说明连接网络并配对蓝牙。"
    private val panelColor = Color.rgb(24,33,43)
    private val inkColor = Color.rgb(241,246,250)
    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private fun text(value: String, size: Float = 18f) = TextView(this).apply { text=value; textSize=size; setTextColor(inkColor) }
    private fun button(value: String, action: () -> Unit) = Button(this).apply {
        text=value; isAllCaps=false; textSize=18f; minHeight=dp(56); setOnClickListener { action() }
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
        val scroll=ScrollView(this).apply { isFillViewport=true; setBackgroundColor(panelColor) }
        form=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(28),dp(16),dp(28),dp(20)) }
        scroll.addView(form);root.addView(scroll,FrameLayout.LayoutParams(-1,-1))
        form.addView(text("DiPlay Geely",24f).apply { setTypeface(null,Typeface.BOLD) })
        val modes=RadioGroup(this).apply { orientation=RadioGroup.HORIZONTAL }
        modeHotspot=RadioButton(this).apply { id=View.generateViewId();text="车机热点";textSize=20f;setTextColor(inkColor);minHeight=dp(56) }
        modeLan=RadioButton(this).apply { id=View.generateViewId();text="同一局域网";textSize=20f;setTextColor(inkColor);minHeight=dp(56) }
        modes.addView(modeHotspot,LinearLayout.LayoutParams(0,dp(56),1f));modes.addView(modeLan,LinearLayout.LayoutParams(0,dp(56),1f))
        form.addView(modes);modes.check(if(mode==ConnectionMode.CAR_HOTSPOT) modeHotspot.id else modeLan.id)
        help=text("",16f);form.addView(help)
        ssid=field("热点或 Wi-Fi 名称");password=field("密码（开放网络留空）",true);iface=field("网络接口，例如 wlan0")
        val fields=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL }
        val left=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
        val right=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;setPadding(dp(24),0,0,0) }
        left.addView(text("网络名称"));left.addView(ssid);left.addView(text("网络密码"));left.addView(password)
        right.addView(text("低频配置"));right.addView(button("展开 / 收起") { iface.visibility=if(iface.visibility==View.VISIBLE) View.GONE else View.VISIBLE;phoneButton.visibility=iface.visibility })
        iface.visibility=View.GONE;right.addView(iface)
        phoneButton=button(phoneName) { choosePhone() }.apply { visibility=View.GONE };right.addView(phoneButton)
        fields.addView(left,LinearLayout.LayoutParams(0,-2,1f));fields.addView(right,LinearLayout.LayoutParams(0,-2,1f));form.addView(fields)
        val actions=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL }
        primary=button("连接 CarPlay") { if(busy) endAttempt("已取消连接") else connect() }.apply { backgroundTintList=android.content.res.ColorStateList.valueOf(Color.rgb(95,168,255));setTextColor(Color.BLACK) }
        actions.addView(primary,LinearLayout.LayoutParams(0,dp(60),1f))
        actions.addView(button("蓝牙配对") { saveInputs(); startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) },LinearLayout.LayoutParams(0,dp(60),1f))
        form.addView(actions)
        status=text(message);status.setPadding(0,dp(12),0,0);status.accessibilityLiveRegion=View.ACCESSIBILITY_LIVE_REGION_POLITE;form.addView(status)
        right.addView(button("查看本地诊断") { showLogs() })
        disconnect=button("断开 CarPlay") { endAttempt("已断开，可重新连接") }.apply { visibility=View.GONE;alpha=.9f }
        root.addView(disconnect,FrameLayout.LayoutParams(dp(164),dp(56),Gravity.TOP or Gravity.END))
        setContentView(root)
        modes.setOnCheckedChangeListener { _,id ->
            saveInputs(); mode=if(id==modeHotspot.id) ConnectionMode.CAR_HOTSPOT else ConnectionMode.SAME_LAN
            store.prefs.edit().putString("mode",mode.name).apply();loadInputs()
        }
        loadInputs();applyState()
    }
    private fun key(name: String) = "${mode.name}_$name"
    private fun loadInputs() {
        ssid.setText(store.prefs.getString(key("ssid"),""));password.setText(store.prefs.getString(key("password"),""));iface.setText(store.prefs.getString("interface","wlan0"))
        help.text=if(mode==ConnectionMode.CAR_HOTSPOT) "在原车设置开启热点，让 iPhone 加入该热点。保持蓝牙开启并完成配对，再点击连接。" else "车机与 iPhone 加入同一 Wi-Fi，保持蓝牙配对。网络需允许设备互通和组播；暂不支持企业认证或网页登录。"
        iface.isEnabled=mode==ConnectionMode.CAR_HOTSPOT
    }
    private fun saveInputs() { if(::ssid.isInitialized) store.prefs.edit().putString(key("ssid"),ssid.text.toString()).putString(key("password"),password.text.toString()).putString("interface",iface.text.toString().trim()).apply() }
    private fun missingPermissions(): Array<String> = (if(Build.VERSION.SDK_INT>=31) listOf(Manifest.permission.BLUETOOTH_CONNECT) else emptyList()).filter { checkSelfPermission(it)!=PackageManager.PERMISSION_GRANTED }.toTypedArray()
    private fun requestNecessaryPermissions(): Boolean {
        val missing=missingPermissions()
        if(missing.isEmpty()) return true
        pendingPermissions=true;requestPermissions(missing,10);return false
    }
    override fun onRequestPermissionsResult(code:Int, permissions:Array<out String>, results:IntArray) {
        super.onRequestPermissionsResult(code,permissions,results)
        if(code==10 && pendingPermissions) {
            pendingPermissions=false
            if(missingPermissions().isEmpty()) connect() else update("蓝牙权限被拒绝。请在系统应用权限中允许附近设备，然后重试。")
        }
    }
    private fun choosePhone() {
        try {
        if(missingPermissions().isNotEmpty()) { update("请先点击连接并允许蓝牙权限，再选择手机。");return }
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
        if(busy || stopping || controller!=null || destroyed) return
        if(!requestNecessaryPermissions()) return
        saveInputs()
        val config=ReceiverConfig(mode,ssid.text.toString(),password.text.toString(),iface.text.toString().trim(),phone)
        secret=config.password
        val token=++generation
        busy=true;update("正在检查配置和认证资产…");applyState()
        timeout=Runnable { if(token==generation && busy && !connected) endAttempt("连接超时。请确认 iPhone 已加入网络、蓝牙已配对，并在手机上允许 CarPlay；局域网需关闭客户端隔离并允许组播，然后重试。") }.also { main.postDelayed(it,90_000) }
        val width=(video.width.coerceAtLeast(640)/2)*2;val height=(video.height.coerceAtLeast(360)/2)*2
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
                val air=AirPlayConfig("DiPlay Geely",id,"", "950.7.1",AirPlayDisplayConfig(width,height,fps=30),manufacturer="DiPlay",model="KX11_A2",oemLabel="DiPlay Geely",microphone=false,mainBufferedAudio=true)
                val pairs=store.pairings()
                main.post {
                    if(destroyed || generation!=token) return@post
                    try {
                        val renderer=AndroidMediaSink(videoWidth=width,videoHeight=height,context=this,audioFocusEnabled=true,wirelessAudio=true,videoFps=30,onAudioDiagnostic={ log(it) })
                        sink=renderer;currentSurface?.let { renderer.setSurface(110,it) }
                        val next=CarPlayController(this,runtime,air,identity,pairs,listener(token),CarPlayMediaEngine(renderer),{ onStatus(token,it) })
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
        message.contains("Bluetooth",true) -> "蓝牙通信不可用，请确认车机与 iPhone 蓝牙开启并已配对。若原车蓝牙不向 Android 提供 iAP2，需实车诊断。"
        message.contains("SSID",true) || message.contains("configuration",true) -> "网络配置不匹配，请核对名称、密码和接口。"
        message.contains("network changed",true) -> "Wi-Fi 已变化或断开，请恢复网络后重试。"
        else -> "未能完成 CarPlay 握手。请核对网络、蓝牙配对及手机授权；同一局域网还需允许设备互通和组播。详细阶段见本地诊断。"
    }
    private fun endAttempt(reason:String) {
        if(stopping) return
        ++generation;timeout?.let(main::removeCallbacks);timeout=null
        currentAirPlaySession=null;connected=false;busy=false;stopping=true;update(reason+"\n正在释放连接…");applyState()
        val old=controller;val renderer=sink;controller=null;sink=null
        // close() cancels the blocking Bluetooth/network operations before queued preparation finishes.
        old?.close()
        worker.execute {
            val released=old?.awaitClosed(8_000) ?: true
            renderer?.close()
            main.post { if(!destroyed) {
                stopping=!released
                update(if(released) reason else "连接资源尚未释放。请退出并重新打开应用，暂不创建新的连接任务。")
                applyState()
            } }
        }
    }
    private fun applyState() {
        (form.parent as View).visibility=if(connected) View.GONE else View.VISIBLE
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
    override fun onConfigurationChanged(config:Configuration) { super.onConfigurationChanged(config);if(busy || connected) endAttempt("显示尺寸已变化，请重新连接。") }
    override fun onStop() { if(busy || connected) endAttempt("应用已离开前台，连接已结束。重新打开后可连接。");super.onStop() }
    override fun onDestroy() {
        saveInputs();if(!stopping && (controller!=null || busy)) endAttempt("应用已关闭")
        destroyed=true;main.removeCallbacksAndMessages(null);worker.shutdown();super.onDestroy()
    }
}
