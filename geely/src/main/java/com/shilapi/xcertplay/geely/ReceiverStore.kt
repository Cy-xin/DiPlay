package com.shilapi.xcertplay.geely

import android.content.Context
import android.util.Base64
import com.shilapi.xcertplay.airplay.AirPlayIdentity
import com.shilapi.xcertplay.airplay.PairingStore
import com.shilapi.xcertplay.mfi.LocalMfiAuthenticationClient
import java.io.File
import java.security.MessageDigest

internal class ReceiverStore(private val context: Context) {
    val prefs = context.getSharedPreferences("geely_minimal", Context.MODE_PRIVATE)
    private fun encode(b: ByteArray) = Base64.encodeToString(b, Base64.NO_WRAP)
    private fun decode(s: String) = Base64.decode(s, Base64.NO_WRAP)
    @Synchronized fun identity(): AirPlayIdentity {
        val saved = prefs.getString("identity_private", null)
        if (saved != null) return AirPlayIdentity(decode(saved), decode(requireNotNull(prefs.getString("identity_public", null))), requireNotNull(prefs.getString("pairing_id", null)))
        return AirPlayIdentity.generate().also {
            check(prefs.edit().putString("identity_private", encode(it.privateKey)).putString("identity_public", encode(it.publicKey)).putString("pairing_id", it.pairingId).commit())
        }
    }
    fun pairings(): PairingStore {
        val store = PairingStore { id, key -> synchronized(this) {
            val ids = prefs.getStringSet("paired_ids", emptySet()).orEmpty() + id
            check(prefs.edit().putString("paired_$id", encode(key)).putStringSet("paired_ids", ids).commit())
        } }
        for (id in prefs.getStringSet("paired_ids", emptySet()).orEmpty()) {
            prefs.getString("paired_$id", null)?.let { store.save(id, decode(it)) }
        }
        return store
    }
    fun installAuthentication() {
        val target = File(context.noBackupFilesDir, LocalMfiAuthenticationClient.DIRECTORY)
        if (!target.exists()) {
            val staging = File(context.noBackupFilesDir, "geely-auth-staging")
            staging.deleteRecursively()
            check(staging.mkdirs())
            staging.setReadable(false, false); staging.setReadable(true, true)
            staging.setExecutable(false, false); staging.setExecutable(true, true)
            try {
                for (name in listOf("identity.pk8", "certificate.p7b")) {
                    val file = File(staging, name)
                    context.assets.open("offline-mfi/$name").use { input -> file.outputStream().use { input.copyTo(it) } }
                    file.setReadable(false, false); file.setReadable(true, true)
                    file.setWritable(false, false); file.setWritable(true, true)
                }
                LocalMfiAuthenticationClient.load(staging)
                check(staging.renameTo(target))
            } finally { staging.deleteRecursively() }
        }
        LocalMfiAuthenticationClient.load(target)
    }
    companion object {
        fun deviceId(identity: AirPlayIdentity): String {
            val bytes = MessageDigest.getInstance("SHA-256").digest(identity.publicKey).take(6).toByteArray()
            bytes[0] = ((bytes[0].toInt() and 0xfc) or 0x02).toByte()
            return bytes.joinToString(":") { "%02X".format(it.toInt() and 255) }
        }
    }
}
