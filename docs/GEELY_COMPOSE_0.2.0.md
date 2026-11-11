# DiPlay Geely 0.2.0：A 冷灰蓝 Compose 重构

基线：0.1.4，b1f101fc。独立本地分支 codex/geely-compose-coolblue。包名 com.shihab.diplay，应用名 DiPlay Geely，versionCode 105；min/target API30，compile37。

## 信息架构与页面

- 连接页：只保留两种连接方式、网络配置、阶段/失败提示、连接/取消/重试。底部操作固定，配置单独滚动。宽屏的网络名称、密码并排；窄屏、大字体上下排。短窗口收紧留白，并独立判断底部是否可横排，不能仅靠宽度判断整页布局。
- 设置：连接（系统蓝牙配对、iPhone选择、热点接口）、播放（媒体缓冲、30/60fps）、外观（三种主题）、诊断。关键词查找定位已有设置；不创建账号、通知、内容流等不存在的业务。
- CarPlay：保留原 SurfaceView、解码与触摸桥接，画面为主，仅有断开入口。设置/主题不重新创建 SurfaceView，也不改变 iPhone CarPlay 的主题。

## 依赖与边界

在 geely 模块新增项目已有 Compose 编译插件 2.2.10、BOM 2026.02.01（UI/Foundation/Animation 1.10.4，Material3 1.4.0）、Activity Compose 1.8.0、Preferences DataStore 1.1.7。不升级其余模块依赖，不引入 common/vehicle-probe、Haze、图片加载、导航框架或后台任务。

- GeelyActivity 改为 ComponentActivity，为 ComposeView 提供生命周期及系统返回派发；连接控制器、认证、视频/音频引擎源码均未改。取消、超时、资源释放、任务互斥、首帧判定和后台断开保留。
- 原 SharedPreferences 中的身份、配对、网络、播放设置保持；DataStore 只存新增 theme。UI 输入在离开前台时保存，避免键入配置因重建丢失。
- 主题切换不重建 Activity。系统 UI_MODE 改变仍只有实际视频尺寸变化才结束旧会话。应用返回先退出弹窗/设置，再处理连接。
- 去除 Compose 依赖默认合并的 Startup Provider 与 ProfileInstallReceiver；最终APK保持一个Activity，零服务、接收器、Provider。
- Compose/DataStore 带入官方 libandroidx.graphics.path.so 与 libdatastore_shared_counter.so，精简验证器改为明确白名单；没有引入车辆或认证 native 库。APK约4.3MB，较旧原生界面版本增加约2.1MB。

## 设计参数

浅色：背景 #EEF2F7，表面 #FAFCFF，正文 #172434，说明 #526174，主操作 #2457C5，按钮文字白色。
深色：背景 #151D29，表面 #202B3A，正文 #EDF3FC，说明 #AAB8CC，主操作 #9FBEFF，按钮文字 #122A57。

正文/说明/按钮的纯色组合对比度，浅色15.27/6.15/6.47，深色12.82/7.11/7.55。玻璃表面高不透明度：API31+ .92，API30 .98。对比度以最终稳定状态核验；不是对所有动画中间帧或第三方系统栏的认证。

面板24dp圆角、输入与按钮16dp；4/8dp间距；标题28sp（短窗口24sp）、模式20sp、说明与状态16sp。触控目标至少48dp，主按钮64dp。220ms颜色插值遵循系统动画倍率。无持续装饰动效、骨架屏、复杂转场或图像请求。

API31+ 的 GlassSurface 在限定面板内，重新绘制并对齐应用自身的静态渐变背景，仅模糊该背景层16dp，再叠加高不透明度表面、1dp细边框和2dp阴影；文字在独立前景，不被模糊。不会读取视频或系统壁纸。API30不调用模糊，使用高不透明度表面；不是在Android11承诺真实背景折射。

## 自查

| 项目 | 结果与边界 |
|---|---|
| 分类 | 核心操作留在首页，参数集中分组；设置及单选至多三级，无底部多栏目 |
| 主题 | DataStore持久化、手动浅深色、跟随系统；切换不主动重启Activity/连接。实机冷启动闪屏仍需观察 |
| 玻璃 | SDK条件分支；模拟器检视API35。API30生命周期测试通过，吉利API30实际外观/流畅度未测 |
| 适配 | 标准1280×720、紧凑1024×600、字体1.35、RTL镜像截图；系统车机栏占用的实际空间计入布局 |
| 可读性 | 色板纯色对比度计算通过，实际浅深色截图人工检查；禁用控件/系统栏另计 |
| 无障碍 | 系统字号、语义单选、明确文字按钮、状态live region、保留系统返回；完整TalkBack人工遍历未完成 |
| 语言 | 中英UI文案、常见状态英文映射、允许RTL。阿拉伯语仅验证方向镜像，使用中文回退，不宣称完成阿语翻译 |
| 性能 | 局部静态背景模糊、稳定视频层、没有滚动装饰；未进行吉利车机GPU/帧耗时分析，不能声称60/120fps |
| 业务 | 单测覆盖重复连接、取消、会话失败、夜间变化、配对标识、音频协商权限与配置；本版CarPlay实连待用户复测 |

## 复测

1. 两种连接方式分别试：首次连接、画面、触摸坐标、音乐与导航声音。
2. 取消/失败重试/断开重连不创建重复任务。
3. 设置 → 外观切换；重开保留选择，跟随系统响应夜间变化。
4. 设置 → 播放：保留原音频缓冲档位和30/60fps，下次连接生效；120fps仍不支持。
5. 吉利车机实际分辨率、字体、屏幕密度、键盘弹出及系统返回。

旧0.1.4 APK保留，可覆盖安装回退；不卸载应用以免丢失配对与身份。新主题数据独立，不要求旧版识别。

参考：
- https://developer.android.com/develop/ui/compose/migrate/interoperability-apis/compose-in-views
- https://developer.android.com/topic/libraries/architecture/datastore
- https://developer.android.com/develop/ui/compose/graphics/draw/modifiers
