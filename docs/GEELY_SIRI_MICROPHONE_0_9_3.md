# 0.9.3：KX11_A2 Siri 麦克风路由修正

基线32e66e34（0.9.2），继续使用codex/geely-return-picture；包名com.shihab.diplay，版本码135。

## 证据与遗漏纠正

用户反馈：MODE长按能唤起Siri，但说话无响应；“嘿Siri”唤醒后能正常响应。
0.9.2实车报告中20:54:20、30、38、42、49共5次麦克风启动均选VOICE_RECOGNITION，随后captureBytes=0、encodedFrames=0、udpSent=0，read阻塞约3.1–6.4秒。Siri请求成功、手机出现语音流不代表车机麦克风实际上传了声音。上一轮分析遗漏了这个重要异常。

原版已有GeelyHeadUnit.detected分支：吉利Siri使用VOICE_COMMUNICATION。最小版保留了此分支，但检测条件只有旧CS11_A4/geely_fx11及Geely/Lynk厂商字符串；本车型号KX11_A2、厂商QTI没有被识别，因而走普通手机VOICE_RECOGNITION路径。

## 最小改动

仅为GeelyHeadUnit检测增加准确型号KX11_A2，使已有Siri/电话麦克风适配分支能够生效。复用已有VOICE_COMMUNICATION处理，不改Siri协议命令、不强行设置全局音频模式、不增加权限或服务、不停用原车助手或HiCar，也不改变Redmi等普通手机路径。电话麦克风也会遵循原版吉利分支，需下一轮实车验证通话收音。

“嘿Siri”可能使用iPhone自身的麦克风，但目前没有直接录音路径证据，不把它写成确定结论。

## 检查与实车边界

新增测试先在旧代码上复现：KX11识别断言失败、Redmi测试通过；修正后检查实际MicrophoneUplink选择的AudioRecord来源，并用合成PCM在本机回环端口验证可产生上行包。没有记录真实语音。完整检查及包校验结果在交付目录。

离线测试只能证明分支选择和应用上行流程。下次停车安装后，长按MODE等待Siri监听，再说一句指令并上传日志；应核查Microphone启动为VOICE_COMMUNICATION、captureBytes/encodedFrames/udpSent持续大于0，以及用户确认Siri能识别。若依旧全0，需进一步检查本车HAL录音路由或原车麦克风占用，不能称已彻底修复。
