package com.shilapi.xcertplay.geely

import java.net.NetworkInterface

internal enum class ConnectionMode { CAR_HOTSPOT, SAME_LAN }
internal data class ReceiverConfig(val mode: ConnectionMode, val ssid: String, val password: String, val networkInterface: String, val phone: String?) {
    fun validate() {
        require(ssid.isNotBlank() && ssid.toByteArray(Charsets.UTF_8).size <= 32 && '\u0000' !in ssid) { "网络名称需为 1–32 字节，与系统 Wi-Fi 名称一致" }
        require(password.isEmpty() || password.length in 8..63) { "密码需为 8–63 个字符；开放网络请留空" }
        require('\u0000' !in password) { "密码包含无效字符" }
        if (mode == ConnectionMode.CAR_HOTSPOT) {
            require(networkInterface.matches(Regex("[A-Za-z0-9_.:-]{1,32}"))) { "请输入网络接口，如 wlan0" }
            require(NetworkInterface.getByName(networkInterface) != null) { "找不到接口 $networkInterface，请先开启车机热点并核对接口" }
        }
    }
    // Deliberately never include Wi-Fi credentials in generated diagnostics.
    override fun toString() = "ReceiverConfig(mode=$mode, credentials=<redacted>)"
}

/** Same identity fallback as upstream when Android hides the physical Bluetooth address. */
internal fun receiverAirPlayConfig(identity: com.shilapi.xcertplay.airplay.AirPlayIdentity, width: Int, height: Int): com.shilapi.xcertplay.airplay.AirPlayConfig {
    val id = ReceiverStore.deviceId(identity)
    return com.shilapi.xcertplay.airplay.AirPlayConfig(
        "DiPlay Geely", id, id, "950.7.1",
        com.shilapi.xcertplay.airplay.AirPlayDisplayConfig(width, height, fps = 30),
        manufacturer = "DiPlay", model = "KX11_A2", oemLabel = "DiPlay Geely",
        microphone = true, mainBufferedAudio = true,
    )
}
