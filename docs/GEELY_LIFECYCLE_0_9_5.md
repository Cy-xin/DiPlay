# 任务07：Geely线程与生命周期治理（0.9.5）

日期2026-11-18。基线fdd3af51（0.9.4），开始时工作区干净，沿用codex/geely-return-picture。版本码137，包名com.shihab.diplay。只执行任务07，不自动安装、不修改服务器、不执行其余任务。

## 审计表

下表覆盖GeelyActivity的异步入口及直接关联的会话、媒体和诊断基础设施。common/DiPlayActivity仅参考已有取消观察者/Handler的方式，不修改该模块。位置以类与方法定位，避免后续行号漂移。

| 位置 | 用途/归属 | 时长 | 退出与取消 | View/Context访问及结论 |
| --- | --- | --- | --- | --- |
| GeelyActivity.worker / connect | 本次连接预检、认证资产和身份/画布准备 | IO或加密可能较长 | onDestroy已有shutdown；新增取消标志和真实完成通知 | 原先仅保护最终UI投递，但会话锁可早于正在运行的预检释放，跨Activity认证文件操作可能重叠；本次修复 |
| uploadWorker / refreshLocalLog、loadLogs、exportLocalDiagnostics、pairDiagnostics、uploadDiagnostics | 用户发起的诊断、导出、配对与上传 | 文件IO/网络可能较长 | onDestroy已有shutdown；保留已提交的用户操作，后台不主动取消 | 结果/进度均检查destroyed；上传器使用Application Context。导出使用Context，不直接操作View。本次不机械改为shutdownNow；HTTP已有连接/读取超时，不将其说成整个写入过程的硬截止 |
| main Handler / timeout | 单次90秒握手超时 | 有界 | endAttempt移除，onDestroy清理；代次保护 | 主线程更新状态，已有保护，保留 |
| main Handler / recoveryTask | 既有最多两次恢复的延迟任务 | 有界 | cancelRecovery、onStop、onDestroy取消 | 主线程检查前台/停止/销毁，不增加重连次数 |
| root.post / 各处fitSessionSurface | 布局、配置变化、会话激活、旋转确认与返回画面 | 每次短，但可重复排队 | 新增按帧合并器，停止/Surface销毁/销毁时取消 | 原来少数回调缺少销毁判断；本次统一代次、Surface身份和有效性保护，执行时读取最新尺寸 |
| listener(token) | 网络/协议线程向Activity报告状态 | 会话期间 | 连接代次保护，补齐销毁检查 | 修复晚到onSessionActive仍可写入已销毁Activity；媒体协议不改 |
| geely-session-release线程 | 关闭传输/媒体，等待实际释放 | 原生或传输退出可较长 | 保留原线程名与8秒等待/晚到完成回调 | 只经受保护的main回调更新界面；本次将预检完成及原生视频退出纳入放锁条件，不增加轮询 |
| sink关闭与releasingSinks | 在当前sink引用已清空后仍追踪尚未释放的解码器 | 直到真实退出 | 复用whenVideoReleased后移除 | 修复surfaceDestroyed找不到正在释放的sink、以及close返回即视为已释放的风险 |
| surfaceDestroyed / beginSurfaceDetach | Surface销毁前交接/停放/释放解码器 | 原有约2秒截止+0.5秒宽限，具体原生调用仍取决于驱动 | 保留底层有界握手，不绕过等待 | 现在覆盖当前和正在释放的sink，记录返回值和耗时；旧Surface销毁不清空新Surface及新恢复提示 |
| ReceiverPictureRecovery | 任务05的0/1/2秒请求、3秒最终观察及一次性帧事件 | 有界 | Surface销毁、后台、断开、忽略、销毁时停止 | 原有代次与观察者取消保留，不改提示/重试行为 |
| ReceiverWheelKeys / wheelDetectionTimeout | 已连接按键监听、20秒临时检测 | 会话或20秒 | 断开解绑，检测后台/离页/超时停止 | Binder回调转主线程；闭合后丢弃输入；本次不改映射与MODE限制 |
| ReceiverAudioTest | 用户发起的5秒测试音线程 | 有界 | onStop/onDestroy停止；audioTestGeneration丢弃旧结果 | 输出用Application Context；更新UI已受保护，不改 |
| ReceiverSessionService | 显式连接的前台服务 | 会话期间 | 非粘性，弱引用owner，任务移除/主动断开停止 | 保留按Home/onStop后的连接；没有改成Activity不可见就关闭 |
| ReceiverDiagnostics.executor | 进程级脱敏日志与崩溃记录 | 进程期间，事件驱动 | 有界队列/留存，Application Context | 不因一个Activity销毁而关闭，否则会丢会话清理/崩溃日志；不是Activity泄漏，无修改 |
| CarPlayController executors、startupTimer、蓝牙guard | 协议控制、握手、触摸及原车音乐交接 | 会话期间 | 既有close关线程/资源，awaitClosed/whenClosed通知 | 使用Application Context和受代次约束的监听；不替换为lifecycleScope，不改线程名或调度 |
| AndroidMediaSink视频/音频/麦克风、停车Surface线程 | 媒体播放、录音和安全Surface交接 | 会话期间 | 复用close、await/whenVideoReleased及既有退出握手 | 此任务只调整Activity对其生命周期的跟踪，不改媒体解码、声音、0.9.3麦克风或0.9.4帧观察逻辑 |
| common/DiPlayActivity | 原版成熟实现参考 | 多种原版功能 | 已有观察者移除、代次递增与Handler取消 | 本最小版不加载其复杂UI，不做全面线程替换，不同步改动 |

## 实际修复

1. ReceiverSurfaceWork在下一帧合并适配请求，保持同轮恢复意图；最新Surface/代次替换旧请求时不沿用旧恢复意图。实际执行前再次校验。停止和销毁取消回调，销毁末尾再次取消endAttempt可能生成的布局请求。
2. ReceiverPreparation区分“已取消”与“实际离开预检”。取消后的排队任务跳过准备；正在运行的准备在实际退出后才发完成通知。全局会话锁须等预检完成，防止新Activity与旧认证文件IO重叠。
3. 复用whenVideoReleased等待真实解码器退出；close调用返回不等于媒体资源已退出。正在释放的sink仍参与Surface销毁握手，释放完成后移除。传输、媒体或预检未完成时不提前允许新连接。
4. 对晚到会话回调补齐destroyed保护；销毁旧Surface仅交接该Surface，不清空新Surface或其画面恢复流程。记录surface_detach_result的确认结果、sink数量、身份及耗时；未确认时记WARN，不伪造成功或跳过底层交接。

没有新增依赖、服务、后台轮询或重连策略。没有修改UI、音频路由、Siri麦克风适配、原版common/shared/geely-core代码。两个Executor原本已有shutdown，未为“治理”而重写。

## 回归证据

- 原有后台连接、返回画面、旋转、帧恢复、上传、按键及音频测试继续运行。
- 帧内重复请求只执行一次，最新Surface/代次胜出；取消丢弃待执行任务，并保留有效的画面恢复意图。
- 取消排队准备跳过动作；取消正在执行的准备不提前通知完成。
- 真实Activity销毁后仍持有预检锁，后台预检实际退出后才释放；晚到会话激活不改销毁后的UI状态。
- 旧Surface销毁不清空新Surface，当前Surface销毁仍清除对应恢复提示。
- 用Robolectric假codec模拟忽略中断的原生卡住：Activity断开后仍追踪旧渲染器，Surface交接记录未确认；全局锁保持，fake原生调用退出后才放锁。测试不是实车驱动验证。

运行AGENTS要求完整CI及Geely/core单测、Geely lint与standalone构建，最终结果和APK核对文件在交付目录。本次无视觉改动，无新增截图需求；原有原生截图测试仍执行。

## 未验证与风险

未安装、未进行本轮实车验证。需要验证返回车机桌面/再进入、横竖屏、取消握手后重连、Surface切换和后台音乐保持。按帧适配比直接post最多增加到下一帧的等待，以消除同一帧重复工作。
真正不返回的原生调用或文件IO仍无法靠普通应用安全强制回收；此时宁可继续阻止第二个连接，也不假装已释放。上传的活动操作保留既有完成策略，极慢网络写入的总耗时并无硬保证，本任务没有扩展到上传协议重构。0.9.3实际Siri收音仍需单独实车验收。
