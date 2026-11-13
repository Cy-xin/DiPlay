# DiPlay Geely 0.6.3：复用原版车机热点实现

- 开发分支：`codex/geely-hotspot-reuse`，当前版基线：`48d78c96`。
- 原版来源：本地 `DiPlay-0.2.22`，提交 `eb27fea273e5f0c01a3ad928a2c2da89731ac110`。
- 复用前逐文件与该提交比对，六个核心文件均一致。`ManualHotspotInterfaces` 仅将 AndroidX 注解换成已有 Android 平台注解，避免引入依赖。

## 实际改动

直接复用原版 ManualHotspotManager、ManualHotspotInterfaces、ManualHotspotReadiness、WirelessStartupPolicy、ConnectionFailureCause 和 CarPlayBonjour：

1. 先确认选中的接口是否属于 ECARX 独立热点，再判断 Android 热点配置是否适用，避免把另一套 Android AP 的 SSID/安全配置误用于吉利独立热点。
2. 保留用户指定接口，按原版稳定采样等待就绪；接口名称先检查格式，存在性由连接线程限时检查，不再因接口尚未出现而立即拒绝。
3. 不把蜂窝数据接口当成热点；未读到 AP 所有权时保留未知状态。
4. mDNS 使用带名称缓存的本地地址，避免反向 DNS 阻塞；保留 IPv6 scope；探测响应首行限定 8 KiB。
5. 手动热点恢复原版 15 秒接口等待预算；失败时释放管理器，取消和再次连接继续沿用现有会话代次保护。

没有引入自动开热点、ADB 授权、Wi-Fi Direct、USB 或新后台轮询。蓝牙 iAP2 配对/认证和原有 TCP、视频、触摸、音频链路保留。同一局域网模式不变（共用上述 mDNS 修复）。无新增依赖，密码不写日志，认证资产继续从已有外部安全目录接入。

## 最短操作

在车机系统设置手动开启热点 → 在 DiPlay 选“车机热点”，填实际热点名称和密码，接口先用 `wlan0` → iPhone 加入该热点并完成蓝牙配对 → 点击连接。

若接口不就绪，检查热点是否已开启、实际接口是否为 wlan0；等待失败结束后可重试。能进入 CarPlay 画面、操作并听到声音才算连接成功。

## 验证边界

复用原版接口稳定性、独立热点路由、蜂窝接口排除、5 GHz companion、mDNS 有界探测回归测试。构建及检查结果随 APK 一并交付。没有连接吉利实车，本次不能把代码复用和离线检查当成实车成功证据。

离线检查结果：项目规定的全部 Gradle 检查成功；geely-core 53 项、geely 37 项、shared 1654 项（原有跳过项保留）、common 1502 项，失败为零。应用 lint、APK 签名、包名、组件/裁剪/外部认证资产核验通过。Redmi 当前不在线，未安装本版。APK SHA-256：`3c26f13a0e0c4699e6d73d99c9794e7c3e402e1e96b6259f1b68bf2fb711c008`。
