package com.shilapi.xcertplay.geely

import com.shilapi.xcertplay.airplay.AirPlayDisplayConfig
import com.shilapi.xcertplay.airplay.AirPlayViewArea
import kotlin.math.roundToInt

/** The original turning-screen square canvas, restricted to two full-screen areas. */
internal class ReceiverRotationPlan(val display:AirPlayDisplayConfig,val dock:ReceiverDockPosition=ReceiverDockPosition.AUTO) {
    var current=display.initialViewArea
    var pending=-1
    fun target(width:Int,height:Int):Int {
        val portrait=height>width
        return (if(portrait) 2 else 0)+(if(dock.edge(portrait)==com.shilapi.xcertplay.airplay.AirPlayInfoPlist.DOCK_EDGE_BOTTOM) 1 else 0)
    }
    data class Bounds(val viewportWidth:Int,val viewportHeight:Int,val surfaceWidth:Int,val surfaceHeight:Int,val surfaceLeft:Int,val surfaceTop:Int)
    fun bounds(width:Int,height:Int):Bounds {
        val area=display.viewAreas!![current]
        val scale=minOf(width.toFloat()/area.width,height.toFloat()/area.height)
        return Bounds((area.width*scale).roundToInt().coerceAtLeast(1),(area.height*scale).roundToInt().coerceAtLeast(1),
            (display.widthPixels*scale).roundToInt().coerceAtLeast(1),(display.heightPixels*scale).roundToInt().coerceAtLeast(1),
            (-area.originX*scale).roundToInt(),(-area.originY*scale).roundToInt())
    }
    companion object {
        fun enabled(portrait:Boolean,observedTurn:Boolean,accelerometer:Boolean,automotive:Boolean,fixedGeely:Boolean)=
            portrait || observedTurn || (accelerometer && !automotive && !fixedGeely)
        fun build(base:AirPlayDisplayConfig,windowWidth:Int,windowHeight:Int,side:Int,dock:ReceiverDockPosition=ReceiverDockPosition.AUTO):ReceiverRotationPlan {
            require(side>=2 && windowWidth>0 && windowHeight>0)
            val even=side/2*2
            val (landscape,portrait)=ReceiverTurningCanvas.turningAreas(even,windowWidth,windowHeight,windowWidth,windowHeight)
            val areas=listOf(landscape,portrait).flatMap {(width,height) ->
                listOf(com.shilapi.xcertplay.airplay.AirPlayInfoPlist.DOCK_EDGE_DRIVER_SIDE,com.shilapi.xcertplay.airplay.AirPlayInfoPlist.DOCK_EDGE_BOTTOM).map {edge ->
                    AirPlayViewArea(width.coerceAtLeast(2),height.coerceAtLeast(2),dockEdge=edge)
                }
            }
            val isPortrait=windowHeight>windowWidth
            val initial=(if(isPortrait) 2 else 0)+(if(dock.edge(isPortrait)==com.shilapi.xcertplay.airplay.AirPlayInfoPlist.DOCK_EDGE_BOTTOM) 1 else 0)
            return ReceiverRotationPlan(base.copy(widthPixels=even,heightPixels=even,viewArea=null,safeArea=null,
                viewAreas=areas,initialViewArea=initial),dock)
        }
    }
}
