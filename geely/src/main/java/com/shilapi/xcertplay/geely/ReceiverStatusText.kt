package com.shilapi.xcertplay.geely

/** UI translation only. Keep protocol diagnostics and their original failure detail unchanged. */
internal fun receiverStatusEnglish(value:String):String {
    if(value.contains('\n')) return value.lines().joinToString("\n") { receiverStatusEnglish(it) }
    if(value.startsWith("连接失败：")) return "Connection failed: "+receiverStatusEnglish(value.removePrefix("连接失败："))
    return when {
        value.startsWith("未连接") -> "Not connected. Join the network and pair Bluetooth first."
        value=="已取消连接" -> "Connection cancelled."
        value.startsWith("已断开") || value.startsWith("iPhone 已断开") || value.startsWith("已返回连接页") -> "Disconnected. Ready to connect again."
        value.startsWith("正在释放连接") -> "Releasing the connection…"
        value.startsWith("CarPlay 音频协商需要麦克风") -> "Allow microphone access for CarPlay audio negotiation. Input is captured only when iPhone requests it; recordings are not saved."
        value.startsWith("麦克风权限未允许") -> "Microphone access is required for full CarPlay audio. Allow it and retry."
        value.startsWith("蓝牙权限被拒绝") || value.startsWith("蓝牙权限已变化") -> "Allow Nearby devices / Bluetooth access in app permissions, then retry."
        value.startsWith("请先点击连接并允许") -> "Tap Connect and allow the required permissions before choosing an iPhone."
        value.startsWith("没有已配对手机") || value.startsWith("未找到明确的已配对") -> "Pair your iPhone in system Bluetooth settings, then select it under Settings > Connection."
        value.startsWith("上一连接仍在释放") -> "The previous connection is still closing. Please retry shortly."
        value.startsWith("正在检查配置") -> "Checking configuration and authentication…"
        value.startsWith("连接超时") -> "Connection timed out. Check iPhone Wi-Fi, Bluetooth pairing and CarPlay approval. Allow multicast and disable client isolation on your network."
        value.startsWith("显示区域尚未就绪") -> "Display is not ready. Please retry shortly."
        value.startsWith("无法启动连接") -> "Could not start CarPlay. Check the local log and retry."
        value.startsWith("本地认证资产缺失") -> "Local authentication assets are missing or invalid. Use a correctly configured APK."
        value.startsWith("CarPlay 会话已建立") -> "CarPlay session established. Waiting for video…"
        value.startsWith("CarPlay 已连接") -> "CarPlay connected. Video is rendering."
        value.startsWith("CarPlay 传输中断") -> "CarPlay transport interrupted. Check Wi-Fi and CarPlay approval on iPhone, then retry."
        value.startsWith("正在选择已配对") -> "Selecting a paired iPhone. Keep Bluetooth enabled."
        value.startsWith("正在通过蓝牙") -> "Authenticating and exchanging network information over Bluetooth…"
        value.startsWith("网络接口就绪") -> "Network interface ready. Establishing CarPlay; not connected yet."
        value.startsWith("正在等待 iPhone") -> "Waiting for iPhone to establish CarPlay. Approve the connection on iPhone."
        value.startsWith("本地认证已就绪") -> "Authentication ready. Checking the network…"
        value.startsWith("本机蓝牙标识无效") -> "Invalid Bluetooth identity. Check the local log."
        value.startsWith("蓝牙握手失败") -> "Bluetooth handshake failed. Ordinary Bluetooth pairing is not a CarPlay connection. Check the local log."
        value.startsWith("网络配置不匹配") -> "Network configuration mismatch. Check network name, password and interface."
        value.startsWith("Wi-Fi 已变化") -> "Wi-Fi changed or disconnected. Restore the network and retry."
        value.startsWith("未能完成 CarPlay 握手") -> "CarPlay handshake did not complete. Check Wi-Fi, Bluetooth pairing and iPhone approval. Allow local traffic and multicast. See the local log for details."
        value.startsWith("连接资源尚未释放") -> "Connection resources did not close. Force stop this app in system app settings before retrying."
        value.startsWith("显示尺寸已变化") -> "Display dimensions changed. Please reconnect."
        value.startsWith("应用已离开前台") -> "The app left the foreground and disconnected. Ready to reconnect."
        value=="应用已关闭" -> "App closed."
        value.startsWith("网络名称需") -> "Network name must match Wi-Fi exactly and contain 1–32 UTF-8 bytes."
        value.startsWith("密码需") -> "Password must contain 8–63 characters. Leave empty for an open network."
        value.startsWith("密码包含") -> "Password contains an invalid character."
        value.startsWith("请输入网络接口") -> "Enter the hotspot network interface, for example wlan0."
        value.startsWith("找不到接口") -> "Network interface not found. Enable the hotspot and verify its interface name."
        value=="配置无效" -> "Invalid connection configuration."
        else -> value
    }
}
