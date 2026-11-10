# DiPlay Geely 最小接收端

## 交付状态

这是带本地认证的**待实车验证测试版**，不是已经证明两种模式都能稳定连接的正式版。
本次没有接入 KX11_A2 或真实 iPhone，首次配对、画面、触摸、声音、断开重连均需在车上验收。
历史证据：用户确认吉利独立热点手动填写信息、指定 `wlan0` 曾连接成功。
这条历史记录不能代替本 APK 的测试。Same LAN 上游有其他车型成功记录，不代表本车通过。

## 源码与基线

- 独立目录：`DiPlay-Geely-MVP`，分支 `codex/geely-minimal`。
- 从现有 fork 的 `490f22fe515c5ca5ef2fdf83ff39f82bb0950cb4` 建立，完整 Git 历史保留。
- 协议来源：原版 DiPlay `v0.2.21`，上游发布提交 `49bf3bc2`；fork 已合并该版本。
- 原有 `mobile/common/shared/vehicle-probe`、旧兼容包和原分支不改动。
- `geely/`：新的单页入口、私有配置和配对存储、连接生命周期。
- `geely-core/`：从基线复制的协议核心；无线编排、AirPlay 监听和 Android 媒体接收器已单独精简。
- 原项目的可选协议实现保留在历史源码中；最终 APK 通过 R8 剔除不可达代码，不能仅依据源目录判断 APK 功能。
- 原项目许可和第三方声明保留。没有推送分支、发布 Release 或上传认证资产。

## 保留与移除

| 范围 | 最小版处理 |
| --- | --- |
| 两种网络后端 | 仅 ManualHotspotManager / ExistingWifiManager 可达 |
| 发现与握手 | 保留 Bluetooth RFCOMM/iAP2、Bonjour、多地址监听、认证、AirPlay 配对和运行隧道 |
| 手动热点 | 用户自行开启；默认接口 wlan0，可改；先检查接口存在，再由原后端核实地址/归属 |
| 吉利独立热点读取 | 保留原后端的只读网络接口/地址识别，作为热点链路基础设施；不改系统设置 |
| 画面和触摸 | 原 MediaCodec/SurfaceView 与 HID；H.264、30 fps，尺寸按实际视区取偶数 |
| 音频 | 原 AudioTrack 解码、缓冲和标准音频焦点；没有 OEM 音乐卡片或音频参数编辑 |
| 配置与重连 | 两种模式分别保存网络凭据，保存 AirPlay 身份/配对，可指定已配对手机；只由用户发起重连 |
| USB、Wi-Fi Direct、Local-only AP | 无入口；控制器拒绝其他模式；有线编排与 VPN/NCM 监听路径已删除 |
| 后台 | 无 Service、Receiver、Provider、WorkManager、开机启动；离开前台即结束连接 |
| 自动热点、ADB、系统修改 | 无运行入口；不请求 Root、系统签名、写设置、安装包或悬浮窗权限 |
| HUD、仪表、地图、车辆数据、账号、音乐卡片、氛围灯 | 无界面、初始化、运行任务或清单组件；最终包检查 R8 裁剪结果 |
| 扩展功能依赖 | 最小端不依赖 common、vehicle-probe、Compose、Media3、WorkManager、ZXing 或 Android Car App 库 |
| 基础音频能力 | 0.1.2 恢复原版麦克风能力声明和请求式上行，用于验证 iPhone 音频设备识别；需要用户手动允许录音权限，不增加 Siri/通话界面 |
| 日志 | 仅应用私有目录 connection.log，限制大小；不导出、不上传，密码在日志入口脱敏 |

## 两条链路与真实连接判定

两种模式共同流程：用户完成系统蓝牙配对 → 选择或自动识别已配对 iPhone → 本地认证 →
复用原 iAP2 网络凭据与地址交接 → 同一接口上的 Bonjour 和 AirPlay TCP → 配对/加密 →
媒体协商 → 解码首帧 → 显示 CarPlay，触摸经 HID 发回 iPhone，声音经 Android AudioTrack 播放。

蓝牙用于首次配对、认证及 Wi-Fi 端点交接，成功后优先切换运行 iAP2 隧道，必要时保留原蓝牙控制回退。
不进行新的蓝牙扫描或后台重连轮询。手机已连 Wi-Fi、接口就绪、TCP 建立或控制会话开始都不标为画面已连接；
只有实际解码渲染首帧后才进入画面页。首帧也不能证明触摸和声音已经通过验收。

单次尝试总等待上限 90 秒，可随时取消。失败/取消/离开前台后关闭控制器和监听、释放媒体资源；
未确认释放时禁止创建下一任务。网络变化结束本次尝试，用户恢复网络后重连。

## 最短操作步骤

### 车机热点

1. 在原车设置中开启热点，让 iPhone 加入，确认名称与密码。
2. 在原车蓝牙设置中与 iPhone 配对，保持双方蓝牙开启。
3. 打开 DiPlay Geely，选择“车机热点”，填写名称/密码；默认 `wlan0`，必要时展开配置修改。
4. 点击“连接 CarPlay”，在 iPhone 上允许 CarPlay。连接后检查画面、点按、播放音乐。
5. 点击右上角“断开 CarPlay”后可再次连接。

### 同一局域网

1. 车机和 iPhone 加入同一可互通的 Wi-Fi；优先 WPA2-Personal，或 WPA2/WPA3 混合网络。
2. 两者在系统设置中完成蓝牙配对，保持蓝牙开启。
3. 打开 DiPlay Geely，选择“同一局域网”，填写路由器 Wi-Fi 的准确名称/密码，点击连接并在手机允许。
4. 若失败：确认不是访客隔离网络，关闭 AP/客户端隔离，允许局域网通信与 mDNS 组播；检查手机授权和蓝牙配对。
5. 开放网络密码留空；WPA3-only、企业认证、需网页登录的网络本版不支持。

多部手机时，展开配置选择一部已配对 iPhone。权限被拒绝时，在系统应用权限中允许附近设备再重试。
Android 11 的基本蓝牙与网络权限在安装时获得；0.1.2 连接前还需用户允许麦克风权限，才能如实声明完整输入输出能力。不索取车辆或后台扩展权限。
定位权限仅声明用于系统可读 Wi-Fi 详情，不用于 GPS 上报；名称被系统隐藏时，仍按用户手工配置验证。

## 本地构建

沿用原版 JDK 25、Gradle wrapper、Android SDK 37 和缓存依赖；最小端 min/target SDK 均为 30，
专供 Android 11 私有侧载，不面向 Google Play。核心无原生编译任务。

```sh
export JAVA_HOME=/path/to/jdk25/Contents/Home
export ANDROID_HOME=/path/to/Android/sdk
export DIPLAY_AUTH_ASSETS_DIR=/private/local/runtime-assets
./gradlew :geely-core:testDebugUnitTest :geely:testDebugUnitTest :geely:lintDebug :geely:assembleStandaloneDebug
```

认证目录只能包含 `offline-mfi/identity.pk8` 与 `offline-mfi/certificate.p7b` 两个文件。
本次复用既有本机构建暂存的认证文件，置于外部私有临时目录接入；没有从 APK 重新提取。
已检查两者可解析、密钥和证书匹配及证书日期有效，运行时还会再次校验；这不保证 iPhone 接受。
无认证时 standalone 构建失败；普通 assembleDebug 只供源码检查，不应拿去宣称可连接。
最终本地 APK 含运行必需的实验性身份，不向 Git 或远程服务上传。

输出：`geely/build/outputs/apk/debug/geely-debug.apk`。
使用本机 Android 测试签名，包名固定 `com.shihab.diplay`，无 `.hudtest`。0.1.2 音频对照测试版的 versionCode 为 102。
与既有同包名应用能否直接升级，取决于签名是否一致；不要未经备份或确认自行卸载旧版。

完整上游回归命令仍遵循根目录 AGENTS.md；它会另外构建原版/示例包，请勿误装那些产物。
最终检查记录、APK 校验和与横屏截图放在本次交付目录。
