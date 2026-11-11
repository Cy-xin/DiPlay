# 0.5.0 吉利图标、返回保连接与导航混音

基线：b0b4cd33（0.4.1）；分支：codex/geely-session-retention。包名com.shihab.diplay，版本112。

## 根因与改动

1. 原先AirPlayConfig没有icons，/info不发送OEM图标。现在复用common/res/raw/ic_car_home.png和原版AirPlayIconSizing的192px限制，发送真实PNG及尺寸，标签“吉利”。下次连接生效，不修改iPhone或车机系统应用。
2. 原先onHostUiRequested、系统返回和Activity.onStop直接调用endAttempt。现在区分“连接已建立”和“正在显示CarPlay”：吉利图标或返回操作只展示DiPlay，主按钮变为“返回CarPlay”；再返回桌面时移动任务至后台。只有主动断开、连接故障或销毁会话才清理。
3. 原先尺寸变化也触发断连。现在保持已协商视频尺寸，用SurfaceView等比例留边适应新窗口，触摸仍按画面区域归一化。复用原版beginSurfaceDetach的parkMain路径，离开前台时停放解码表面，返回后重新附着。
4. 为使普通后台切换有正常Android进程优先级，参考原版DiPlaySessionService增加一个非导出的、START_NOT_STICKY前台服务。仅用户启动连接时创建，通知可返回画面或主动断开；断开/失败后停止。不添加开机启动、后台轮询、悬浮窗、定位或系统应用接口。只增加对应前台服务权限。服务不独立重建会话，不保证系统强杀、任务划掉或Activity被销毁后继续连接。
5. 精简版MOBILE_COMPATIBLE路由把compatibility映射到NAVIGATION，但旧writePcm仍将所有compatibility标为priority speech，AudioFocusCoordinator会把音乐音轨设为0。这是代码可确认的静音路径，不等同已确认iPhone播放器真的暂停。现在在精简版启用navigationMixEnabled：NAVIGATION实际PCM到达后按播放队列时长压低音乐至20%，加150ms尾段；多路导航合并，播报结束/释放后恢复。保留原版focus、独立AudioTrack和DUCKED_VOLUME机制，电话/Siri仍使用独立语音优先处理。不向iPhone发送新的PAUSE/PLAY命令，不修改shared模块及其他应用音频策略。

## 检查

- 项目AGENTS要求的单测/lint/构建通过；geely应用lint与Standalone构建通过。
- geely共23项（原19项+返回/后台/主动断开、图标真实PNG、空闲无服务、尺寸变化4项回归）通过。
- geely-core共5项（原2项+实际播报才压低/到期恢复、重叠播报、新媒体轨继承、Siri优先3项组合回归）通过。
- 最小APK检查：1个Activity、仅1个指定且非导出的会话服务、0接收器/提供器；没有开机、覆盖层、系统修改权限；排除OEM/HUD/USB/P2P运行类检查及本地认证资产匹配通过。
- Redmi最终0.5.0更新安装并启动成功，空闲时dumpsys没有运行服务。保留原配置。
- 额外尝试核心库单独lint，发现52条既有错误，均在本次未改动文件（含保留但精简APK不使用的模块、库空清单权限声明及API检查）。没有隐藏或修改其规则，也没有扩展修复范围；日志随交付保存。此项不能声称通过。

## 实机验收待用户完成

连接iPhone，确认吉利图标显示；依次返回DiPlay、返回车机桌面、重新打开并返回CarPlay，确认没有新配对/连接任务且音频持续；播放音乐并触发多段导航、Siri与通话，确认导航压低而非静音、结束恢复且真正播放状态没有异常。检查通知返回和主动断开后服务停止。手机与车机两种网络模式均需确认。自动化模拟和编译均不能代替实车验收。

失败时通过“诊断”上传，按session/attempt关联background_retained、receiver_home_shown、carplay_shown、surface_fit、connection_service_started/stopped、Audio navigation overlay等事件；若iPhone实际暂停，还需结合具体导航应用和流类型继续判断，不宣称本次离线检查已经排除所有来源。
