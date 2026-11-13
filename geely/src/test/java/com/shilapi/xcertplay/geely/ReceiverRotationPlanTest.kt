package com.shilapi.xcertplay.geely

import com.shilapi.xcertplay.airplay.AirPlayDisplayConfig
import com.shilapi.xcertplay.airplay.AirPlayInfoPlist
import org.junit.Assert.*
import org.junit.Test

class ReceiverRotationPlanTest {
    @Test fun portraitStartedSessionAdvertisesBothDirectionsOnOneSupportedCanvas() {
        val plan=ReceiverRotationPlan.build(AirPlayDisplayConfig(1350,3000,fps=60),1080,2400,1920)
        assertEquals(1920,plan.display.widthPixels);assertEquals(1920,plan.display.heightPixels)
        assertEquals(1,plan.current)
        assertEquals(1920,plan.display.viewAreas!![0].width);assertEquals(864,plan.display.viewAreas!![0].height)
        assertEquals(864,plan.display.viewAreas!![1].width);assertEquals(1920,plan.display.viewAreas!![1].height)
        val display=(AirPlayInfoPlist.build(receiverAirPlayConfig(com.shilapi.xcertplay.airplay.AirPlayIdentity.generate(),1080,2400).copy(main=plan.display))["displays"] as List<*>).first() as Map<*,*>
        assertEquals(2,(display["viewAreas"] as List<*>).size);assertEquals(1,display["initialViewArea"])
    }
    @Test fun turnChangesVisibleViewportWithoutStretchingSquareOrRenegotiatingStream() {
        val plan=ReceiverRotationPlan.build(AirPlayDisplayConfig(1080,2400),1080,2400,1920)
        val portrait=plan.bounds(1080,2400)
        assertEquals(1080,portrait.viewportWidth);assertEquals(2400,portrait.viewportHeight)
        assertEquals(2400,portrait.surfaceWidth);assertEquals(2400,portrait.surfaceHeight)
        plan.current=plan.target(2400,1080)
        val landscape=plan.bounds(2400,1080)
        assertEquals(2400,landscape.viewportWidth);assertEquals(1080,landscape.viewportHeight)
        assertEquals(2400,landscape.surfaceWidth);assertEquals(2400,landscape.surfaceHeight)
        assertEquals(1920,plan.display.heightPixels)
    }
    @Test fun touchAtVisibleCenterMapsToTheActiveAreaCenterInBothDirections() {
        val plan=ReceiverRotationPlan.build(AirPlayDisplayConfig(1080,2400),1080,2400,1920)
        for((width,height) in listOf(1080 to 2400,2400 to 1080)) {
            plan.current=plan.target(width,height);val b=plan.bounds(width,height);val area=plan.display.viewAreas!![plan.current]
            val x=(b.viewportWidth/2.0-b.surfaceLeft)/b.surfaceWidth*plan.display.widthPixels
            val y=(b.viewportHeight/2.0-b.surfaceTop)/b.surfaceHeight*plan.display.heightPixels
            assertEquals(area.width/2.0,x,1.0);assertEquals(area.height/2.0,y,1.0)
        }
    }
    @Test fun portableScreensEnableRotationButFixedGeelyDoesNotPayForSquareByDefault() {
        assertTrue(ReceiverRotationPlan.enabled(false,false,true,false,false))
        assertFalse(ReceiverRotationPlan.enabled(false,false,true,false,true))
        assertFalse(ReceiverRotationPlan.enabled(false,false,true,true,false))
        assertTrue(ReceiverRotationPlan.enabled(true,false,false,false,true))
        assertTrue(ReceiverRotationPlan.enabled(false,true,false,true,true))
    }
}
