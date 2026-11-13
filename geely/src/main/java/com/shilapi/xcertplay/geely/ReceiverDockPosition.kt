package com.shilapi.xcertplay.geely

import com.shilapi.xcertplay.airplay.AirPlayDisplayConfig
import com.shilapi.xcertplay.airplay.AirPlayInfoPlist
import com.shilapi.xcertplay.airplay.AirPlayViewArea

/** Same bottom/driver-edge declarations as upstream; right uses its rightHandDrive flag. */
internal enum class ReceiverDockPosition(val value:Int,val title:String) {
    AUTO(0,"自动：竖屏底部／横屏左侧"),LEFT(1,"左侧"),BOTTOM(2,"底部"),RIGHT(3,"右侧");
    val rightHandDrive get()=this==RIGHT
    fun edge(portrait:Boolean)=if(this==BOTTOM || this==AUTO && portrait) AirPlayInfoPlist.DOCK_EDGE_BOTTOM else AirPlayInfoPlist.DOCK_EDGE_DRIVER_SIDE
    fun singleDisplay(display:AirPlayDisplayConfig)=display.copy(viewAreas=listOf(AirPlayViewArea(display.widthPixels,display.heightPixels,dockEdge=edge(display.heightPixels>display.widthPixels))),initialViewArea=0)
    companion object {fun from(value:Int)=entries.firstOrNull {it.value==value} ?: AUTO}
}
