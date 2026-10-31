# 吉利主屏「应用建议」卡片：原因与接入边界

## 已确定的原因

本车主屏左下的「应用建议」卡片由原车 `com.geely.recents/com.geely.recents.appsuggest.SuggestProvider` 提供，绑定关系已通过只读车机检查确认。它采用原车 APK 中固定的包名白名单。GeelyCarPlay 使用的 `io.dushu.car` 已在名单内；DiPlay 正式包 `com.shihab.diplay` 和调试包 `com.shihab.diplay.hudtest` 均不在名单内。

因此，GeelyCarPlay 能进入这张卡片的关键条件在原车端。仅修改 DiPlay 的名称、图标、主入口或任务属性，不能把 DiPlay 加进原车白名单。本次没有添加无依据的注册接口，也没有更改 DiPlay 包名、替换原车应用、修改系统资源或写入原车数据。

该结论针对本次取得的 KX11_A2 固件样本，不代表所有吉利车机都使用同一规则。

## 卡片如何工作

1. `AppSuggestManager.init(Context)` 读取原车资源 `array/app_suggest_white_list`，注册任务栈监听器。
2. 前台应用变化后，只有 `topActivity` 的包名在名单内，才会从历史列表去重并移到第一位，然后刷新卡片。
3. `getAllAppDataList(Context)` 在显示前再次删除未安装或不在白名单内的包，并保存历史缓存 `APP_SUGGEST`。
4. `SuggestProvider.showData(Context, RemoteViews)` 最多取前四项显示图标。点击后通过包管理器取得该包的普通启动入口，在主屏打开应用。

这是按前台使用先后排列的建议列表，不能据此称为按使用次数统计的「高频排行榜」。名单内应用在启动并成为前台后才具备更新列表的条件；并非安装完成就保证立即出现。

发送卡片刷新广播只会重新读取、过滤现有列表，不会增加白名单成员。原车是否在某个系统界面提供白名单编辑功能，尚未验证。

## 静态证据与验证范围

只读取得的 `Recents.apk`：12,367,260 字节，SHA-256：

`63f414551a1afe173b40faca60c83a2fa65160343a281900c8ba420e468b3fc3`

ZIP 完整性检查通过。Android SDK Build-Tools 36.0.0 的 `aapt2 dump resources` 读取到 `app_suggest_white_list` 有 47 个条目，其中 `io.dushu.car` 出现两次，两个 DiPlay 包名均不存在。

REA 保留了以下完整方法记录：

| 事实 | 方法 | Evidence ID |
| --- | --- | --- |
| 加载固定白名单、监听前台应用、仅名单内应用移到首位 | `com.geely.recents.appsuggest.AppSuggestManager.init` | `ev_eb1b82dd86806ff49096c0457d4cf95971474b0fac69f84ec1cb1538e199cd01` |
| 显示前再次过滤已安装状态和白名单 | `com.geely.recents.appsuggest.AppSuggestManager.getAllAppDataList` | `ev_c746266c26b7fd72e1959fce01de45f2b1f45e046e06b64232fb35f778d1e7b6` |

上述两个方法均为完整 Java 表示，无 smali 回退或截断标记。最多四项、点击启动和历史缓存行为还参考了同一 APK 的补充 JADX 导出。`SuggestProvider.showData` 的图标局部有类型推断警告，因此不将这部分导出当作可直接编译的原始源码。

这是原车样本的静态实现与当前卡片绑定关系核对；没有安装修改版、强制刷新卡片、运行替代包或验证修改后的实车效果。

## 可采取的方案

- **保留普通 DiPlay 安装方式**：继续使用已有应用入口或 DiPlay 已有的桌面悬浮球功能。它们不依赖这张原车卡片的固定白名单。当前车机是否允许悬浮显示，仍以实际授权为准。
- **由车机固件提供正式适配**：在原车 `com.geely.recents` 的建议名单中加入 DiPlay 包名，或使用固件明确支持的自定义入口。这样才是针对这张卡片的直接修改；需先确认系统支持的资源配置方式、签名和恢复路径。
- **继续研究可配置入口**：可以先只读检查这套固件是否有受支持的名单配置接口，再决定是否制作独立适配组件。当前未找到可由普通 DiPlay 应用调用的接口，不能先做一个声称有效的开关。

不把 DiPlay 改名成 `io.dushu.car` 作为默认方案：这会与同包名应用冲突，而且原车其他媒体功能也对该包名和具体接收器类名有预设，迁移包名不等于完整、可靠的系统兼容。

## 与其他应用区域的区别

「应用建议」不是左侧快捷栏、全部应用页、最近任务页，也不是 `com.geely.pushwidget` 的云端应用商城推荐卡。

补充分析发现：原车 Launcher 的全部应用目录还会合并内置资产配置和应用商城目录；`com.geely.pushwidget` 的 `HomeAppWidget` 则消费云端 `BannerData`。这些路径不能替代本卡片的 `AppSuggestManager` 白名单机制，也不能据它们宣称 DiPlay 已进入「应用建议」。

已有 `GeelyLauncherEligibilityTest` 只保护 DiPlay 标准 Android 主入口的可解析性、公开状态、最近任务可见性、现有任务边界及名称图标声明。它不测试也不保证原车建议白名单中的成员资格。
