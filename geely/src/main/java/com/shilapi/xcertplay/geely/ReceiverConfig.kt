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
