package com.shilapi.xcertplay.geely

import com.shilapi.xcertplay.airplay.AirPlayDisplayConfig
import com.shilapi.xcertplay.airplay.AirPlayViewArea
import kotlin.math.roundToInt

/** The original turning-screen square canvas, restricted to two full-screen areas. */
internal class ReceiverRotationPlan(val display:AirPlayDisplayConfig) {
    var current=display.initialViewArea
    var pending=-1
    fun target(width:Int,height:Int)=if(width>=height) 0 else 1
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
        fun build(base:AirPlayDisplayConfig,windowWidth:Int,windowHeight:Int,side:Int):ReceiverRotationPlan {
            require(side>=2 && windowWidth>0 && windowHeight>0)
            val even=side/2*2
            val (landscape,portrait)=ReceiverTurningCanvas.turningAreas(even,windowWidth,windowHeight,windowWidth,windowHeight)
            return ReceiverRotationPlan(base.copy(widthPixels=even,heightPixels=even,viewArea=null,safeArea=null,
                viewAreas=listOf(AirPlayViewArea(landscape.first.coerceAtLeast(2),landscape.second.coerceAtLeast(2)),AirPlayViewArea(portrait.first.coerceAtLeast(2),portrait.second.coerceAtLeast(2))),
                initialViewArea=if(windowWidth>=windowHeight) 0 else 1))
        }
    }
}
