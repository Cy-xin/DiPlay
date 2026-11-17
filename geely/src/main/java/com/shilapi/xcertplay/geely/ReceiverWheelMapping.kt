package com.shilapi.xcertplay.geely

import android.content.SharedPreferences
import com.shilapi.xcertplay.airplay.CarPlayMediaButton

internal enum class ReceiverWheelButton(val key:Int,val title:String,val english:String,val defaultAction:Int) {
    NEXT(200087,"下一首键","Next key",CarPlayMediaButton.NEXT),
    PREVIOUS(200088,"上一首键","Previous key",CarPlayMediaButton.PREVIOUS),
    PLAY_PAUSE(200085,"播放／暂停键","Play/pause key",CarPlayMediaButton.PLAY_PAUSE);
    val preference get()="wheel_action_$key"
}
internal object ReceiverWheelMapping {
    val choices=listOf(CarPlayMediaButton.NEXT,CarPlayMediaButton.PREVIOUS,CarPlayMediaButton.PLAY_PAUSE,0)
    val defaults=ReceiverWheelButton.entries.associate {it.key to it.defaultAction}
    fun action(key:Int,value:Int)=value.takeIf {it in choices} ?: defaults[key] ?: 0
    fun load(prefs:SharedPreferences)=ReceiverWheelButton.entries.associate {it.key to action(it.key,prefs.getInt(it.preference,it.defaultAction))}
    fun title(action:Int)=when(action) {4->"下一首";5->"上一首";3->"播放／暂停";else->"不执行 CarPlay 操作"}
    fun english(action:Int)=when(action) {4->"Next track";5->"Previous track";3->"Play/pause";else->"No CarPlay action"}
}
