package com.shilapi.xcertplay.geely


internal enum class ConnectionMode { CAR_HOTSPOT, SAME_LAN }
internal data class ReceiverConfig(val mode: ConnectionMode, val ssid: String, val password: String, val networkInterface: String, val phone: String?) {
    fun validate() {
        require(ssid.isNotBlank() && ssid.toByteArray(Charsets.UTF_8).size <= 32 && '\u0000' !in ssid) { "网络名称需为 1–32 字节，与系统 Wi-Fi 名称一致" }
        require(password.isEmpty() || password.length in 8..63) { "密码需为 8–63 个字符；开放网络请留空" }
        require('\u0000' !in password) { "密码包含无效字符" }
        if (mode == ConnectionMode.CAR_HOTSPOT) {
            require(networkInterface.matches(Regex("[A-Za-z0-9_.:-]{1,32}"))) { "请输入网络接口，如 wlan0" }
        }
    }
    // Deliberately never include Wi-Fi credentials in generated diagnostics.
    override fun toString() = "ReceiverConfig(mode=$mode, credentials=<redacted>)"
}

/** Same identity fallback as upstream when Android hides the physical Bluetooth address. */
internal fun receiverAirPlayConfig(identity: com.shilapi.xcertplay.airplay.AirPlayIdentity, width: Int, height: Int, fps: Int = 30, icons: List<com.shilapi.xcertplay.airplay.AirPlayIcon> = emptyList()): com.shilapi.xcertplay.airplay.AirPlayConfig {
    val id = ReceiverStore.deviceId(identity)
    return com.shilapi.xcertplay.airplay.AirPlayConfig(
        "DiPlay Geely", id, id, "950.7.1",
        com.shilapi.xcertplay.airplay.AirPlayDisplayConfig(width, height, fps = ReceiverPlaybackSettings.sanitizeFps(fps)),
        manufacturer = "DiPlay", model = "KX11_A2", oemLabel = "吉利", icons = icons,
        microphone = true, mainBufferedAudio = true,
    )
}

/** Session preferences are snapshotted before connecting, never changed under a running decoder. */
internal object ReceiverPlaybackSettings {
    val bufferChoices = listOf(100, 150, 200, 300, 500, 1000)
    val fpsChoices = listOf(30, 60)
    fun sanitizeBuffer(value: Int) = value.takeIf { it in bufferChoices } ?: 200
    fun sanitizeFps(value: Int) = value.takeIf { it in fpsChoices } ?: 30
}

/** Same bundled artwork and advertised PNG fields as the original DiPlay car-home button. */
internal fun receiverHomeIcons(context:android.content.Context):List<com.shilapi.xcertplay.airplay.AirPlayIcon> {
    val bytes=context.resources.openRawResource(R.raw.ic_car_home).use {it.readBytes()}
    val (size,png)=checkNotNull(ReceiverHomeIconSizing.fit(bytes)) {"Invalid bundled Geely icon"}
    return listOf(com.shilapi.xcertplay.airplay.AirPlayIcon(size,size,png))
}
