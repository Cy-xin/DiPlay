# 吉利主屏常用应用列表：接入核对

## 本次结论

GeelyCarPlay 已显示在该车主屏的常用应用列表，但现有证据不足以确定它如何被原车桌面排序、筛选或固定。DiPlay 已有相同的标准 Android `MAIN` / `LAUNCHER` 入口，不能把重复声明该入口当作新增吉利常用应用接入。

这次没有新增未经验证的 OEM 注册方法、伪造使用记录、广播常用列表变化，也没有修改原车桌面数据。常用列表的实际新增和实车验证尚未完成。

## 已核对的证据

- 原 GeelyCarPlay APK 的 REA manifest 记录：包名 `io.dushu.car`，主入口 `io.dushu.car.CarPlayActivity`，`exported=true`，标准 `MAIN` / `LAUNCHER`。该记录未声明常用列表专属权限、元数据或接收器。Evidence：`ev_ee14b104493b52e834ee053b4e76d582e4a36b4708e6c4a54395f54e4fa3de44`，对应 APK SHA-256 `c31d4a18395354da45cbf09c69a5d852779ee97da91f38e307cc578f6d61b8da`。当前原始 APK 已不可读取，本结论复用先前保留的完整 Evidence，不声称本次重新反编译成功。
- 原 APK 的已保存补充 JADX 导出中，没有发现独立的常用应用注册调用。此为有范围的负面搜索，不能证明所有动态、反射或原车端行为均不存在。
- DiPlay 的 `common/src/main/AndroidManifest.xml` 同样向桌面公开 `DiPlayActivity` 的 `MAIN` / `LAUNCHER` 入口。播放页公开且没有 `excludeFromRecents=true`。`mobile` 的应用名称与图标仍为 DiPlay 与现有 CarPlay 图标。
- DiPlay 播放页有独立 `${applicationId}.carplay` task affinity；GeelyCarPlay 原 manifest 未为播放页声明该属性。DiPlay 的 `OwnScreenStart.kt` 说明这个任务边界涉及已有 BYD 桌面卡片兼容性。没有桌面筛选规则证据，不能据此认定它是常用列表缺失的原因，也不应全局删除。
- 本车软件清单记录原车桌面为 `com.android.launcher3`，APK 路径 `/system/priv-app/oneOS_Launcher3/oneOS_Launcher3.apk`。当前已有检测档案中没有这个 APK，本次无线调试未建立可用连接，无法继续读取原车列表实现。
- 已有 SystemUIPlugin 导出包含 `RECENT_CHANGE_INTENT` / `recentPackages` 常量，但在该导出中没有找到其完整发布、接收和列表筛选链路。常量本身不是可调用的注册接口。

GeelyCarPlay 会向原车在线媒体服务发布音乐信息；是否因此影响“常用应用”或媒体来源列表，需要桌面代码或前后状态对照证明，当前不能混为一谈。

## 已增加的回归保护

`GeelyLauncherEligibilityTest` 通过 Android 包管理器验证主入口可解析、启用并公开，主入口及播放页没有排除最近任务；同时检查既有播放任务边界以及移动端应用名称和图标声明。这些检查只能保护标准 Android 桌面可见性，不能验证吉利常用列表中的排名或固定状态。

## 后续验证所需的最小证据

1. 在车机已连接时，只读取得原车 Launcher APK，记录版本和 SHA-256；跟踪常用应用视图的数据来源、白名单/黑名单、使用统计与任务组件匹配规则。
2. 确认用户看到的是主屏常用应用区、左侧快捷栏、最近任务，还是媒体来源列表；保存对应屏幕与列表状态。
3. 同样操作启动一次 DiPlay 与 GeelyCarPlay，比较原车列表变化和只读日志，记录实际包名、主入口、播放页与任务信息。不能将手工启动后的出现归因于某个未调用接口。
4. 如果桌面确有公开或可验证的应用注册契约，再加入仅针对本车固件的最小适配，并保留拒绝、服务缺失和其他车机的退化路径。
