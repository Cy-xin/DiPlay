# 吉利/OneOS 兼容包构建（io.dushu.car）

## 本次范围

新增一个**独立可选**构建变体，安装包名为 `io.dushu.car`，用于接管吉利原车的两项既有能力：主屏「应用建议」卡片入榜、原车媒体卡的正反向控制。

它不改变普通 `com.shihab.diplay` 构建的任何行为，也不与之互斥——两者可以同时安装。选择兼容构建的唯一方式是显式传入构建属性，默认构建不受影响。

## 为什么必须是独立包名

原车桌面按**精确包名**过滤媒体卡候选，原车 `OnlineMusicService` 也按固定组件名下发指令：

- 广播接收器：`io.dushu.car.receiver.MediaControlReceiver`
- 拉起跳板：`io.dushu.car.mvp.view.TransparentActivity`

包名或组件名差一个字符，APK 仍能正常安装，然后静默地什么都不做。因此校验必须落到构建产物本身，而不是构建日志。

## 构建

```bash
# 兼容包（需要认证资产）
DIPLAY_AUTH_ASSETS_DIR=/absolute/path/to/runtime-assets \
  ./gradlew :mobile:assembleStandaloneDebug -Pdiplay.geelyOemCompatibility=true

# 普通包（不受影响）
./gradlew :mobile:assembleDebug
```

`DIPLAY_AUTH_ASSETS_DIR` 需包含 `offline-mfi/identity.pk8` 与 `offline-mfi/certificate.p7b`。没有认证资产的构建装到车上仍可入卡、可上报，但无法与 iPhone 建立 CarPlay 会话。

开启属性后 `applicationId` 变为 `io.dushu.car`，debug 变体**不再**追加 `.hudtest` 后缀——该后缀会让包名不在原车名单内，得到一个装了也没用的包。`versionName` 追加 `-geely` 以便区分。

## 校验

```bash
scripts/verify_compat_apk.sh <path-to-apk>
```

脚本校验五项并要求全部通过：包名精确等于 `io.dushu.car`、两个组件 `enabled` 且 `exported`、两份认证资产存在。任一项不符即返回非零，可接入 CI。

## 代码约束（来自实车实测，不是推断）

2026-10-31 在目标车机上实测得到三条约束，直接决定了实现方式：

1. **进度查询是心跳，不是优化。** 媒体卡可见期间原车约每 0.7 秒下发一次 `MEDIA_CONTROL_CURRENT_POSITION`，约 4 秒无人应答即判定该媒体源离线并停止查询。因此 `GeelyMediaCardPublisher.publishNow()` 会回一整套状态（含 `RECEIVER_MEDIA_APP_STATUS`），并重置 2 秒周期心跳。多个屏同时查询时按 250 ms 合并，避免广播风暴。

2. **原车从不广播 PLAY。** 卡片上的暂停键发 `MEDIA_CONTROL_PAUSE`，但播放键会直接拉起 `TransparentActivity`（`MAIN` + `OPEN_PLAY`）。所以「播放」必须由跳板兑现为一次播放请求；若此时 CarPlay 尚未连接，请求最多保留 30 秒，在连接建立后补发一次，超时丢弃，避免隔很久之后意外出声。

   跳板**只在没有会话可播放时才拉起主界面**。会话在线时按播放键只发播放请求，不打开窗口：此时应用正全屏投屏，拉起 `DiPlayActivity` 会盖住投屏、窗口被替换后再退回去，观感就是屏幕跳走又跳回（实车录像已确认，首版正是因此每次按键都会闪一次）。没有会话时打开窗口才有意义——那是连接手机的入口。

3. **`OPEN_PLAY` 携带的书 ID 和进度是本应用自己上报值的回显**，不是 iPhone 书库标识。核对后不作转发、不据此 seek iPhone，只把这次点击理解为「打开并播放」。

后台启动限制同样已实测：`MEDIA_CONTROL_OPEN_APP` 分支里的 `startActivity` 不抛异常但不出现窗口（BAL 静默拦截），因此不能靠返回值判断成败。原车自身以 `android.uid.system` 运行，拉起跳板不受此限制，这也是唯一可用的「打开」路径。

`MEDIA_CONTROL_OPEN_APP` 仍会尝试打开主界面，但那是原车明确的「打开应用」语义，与卡片播放键无关；跳板路径受后台启动限制，实测不会真的出现窗口。

## 不做的事

- 不响应 `MEDIA_CONTROL_FAVORITE`，不伪造收藏状态。
- 不转发原车 extras 到应用内部，避免把未校验的外部输入带进启动流程。
- 不在非兼容包名里响应任何 OEM 指令：组件在普通构建中 `enabled=false`，且判定函数对包名做精确匹配。
- 不对未连接 CarPlay 的进度查询作答——宁可被判离线，也不冒充一个无法播放的在线媒体源。

## 已验证事项

- 兼容包与原车组合下 CarPlay 会话可以建立，音乐播放正常（2026-10-31 实车安装验证）。认证资产来自随包的 Apple 签发证书，`LocalMfiAuthenticationClient.load()` 只校验密钥与证书匹配，不校验包名或签名，因此跨包名复用成立。

## 未验证事项

以下内容尚无实车结论，不应在交付说明中当作已完成：

- 反向控制在真实播放中的按压手感与去重表现（`geely-wheel` 已从 `media_session` 分组中拆出，避免 OEM 与系统媒体键同组导致 120 ms 去重失效）。
- 主屏卡片在兼容包下的封面显示：封面仍走 Android MediaSession，未提供原车私有广播的封面 URL。
