# DiPlay Geely 0.2.2：系统栏隐藏开关

基于0.2.1 ed49cb0f，本地分支codex/geely-compose-coolblue。版本0.2.2 / 107，包名com.shihab.diplay。

## 使用

断开CarPlay后，打开 设置 → 外观 → 隐藏状态栏和侧边栏。默认关闭，修改立即保存并在本应用内生效；也可搜索“状态栏”“侧边栏”“全屏”。开启后从屏幕边缘滑动可临时呼出系统栏。关闭开关或离开应用会请求恢复系统栏；再次回到本应用时重新应用已保存选择。

连接过程中禁用修改，避免影响协商好的画面尺寸和触摸坐标。保留CarPlay断开入口及系统返回，不添加后台轮询、额外权限或服务。

## 实现与边界

复用原版 vehicle-probe/VehicleSystemBars.kt 中的LEGACY fullscreen / hide-navigation / immersive-sticky标志，并调用API30的WindowInsetsController隐藏statusBars/navigationBars。没有引入vehicle-probe模块或车辆数据依赖。保留当前edge-to-edge视频层，Compose根据系统实际可见insets布局。

只在Activity启动、重新获得焦点和用户切换时应用；离开前台恢复本窗口的系统栏请求。恢复不清除用户偏好。主题的图标明暗设置独立保留。

这是系统沉浸式请求，不是停用或操控原车应用：若吉利侧栏由独立OEM覆盖层绘制，或固件拒绝隐藏，普通应用可能无法将其隐藏。开关保存成功不能当作吉利侧栏已实际消失；必须在KX11_A2实测。本版本不使用Root、系统签名、ADB授权或系统文件改动来绕过限制。

## 验证

新增生命周期回归：默认关闭、窗口焦点后设置隐藏标志、离开前台清除隐藏标志、保存后重开恢复选择。完整项目检查与APK精简/签名检查随交付记录提供。模拟器和Redmi不代替吉利侧栏实测。

来源：原版 VehicleSystemBars.kt、CarPlayHostActivity.applySystemBars（本地保留源码）。Android系统栏API参考：https://developer.android.com/develop/ui/views/layout/immersive
