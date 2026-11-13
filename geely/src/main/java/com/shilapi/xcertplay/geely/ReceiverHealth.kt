package com.shilapi.xcertplay.geely

/** Counts only engine observations; unknown measurements remain explicitly unknown. */
internal class ReceiverHealth {
    private var video="视频：等待接收统计"
    private var format="实际解码尺寸：等待解码器报告"
    private val audio=linkedMapOf<String,String>()
    private val underruns=mutableMapOf<String,Long>()
    fun reset() {video="视频：等待接收统计";format="实际解码尺寸：等待解码器报告";audio.clear();underruns.clear()}
    fun observe(line:String):Boolean {
        fun field(name:String)=Regex("(?:^| )"+name+"=([^ ]+)").find(line)?.groupValues?.get(1)
        when {
            line.startsWith("video stats") -> video="视频接收：${field("rx") ?: "未知"} · 解码显示：${field("shown") ?: "未知"} · 码率：${field("kbps") ?: "未知"} kbps"
            line.startsWith("output format requested=") -> format="实际解码尺寸：${field("coded") ?: "未知"}（编码画布）"
            line.startsWith("audio stats ") -> {
                val channel=field("channel") ?: return false
                val delta=field("underruns")?.removePrefix("+")?.toLongOrNull()?.coerceAtLeast(0) ?: 0
                underruns[channel]=(underruns[channel] ?: 0)+delta
                val rate=field("sampleRate")?.toDoubleOrNull()
                val queued=field("estimatedQueuedFrames")?.toDoubleOrNull()
                val ms=if(rate!=null && rate>0 && queued!=null && queued>=0) "${(queued*1000/rate).toInt()} ms" else "未知"
                audio[channel]="$channel：本机待播约 $ms · 欠载累计 ${underruns[channel]} 次"
            }
            else -> return false
        }
        return true
    }
    fun describe()=listOf(format,video).plus(audio.values).joinToString("\n")+"\n统计约每 5 秒更新；静止画面帧率可能降低。欠载不等于可听断音；本机待播量不代表端到端延迟。"
}

internal class ReceiverRecovery {
    var attempts=0;private set
    fun reset() {attempts=0}
    fun nextDelay(transient:Boolean):Long? {
        if(!transient || attempts>=2) return null
        attempts++
        return if(attempts==1) 2000L else 5000L
    }
    companion object {
        fun transient(message:String):Boolean {
            val text=message.lowercase(java.util.Locale.ROOT)
            if(listOf("authentication","certificate","permission","denied","ssid","configuration","paired","bonded","cancel").any {it in text}) return false
            return listOf("network changed","network disconnected","network is not ready","connection reset","broken pipe","connection timed out","socket closed","传输中断").any {it in text}
        }
    }
}
