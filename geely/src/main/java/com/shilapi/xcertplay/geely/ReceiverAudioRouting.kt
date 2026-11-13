package com.shilapi.xcertplay.geely

import android.media.AudioDeviceInfo
import com.shilapi.xcertplay.media.AudioOutputDevice
import com.shilapi.xcertplay.media.AudioOutputRoutes

internal enum class ReceiverAudioRole(val title:String,val english:String,val bus:String) {
    MEDIA("媒体音乐","Media music","bus0_media_out"),
    NAVIGATION("导航播报","Navigation","bus1_navigation_out"),
    PHONE("电话通话","Phone calls","bus4_call_out"),
    ASSISTANT("语音助手 / Siri","Assistant / Siri","bus2_voice_command_out"),
    RINGTONE("来电铃声","Ringtone","bus3_call_ring_out");
    val usage get()=when(this) {
        MEDIA -> android.media.AudioAttributes.USAGE_MEDIA
        NAVIGATION -> android.media.AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE
        PHONE -> android.media.AudioAttributes.USAGE_VOICE_COMMUNICATION
        ASSISTANT -> android.media.AudioAttributes.USAGE_ASSISTANT
        RINGTONE -> android.media.AudioAttributes.USAGE_NOTIFICATION_RINGTONE
    }
    val preference get()="audio_output_"+name.lowercase(java.util.Locale.ROOT)
}

/** Persist stable bus addresses only. Android output IDs are resolved again for every connection. */
internal object ReceiverAudioRouting {
    const val AUTO="geely_auto"
    const val SYSTEM="system"
    val choices=listOf(AUTO,SYSTEM)+ReceiverAudioRole.entries.map {it.bus}
    fun sanitize(value:String?)=value?.takeIf {it in choices} ?: AUTO
    fun isGeely(model:String)=model.contains("KX11",ignoreCase=true)
    fun bus(role:ReceiverAudioRole,value:String?,model:String):String?=when(val choice=sanitize(value)) {
        AUTO -> role.bus.takeIf {isGeely(model)}
        SYSTEM -> null
        else -> choice
    }
    fun title(choice:String)=when(sanitize(choice)) {
        AUTO -> "自动匹配吉利通道"
        SYSTEM -> "跟随系统"
        else -> ReceiverAudioRole.entries.first {it.bus==choice}.title+"通道"
    }
    fun english(choice:String)=when(sanitize(choice)) {
        AUTO -> "Match Geely channel automatically"
        SYSTEM -> "System routing"
        else -> ReceiverAudioRole.entries.first {it.bus==choice}.english+" output"
    }
    fun usages(values:Map<ReceiverAudioRole,String>):Map<Int,Int> = ReceiverAudioRole.entries.mapNotNull {role ->
        val target=ReceiverAudioRole.entries.firstOrNull {it.bus==sanitize(values[role])}
        target?.let {role.usage to it.usage}
    }.toMap()
    fun routes(values:Map<ReceiverAudioRole,String>,model:String,outputs:List<AudioOutputDevice>):AudioOutputRoutes {
        fun preferred(role:ReceiverAudioRole):AudioOutputDevice? {
            val address=bus(role,values[role],model) ?: return null
            return outputs.singleOrNull {it.type==AudioDeviceInfo.TYPE_BUS && it.address==address}
                ?: AudioOutputDevice(-1,AudioDeviceInfo.TYPE_BUS,address,address)
        }
        return AudioOutputRoutes(media=preferred(ReceiverAudioRole.MEDIA),navigation=preferred(ReceiverAudioRole.NAVIGATION),
            phone=preferred(ReceiverAudioRole.PHONE),assistant=preferred(ReceiverAudioRole.ASSISTANT),ringtone=preferred(ReceiverAudioRole.RINGTONE))
    }
}
