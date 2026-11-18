package com.shilapi.xcertplay.geely

internal enum class ReceiverSettingsGroup(val zh:String,val en:String) {
    CONNECTION("连接","Connection"),PLAYBACK("音频与画面","Audio & display"),APPEARANCE("外观","Appearance"),WHEEL("方向盘","Steering wheel")
}
/** Stable identities drive filtering; translated labels never decide where an item lives. */
internal enum class ReceiverSetting(val group:ReceiverSettingsGroup?,val keywords:String) {
    AUTO_CONNECT(ReceiverSettingsGroup.CONNECTION,"自动连接 打开 启动 auto connect launch"),
    BLUETOOTH(ReceiverSettingsGroup.CONNECTION,"蓝牙配对 蓝牙 配对 Bluetooth pair"),
    PHONE(ReceiverSettingsGroup.CONNECTION,"已配对 iPhone 手机 phone"),
    BUFFER(ReceiverSettingsGroup.PLAYBACK,"音频缓冲 音频 缓冲 audio buffer"),
    AUDIO_ROUTE(ReceiverSettingsGroup.PLAYBACK,"音频通道映射 输出通道 5秒测试 音量 volume 音频 通道 映射 音乐 导航 电话 语音 铃声 测试 navigation routing channel Siri test tone"),
    LOW_LATENCY(ReceiverSettingsGroup.PLAYBACK,"低延迟解码 低延迟 解码 latency decoder"),
    FPS(ReceiverSettingsGroup.PLAYBACK,"CarPlay 帧率 fps frame"),
    QUALITY(ReceiverSettingsGroup.PLAYBACK,"CarPlay 画面清晰度 清晰度 分辨率 resolution quality detail"),
    SIZE(ReceiverSettingsGroup.PLAYBACK,"CarPlay 界面大小 界面 大小 图标 缩放 size scale"),
    DOCK(ReceiverSettingsGroup.PLAYBACK,"CarPlay 菜单栏位置 菜单栏 位置 dock position"),
    DISPLAY_INFO(ReceiverSettingsGroup.PLAYBACK,"画面信息 画面 信息 display applied"),
    THEME(ReceiverSettingsGroup.APPEARANCE,"主题 浅色 深色 跟随系统 跟随 系统 theme light dark system"),
    SYSTEM_BARS(ReceiverSettingsGroup.APPEARANCE,"隐藏状态栏和侧边栏 全屏 状态栏 侧边栏 导航栏 fullscreen system bars sidebar"),
    WHEEL(ReceiverSettingsGroup.WHEEL,"方向盘按键 播放／暂停 播放/暂停 MODE长按 长按 方向盘 按键 映射 下一首 上一首 播放 暂停 实体按键 检测 测试 physical wheel key MODE Siri detect test"),
    DIAGNOSTICS(null,"诊断与日志 连接自检 运行指标 诊断 日志 上传 服务器 配对码 运行 指标 自检 耗时 diagnostics logs upload server pairing report metrics timing");

    fun visible(query:String,selected:ReceiverSettingsGroup):Boolean {
        val words=query.trim().split(Regex("\\s+")).filter {it.isNotEmpty()}
        return if(words.isEmpty()) group==selected else words.all {word ->
            (keywords+" "+group?.zh.orEmpty()+" "+group?.en.orEmpty()).contains(word,true)
        }
    }
}
