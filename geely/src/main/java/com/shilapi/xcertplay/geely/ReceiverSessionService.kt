package com.shilapi.xcertplay.geely

import android.Manifest
import android.app.*
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.IBinder
import java.lang.ref.WeakReference

/** Upstream DiPlaySessionService pattern, without vehicle, HUD, location or restart machinery. */
class ReceiverSessionService:Service() {
    override fun onBind(intent:Intent?):IBinder?=null
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
        if(intent?.action==ACTION_STOP) {owner?.get()?.disconnectFromNotification();stopSelf();return START_NOT_STICKY}
        // Never resurrect a connection when Android redelivers a stale service intent.
        if(owner?.get()==null) {stopSelf();return START_NOT_STICKY}
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL,"CarPlay 连接",NotificationManager.IMPORTANCE_LOW))
        val open=PendingIntent.getActivity(this,0,Intent(this,GeelyActivity::class.java).setAction(ACTION_OPEN)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop=PendingIntent.getService(this,1,Intent(this,ReceiverSessionService::class.java).setAction(ACTION_STOP),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification=Notification.Builder(this,CHANNEL).setSmallIcon(R.drawable.ic_connection_notification)
            .setContentTitle("DiPlay Geely").setContentText("CarPlay 连接保持中，点击返回画面")
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(null,"断开连接",stop).build()).build()
        var types=ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK or ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED) types=types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        startForeground(112,notification,types)
        ReceiverDiagnostics.get(this).event("lifecycle","connection_service_started","types=$types")
        return START_NOT_STICKY
    }
    override fun onDestroy() {ReceiverDiagnostics.get(this).event("lifecycle","connection_service_stopped");super.onDestroy()}
    override fun onTaskRemoved(rootIntent:Intent?) {owner?.get()?.disconnectFromNotification();stopSelf()}
    companion object {
        const val ACTION_OPEN="com.shihab.diplay.OPEN_CARPLAY"
        private const val ACTION_STOP="com.shihab.diplay.DISCONNECT"
        private const val CHANNEL="geely_carplay_connection"
        private var owner:WeakReference<GeelyActivity>?=null
        internal fun attach(activity:GeelyActivity) {owner=WeakReference(activity)}
        internal fun detach(activity:GeelyActivity) {if(owner?.get()===activity) owner=null}
    }
}
