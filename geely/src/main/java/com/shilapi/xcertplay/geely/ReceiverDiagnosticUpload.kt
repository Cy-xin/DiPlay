package com.shilapi.xcertplay.geely

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal class ReceiverDiagnosticUpload(private val context:Context,private val journal:ReceiverDiagnostics) {
    private val prefs=context.getSharedPreferences("diagnostics",Context.MODE_PRIVATE)
    private val endpoint="https://140.245.48.181:8443/diplay-diagnostics/v1"
    val enrolled get()=prefs.contains("encrypted_upload_token")
    val lastReport get()=prefs.getString("last_report","").orEmpty()
    private fun key():SecretKey {
        val store=KeyStore.getInstance("AndroidKeyStore").apply {load(null)}
        (store.getKey("diplay-diagnostics-upload",null) as? SecretKey)?.let {return it}
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("diplay-diagnostics-upload",KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    private fun token():String {
        val blob=JSONObject(prefs.getString("encrypted_upload_token",null) ?: error("NOT_PAIRED"))
        val cipher=Cipher.getInstance("AES/GCM/NoPadding").apply {init(Cipher.DECRYPT_MODE,key(),GCMParameterSpec(128,Base64.decode(blob.getString("iv"),Base64.NO_WRAP)))}
        return String(cipher.doFinal(Base64.decode(blob.getString("data"),Base64.NO_WRAP)),Charsets.UTF_8).also {journal.protect(it)}
    }
    private fun storeToken(token:String) {
        journal.protect(token)
        val cipher=Cipher.getInstance("AES/GCM/NoPadding").apply {init(Cipher.ENCRYPT_MODE,key())}
        val encrypted=JSONObject().put("iv",Base64.encodeToString(cipher.iv,Base64.NO_WRAP))
            .put("data",Base64.encodeToString(cipher.doFinal(token.toByteArray()),Base64.NO_WRAP))
        check(prefs.edit().putString("encrypted_upload_token",encrypted.toString()).commit())
    }
    private fun connection(path:String):HttpURLConnection {
        val url=URL(endpoint+path)
        require(url.protocol=="https")
        val manager=context.getSystemService(ConnectivityManager::class.java)
        fun internet(n:android.net.Network)=manager.getNetworkCapabilities(n)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)==true
        val network=manager.activeNetwork?.takeIf {internet(it)} ?: manager.allNetworks.firstOrNull {internet(it)}
        val conn=(network?.openConnection(url) ?: url.openConnection()) as HttpURLConnection
        conn.setRequestProperty("User-Agent","DiPlay-Geely/0.3 Android")
        conn.connectTimeout=12000;conn.readTimeout=20000;conn.instanceFollowRedirects=false;conn.requestMethod="POST";conn.doOutput=true
        return conn
    }
    private fun response(conn:HttpURLConnection):JSONObject {
        val code=conn.responseCode
        if(code !in listOf(200,201)) {
            journal.event("upload","http_failure","status=$code","WARN")
            if(code==401) prefs.edit().remove("encrypted_upload_token").apply()
            throw java.io.IOException("HTTP_$code")
        }
        val text=conn.inputStream.bufferedReader().use {it.readText().take(8192)}
        return JSONObject(text)
    }
    fun enroll(code:String) {
        val clean=code.trim().uppercase();journal.protect(clean)
        require(clean.matches(Regex("[A-F0-9]{12}"))) {"PAIR_CODE_FORMAT"}
        journal.event("upload","pair_start")
        val conn=connection("/enroll")
        try {
            conn.setRequestProperty("Content-Type","application/json")
            val bytes=JSONObject().put("code",clean).put("installationId",journal.installation).toString().toByteArray()
            conn.setFixedLengthStreamingMode(bytes.size);conn.outputStream.use {it.write(bytes)}
            val result=response(conn);require(result.getString("installationId")==journal.installation)
            storeToken(result.getString("token"));journal.event("upload","pair_success")
        } finally {conn.disconnect()}
    }
    fun upload(bundle:File,onProgress:(Long,Long)->Unit):String {
        require(bundle.name.matches(Regex("outbox-[0-9a-f-]{36}\\.json\\.gz")))
        require(bundle.length() in 1..8*1024*1024)
        val id=bundle.name.removePrefix("outbox-").removeSuffix(".json.gz")
        val bearer=token();val conn=connection("/reports")
        journal.event("upload","send_start","report=$id bytes=${bundle.length()}")
        try {
            conn.setRequestProperty("Authorization","Bearer $bearer");conn.setRequestProperty("Content-Type","application/gzip")
            conn.setRequestProperty("Idempotency-Key",id);conn.setFixedLengthStreamingMode(bundle.length())
            conn.outputStream.use {out ->bundle.inputStream().use {input ->
                val buffer=ByteArray(16384);var done=0L;var notified=0L
                while(true) {
                    if(Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException()
                    val n=input.read(buffer);if(n<0) break;out.write(buffer,0,n);done+=n
                    if(done-notified>=65536 || done==bundle.length()) {onProgress(done,bundle.length());notified=done}
                }
            }}
            val result=response(conn);check(result.getString("reportId")==id)
            check(prefs.edit().putString("last_report",id).putLong("last_uploaded_at",System.currentTimeMillis()).commit())
            bundle.delete();journal.event("upload","send_success","report=$id events=${result.optInt("eventCount")}")
            return id
        } catch(e:Exception) {
            journal.event("upload","send_failed","report=$id type=${e.javaClass.simpleName} message=${e.message.orEmpty()}","ERROR");throw e
        } finally {conn.disconnect()}
    }
}
