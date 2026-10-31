# 吉利音乐信息与自动连接适配

## 本次范围

在 DiPlay 已有 CarPlay 接收、系统栏设置和吉利导航投屏的基础上补齐 OneOS 音乐信息上报与自动启动入口。不改变状态栏、导航栏、音频焦点或自动连接的默认设置，不接管原车仪表安全信息。

## 音乐显示

DiPlay 继续向 Android MediaSession 发布媒体信息和封面，同时在固件公开并启用以下接收器时，发布 OneOS 专用音乐卡广播：

- 目标：`com.geely.online.service/com.media.control.MediaControlReceiver`
- 动作：`ecarx.intent.broadcast.action.MEDIA_CONTROL_RECEIVER`
- 内容：歌曲、歌手、稳定的曲目 ID、总时长、当前进度及播放/暂停状态。时长与进度以毫秒传递；ID 使用 64 位字段，状态 3 表示播放、2 表示暂停。
- 更新：曲目或播放状态变化立即发布，普通进度和保持更新最多每两秒一轮。沿用观察到的曲目刷新状态边沿；该广播是媒体显示元数据，不向 iPhone 发送播放/暂停命令。
- 生命周期：随现有媒体会话及音频焦点所有权发布，失去焦点时停止源声明与周期更新，断开时发送最后一次暂停状态并清理任务。吉利工厂路由使用音频引擎实际上报的归属，媒体键持有焦点的路径使用其焦点回调。关闭音频焦点管理的既有兼容选项继续生效；没有可确认归属时不主动声明原车音乐源。旧音频引擎迟到的活动和归属回调携带所属连接身份，不能影响新会话。
- 缺少接收器时不发布；系统拒绝广播时停止当前发布器的重试，保留普通音乐播放。诊断标记 `PUBLISHED_UNCONFIRMED` 只代表发送调用返回，不代表原车收到或已显示。

本次 OneOS 私有广播的封面 URL 留空，封面继续通过原有 Android MediaSession 提供。原包使用可由外部服务读取的文件 URI；没有在线车机确认该固件支持何种授权读取方式，暂不复制这条文件共享路径。因此不能承诺原车音乐卡的封面已经接通。

该接口由原车服务决定桌面/仪表如何显示；这不等于移除仪表渐变、原车媒体卡、车辆模型或底部数值，也不创建新的仪表悬浮层。没有实车验证时，不能宣称歌曲已显示到用户的仪表。

## 自动连接

GeelyCarPlay 的已保存静态导出显示：新安装默认开启开机打开与打开后连接，并在已设置时保留用户偏好；接收开机与应用替换广播，解锁前有单独的等待服务。默认开启可解释其自动连接体验，不能解释为绕过 iPhone 首次配对。

DiPlay 已有：

- 车机启动后打开。
- 打开 DiPlay 时连接。
- 选定 iPhone 蓝牙连接时唤醒。
- 默认有线、无线或上次连接方式。

本次增加 `MY_PACKAGE_REPLACED`，根据已有“开机打开”选择恢复应用；首页已存在时，也消费明确的自动启动请求。两个开关都开启、认证可用、非设置或引导入口且没有现有会话时才按保存的连接方式连接；在排队请求真正执行前再次检查，避免覆盖这期间新建的连接或用户刚关闭的选项。不会为了更新重启正在运行的会话。

停车后验证时，可在连接设置启用需要的上述选项并完成首次手机配对。能否从后台打开应用仍取决于车机固件与用户授予的权限；本次没有增加强制唤醒或解锁前服务。

## 常用应用列表

见 [吉利桌面接入核对](GEELY_LAUNCHER.md)。标准桌面入口已存在且有回归检查；原车常用列表的实际筛选规则尚未取得，未新增未经验证的私有注册调用。

## 证据与验证边界

音乐协议依据原 GeelyCarPlay APK 的补充 JADX 导出 `defpackage/hb1.java`、`mc0.java`、`p3.java`、`k5.java`；自动启动依据 `w00.java`、`BootReceiver.java`、`BootPreparationService.java` 和 `CarPlayActivity.java`。本次原始桌面 APK 已不在原路径，REA 复核返回 `target_unavailable`；因此这些行为是复用此前导出的静态证据，并非新一次成功反编译或实车观测。原文件身份记录 SHA-256：`c31d4a18395354da45cbf09c69a5d852779ee97da91f38e307cc578f6d61b8da`。

用户当前未连接车机；未安装本次 APK，未发送原车控制指令，未做仪表/常用列表/手机重连的实车验证。源码构建不配置配件认证身份，不能单凭 APK 构建成功声称可完成真实 CarPlay 连接。

## 离线检查结果

2026-10-31 对代码提交 `5aa89c06` 串行运行仓库要求的完整检查：

```sh
./gradlew :shared:testDebugUnitTest :common:testDebugUnitTest :home:testDebugUnitTest \
  :mobile:lintDebug :home:lintDebug :maphost:lintDebug \
  :mobile:assembleDebug :home:assembleDebug :maphost:assembleDebug --stacktrace
```

结果 `BUILD SUCCESSFUL`。单元测试报告合计 3145 项，1 项跳过，0 失败/错误（shared 1654、common 1485、home 6）。三个应用的 lint 和 debug 构建完成。公开源码文件检查及补丁格式检查通过。测试覆盖 OneOS 广播字段和频率、焦点失去/恢复、断开清理、旧连接回调隔离、工厂音频归属、启动请求排队后状态改变与标准桌面入口。

`mobile-debug.apk` 的包名为 `com.shihab.diplay.hudtest`，版本 `0.2.21-hud-test`，未包含配件认证容器。APK 构建成功和单元测试通过不能证明真实 iPhone 连接或原车界面显示已通过。
