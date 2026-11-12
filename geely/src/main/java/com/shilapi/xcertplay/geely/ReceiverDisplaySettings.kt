package com.shilapi.xcertplay.geely

import android.media.MediaFormat
import com.shilapi.xcertplay.airplay.AirPlayDisplayConfig
import com.shilapi.xcertplay.airplay.CarPlayDisplayScale
import com.shilapi.xcertplay.airplay.CarPlayUiScale
import com.shilapi.xcertplay.media.VideoDecoderSupport

/** Negotiation only; the running session and touch coordinates are never rewritten by settings. */
internal object ReceiverDisplaySettings {
    val qualityChoices=listOf(100,125)
    val sizeChoices=listOf(85,100,115)
    fun quality(value:Int)=value.takeIf {it in qualityChoices} ?: 100
    fun size(value:Int)=value.takeIf {it in sizeChoices} ?: 100
    data class Plan(val display:AirPlayDisplayConfig,val quality:Int,val size:Int,val fallback:Boolean)
    fun plan(width:Int,height:Int,fps:Int,quality:Int,size:Int,supports:(AirPlayDisplayConfig)->Boolean=::hardwareSupports):Plan {
        require(width>=2 && height>=2)
        val base=AirPlayDisplayConfig(width/2*2,height/2*2,fps=ReceiverPlaybackSettings.sanitizeFps(fps))
        var actualQuality=quality(quality);var actualSize=size(size)
        fun candidate():AirPlayDisplayConfig {
            val resolution=CarPlayDisplayScale.applyPercent(base,actualQuality)
            val scaled=CarPlayUiScale.apply(resolution,actualSize)
            if(actualSize<100 && scaled===resolution) actualSize=100
            return scaled
        }
        val checked=mutableMapOf<Triple<Int,Int,Int>,Boolean>()
        fun supported(d:AirPlayDisplayConfig):Boolean {
            if(d.widthPixels<=base.widthPixels && d.heightPixels<=base.heightPixels) return true
            return maxOf(d.widthPixels,d.heightPixels)<=3840 && minOf(d.widthPixels,d.heightPixels)<=2160 && checked.getOrPut(Triple(d.widthPixels,d.heightPixels,d.fps)) {supports(d)}
        }
        // Same fallback order as the original host: remove smaller-UI enlargement, then HD.
        var display=candidate()
        if(!supported(display) && actualSize<100) {actualSize=100;display=candidate()}
        if(!supported(display) && actualQuality>100) {actualQuality=100;display=candidate()}
        return Plan(display,actualQuality,actualSize,actualQuality!=quality(quality) || actualSize!=size(size))
    }
    private fun hardwareSupports(display:AirPlayDisplayConfig):Boolean {
        val mime=MediaFormat.MIMETYPE_VIDEO_AVC
        return VideoDecoderSupport.candidates(mime,display.widthPixels,display.heightPixels,display.fps).any {
            VideoDecoderSupport.isHardware(it) && VideoDecoderSupport.supportsRate(it,mime,display.widthPixels,display.heightPixels,display.fps)
        }
    }
}
