package com.shilapi.xcertplay.geely

import org.json.JSONArray
import org.json.JSONObject

/** Monotonic measurements; callbacks advance phases without a timer or polling loop. */
internal class ReceiverConnectionTiming(private val clock:()->Long) {
    enum class Stage(val title:String) { PREPARE("本地准备"),AUTH("认证准备"),NETWORK("网络就绪"),SELECT_PHONE("选择手机"),DISCOVERY("发现服务"),BLUETOOTH("蓝牙交接"),WAIT_PHONE("等待手机"),HANDSHAKE("协议握手"),VIDEO("等待首帧") }
    private var started=0L
    private var phaseStarted=0L
    private var phase=Stage.PREPARE
    private val completed=mutableListOf<Pair<Stage,Long>>()
    var running=false;private set
    fun start() {started=clock();phaseStarted=started;phase=Stage.PREPARE;completed.clear();running=true}
    fun advance(next:Stage):Boolean {
        if(!running || next==phase) return false
        val now=clock();completed+=phase to (now-phaseStarted).coerceAtLeast(0);phase=next;phaseStarted=now;return true
    }
    fun finish(result:String):String? {
        if(!running) return null
        val now=clock();completed+=phase to (now-phaseStarted).coerceAtLeast(0);running=false
        return JSONObject().put("result",result).put("totalMs",(now-started).coerceAtLeast(0))
            .put("stages",JSONArray().apply {completed.forEach {(stage,ms)->put(JSONObject().put("stage",stage.name).put("ms",ms))}}).toString()
    }
    fun checkReport(failed:Boolean=false):String = buildString {
        append(if(failed) "中断阶段：" else if(running) "当前检查：" else "最近检查阶段：")
        append(phase.title)
        append("\n已完成："+completed.map {it.first.title}.distinct().joinToString(" → ").ifEmpty {"暂无"})
        append("\n"+when(phase) {
            Stage.PREPARE -> "检查网络名称、密码、接口格式和必要权限。"
            Stage.AUTH -> "检查本地认证资产是否完整；不要上传密钥。"
            Stage.NETWORK -> "手动开启热点，核对接口；局域网模式需连接同一可互通 Wi-Fi。"
            Stage.SELECT_PHONE,Stage.BLUETOOTH -> "保持蓝牙开启并完成 iPhone 配对；普通配对不代表 CarPlay 握手成功。"
            Stage.DISCOVERY,Stage.WAIT_PHONE -> "确认 iPhone 已加入网络，关闭客户端隔离并允许组播，在 iPhone 上允许 CarPlay。"
            Stage.HANDSHAKE -> "检查 iPhone 的 CarPlay 授权；失败详情可上传日志排查。"
            Stage.VIDEO -> "已建立会话，等待实际解码画面；可上传日志检查视频协商。"
        })
    }
    fun elapsedMs()=if(running) (clock()-started).coerceAtLeast(0) else 0L
    companion object {
        fun describe(raw:String):String=runCatching {
            val report=JSONObject(raw);val status=when(report.getString("result")) {"success"->"成功";"cancelled"->"已取消";else->"未成功"}
            buildString {
                append("最近连接：$status · ${seconds(report.getLong("totalMs"))} 秒")
                val stages=report.getJSONArray("stages")
                for(i in 0 until stages.length()) {val row=stages.getJSONObject(i);val stage=Stage.valueOf(row.getString("stage"));append("\n${stage.title}：${seconds(row.getLong("ms"))} 秒")}
            }
        }.getOrDefault("")
        private fun seconds(ms:Long)=String.format(java.util.Locale.ROOT,"%.1f",ms/1000.0)
    }
}
