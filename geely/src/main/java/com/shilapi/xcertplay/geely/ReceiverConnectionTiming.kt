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
