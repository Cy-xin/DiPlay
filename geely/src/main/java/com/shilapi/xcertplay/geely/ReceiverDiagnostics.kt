package com.shilapi.xcertplay.geely

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.SystemClock
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.GZIPOutputStream

/** App-owned event journal only: no global logcat, screen capture, PCM, keys or input contents. */
internal class ReceiverDiagnostics private constructor(private val context:Context) {
    val session=UUID.randomUUID().toString()
    private val prefs=context.getSharedPreferences("diagnostics",Context.MODE_PRIVATE)
    val installation=prefs.getString("installation",null) ?: UUID.randomUUID().toString().also {prefs.edit().putString("installation",it).commit()}
    private val directory=File(context.noBackupFilesDir,"diagnostics").apply {mkdirs()}
    private val lock=Any()
    private val seq=AtomicLong()
    private val dropped=AtomicInteger()
    private val executor=ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,ArrayBlockingQueue(512),
        {r -> Thread(r,"DiPlay-Diagnostics").apply {isDaemon=true}},ThreadPoolExecutor.AbortPolicy())
    @Volatile var attempt=""
    @Volatile private var secrets=emptySet<String>()
    private var active=File(directory,"events-${System.currentTimeMillis()}-${session.take(8)}.jsonl")
    init {
        event("app","process_start","version=${BuildConfig.VERSION_NAME} sdk=${Build.VERSION.SDK_INT} model=${Build.MODEL}")
        if(prefs.getBoolean("foreground_unfinished",false)) event("app","previous_foreground_unfinished","Previous process did not mark foreground exit; cause unknown", "WARN")
        runCatching {context.getSystemService(ActivityManager::class.java).getHistoricalProcessExitReasons(context.packageName,0,3).forEach {
            event("app","historical_exit","time=${it.timestamp} reason=${it.reason} status=${it.status} pssKb=${it.pss}")
        }}
    }
    @Synchronized fun protect(vararg values:String) { secrets=(secrets+values.filter {it.isNotBlank()}).toSet() }
    fun redact(value:String):String=redactValue(value,secrets)
    fun event(category:String,event:String,detail:String="",level:String="INFO") {
        val record=JSONObject().put("schema",1).put("utc",System.currentTimeMillis()).put("elapsedMs",SystemClock.elapsedRealtime())
            .put("seq",seq.incrementAndGet()).put("session",session).put("attempt",attempt).put("level",level)
            .put("category",category.take(100)).put("event",event.take(100)).put("detail",redact(detail).take(4000)).toString()
        try {executor.execute {runCatching { synchronized(lock) {
            val lost=dropped.getAndSet(0)
            if(lost>0) write(JSONObject(record).put("event","queue_gap").put("detail","dropped=$lost").put("level","WARN").toString())
            write(record)
        }}}} catch(_:java.util.concurrent.RejectedExecutionException) {dropped.incrementAndGet()}
    }
    private fun files()=directory.listFiles {f->f.name.startsWith("events-") && f.extension=="jsonl"}.orEmpty().sortedBy {it.lastModified()}
    private fun write(record:String) {
        if(active.length()>1024*1024) active=File(directory,"events-${System.currentTimeMillis()}-${UUID.randomUUID().toString().take(8)}.jsonl")
        active.appendText(record+"\n")
        val files=files()
        files.filterIndexed {i,f -> i<files.size-8 || (f!=active && f.lastModified()<System.currentTimeMillis()-7*86400000L)}.forEach {it.delete()}
    }
    fun foreground(active:Boolean) {
        prefs.edit().putBoolean("foreground_unfinished",active).apply()
        event("lifecycle",if(active) "foreground" else "background")
    }
    fun crash(error:Throwable) {runCatching { synchronized(lock) {
        val stack=error.stackTraceToString().take(12000)
        val r=JSONObject().put("schema",1).put("utc",System.currentTimeMillis()).put("elapsedMs",SystemClock.elapsedRealtime())
            .put("seq",seq.incrementAndGet()).put("session",session).put("attempt",attempt).put("level","ERROR")
            .put("category","app").put("event","uncaught_exception").put("detail",redact(stack).take(4000))
        write(r.toString())
    }}}
    fun flush() {executor.submit {}.get(5,TimeUnit.SECONDS)}
    fun recent():String {flush();return synchronized(lock) {files().takeLast(2).joinToString("\n") {it.readText().takeLast(32000)}.takeLast(48000)}}
    /** Frozen bounded outbox survives failed uploads; retries send identical bytes and report UUID. */
    fun snapshot(issue:String):File {
        flush()
        val id=UUID.randomUUID().toString();val file=File(directory,"outbox-$id.json.gz");val temporary=File(directory,".building-$id")
        synchronized(lock) {
            directory.listFiles {it.name.startsWith(".building-")}.orEmpty().forEach {it.delete()}
            val selected=files();var count=0
            GZIPOutputStream(FileOutputStream(temporary)).bufferedWriter().use {out ->
                val header=JSONObject().put("schemaVersion",1).put("reportId",id).put("installationId",installation)
                    .put("createdAt",System.currentTimeMillis()).put("appVersion",BuildConfig.VERSION_NAME).put("issue",redact(issue).take(1000))
                    .put("metadata",JSONObject().put("model",Build.MODEL).put("manufacturer",Build.MANUFACTURER).put("androidSdk",Build.VERSION.SDK_INT)
                        .put("appVersion",BuildConfig.VERSION_NAME).put("fileCount",selected.size).put("dropped",dropped.get())
                        .put("logPolicy","app-only; 7 days or 8MiB; no credentials, text, coordinates, recordings or frames"))
                out.write(header.toString().dropLast(1));out.write(",\"events\":[")
                for(f in selected) f.forEachLine {line ->
                    if(count<58000 && line.isNotBlank()) runCatching {
                        val safe=JSONObject(line);safe.put("detail",redact(safe.optString("detail")))
                        if(count++>0) out.write(",");out.write(safe.toString())
                    }
                }
                out.write("]}")
            }
            check(temporary.renameTo(file))
            directory.listFiles {f->f.name.startsWith("outbox-") && f!=file}.orEmpty().sortedByDescending {it.lastModified()}.drop(1).forEach {it.delete()}
        }
        return file
    }
    fun pending():File?=directory.listFiles {f->f.name.startsWith("outbox-") && f.name.endsWith(".json.gz")}.orEmpty().maxByOrNull {it.lastModified()}
    companion object {
        @Volatile private var current:ReceiverDiagnostics?=null
        private var hooked=false
        @Synchronized fun get(context:Context):ReceiverDiagnostics {
            val app=context.applicationContext
            if(current?.context!==app) current=ReceiverDiagnostics(app)
            if(!hooked) {
                val previous=Thread.getDefaultUncaughtExceptionHandler()
                Thread.setDefaultUncaughtExceptionHandler {thread,error ->
                    current?.crash(error)
                    if(previous!=null) previous.uncaughtException(thread,error)
                    else {android.os.Process.killProcess(android.os.Process.myPid());kotlin.system.exitProcess(10)}
                };hooked=true
            }
            return current!!
        }
        internal fun redactValue(value:String,secrets:Set<String>):String {
            var s=value.replace(Regex("(?i)Bearer\\s+[^\\s,;]+"),"Bearer [redacted]")
            secrets.sortedByDescending {it.length}.forEach {s=s.replace(it,"[redacted]")}
            s=s.replace(Regex("(?i)[\"']?(password|passphrase|authorization|privateKey|pairingKey|token|secret|certificate)[\"']?\\s*[:=]\\s*\"[^\"]*\""),"$1=[redacted]")
            s=s.replace(Regex("-----BEGIN [\\s\\S]*?-----END [^-]+-----"),"[redacted credential]")
            s=s.replace(Regex("(?i)(password|passphrase|authorization|privateKey|publicKey|pairingKey|sessionKey|sharedSecret|signature|proof|deviceID|pairingID|identifier|ssid|hostName|access_token|token|certificate)\\s*[:=]\\s*[^\\s,;}]+"),"$1=[redacted]")
            s=s.replace(Regex("(?i)\\b[0-9a-f]{2}(:[0-9a-f]{2}){5}\\b"),"[device-address]")
                .replace(Regex("\\b(?:\\d{1,3}\\.){3}\\d{1,3}\\b"),"[ip]")
                .replace(Regex("[A-Za-z0-9+/]{100,}={0,2}"),"[redacted blob]")
            return s
        }
    }
}
