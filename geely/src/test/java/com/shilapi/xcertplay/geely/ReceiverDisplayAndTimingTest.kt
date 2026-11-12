package com.shilapi.xcertplay.geely

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.json.JSONObject

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[30],manifest=Config.NONE)
class ReceiverDisplayAndTimingTest {
    @Test fun defaultMatchesNativeWithoutRequiringAnEnlargementDecoder() {
        val plan=ReceiverDisplaySettings.plan(1281,721,60,100,100) {error("Default must preserve the existing path")}
        assertEquals(1280,plan.display.widthPixels);assertEquals(720,plan.display.heightPixels)
        assertEquals(60,plan.display.fps);assertFalse(plan.fallback)
    }
    @Test fun supportedHdAndSmallerUiUseUpstreamEvenCanvasWithSameAspect() {
        var checks=0
        val plan=ReceiverDisplaySettings.plan(1280,720,30,125,85) {checks++;true}
        assertEquals(1,checks)
        assertEquals(1882,plan.display.widthPixels);assertEquals(1058,plan.display.heightPixels)
        assertEquals(0,plan.display.widthPixels%2);assertFalse(plan.fallback)
        assertEquals(125,plan.quality);assertEquals(85,plan.size)
    }
    @Test fun unsupportedEnlargementFallsBackWithoutChangingTouchSurfaceOrSavedChoice() {
        val plan=ReceiverDisplaySettings.plan(1920,720,60,125,85) {false}
        assertEquals(1920,plan.display.widthPixels);assertEquals(720,plan.display.heightPixels)
        assertEquals(100,plan.quality);assertEquals(100,plan.size);assertTrue(plan.fallback)
    }
    @Test fun exceeding4kCannotSilentlyClaimTheSmallerUiWasApplied() {
        val plan=ReceiverDisplaySettings.plan(3840,2160,30,100,85) {true}
        assertEquals(3840,plan.display.widthPixels);assertEquals(100,plan.size);assertTrue(plan.fallback)
    }
    @Test fun invalidStoredPreferencesKeepSafeDefaultsAndNeverEnable120Fps() {
        val plan=ReceiverDisplaySettings.plan(1280,720,120,999,1) {true}
        assertEquals(30,plan.display.fps);assertEquals(1280,plan.display.widthPixels)
        assertEquals(100,plan.quality);assertEquals(100,plan.size)
    }
    @Test fun repeatedCallbacksDoNotRestartAStageAndPhasesSumToTotal() {
        var now=100L;val timing=ReceiverConnectionTiming {now};timing.start()
        now=250;assertTrue(timing.advance(ReceiverConnectionTiming.Stage.BLUETOOTH))
        now=300;assertFalse(timing.advance(ReceiverConnectionTiming.Stage.BLUETOOTH))
        now=750;assertTrue(timing.advance(ReceiverConnectionTiming.Stage.VIDEO))
        now=1000;val report=JSONObject(timing.finish("success")!!)
        assertEquals(900L,report.getLong("totalMs"));val stages=report.getJSONArray("stages")
        assertEquals(3,stages.length());assertEquals(500L,stages.getJSONObject(1).getLong("ms"))
        assertEquals(report.getLong("totalMs"),(0 until stages.length()).sumOf {stages.getJSONObject(it).getLong("ms")})
        assertNull(timing.finish("failed"));assertFalse(timing.running)
    }
    @Test fun cancellationAndFreshAttemptDoNotReuseOldElapsedTimeOrBreakMalformedReport() {
        var now=100L;val timing=ReceiverConnectionTiming {now};assertNull(timing.finish("failed"))
        timing.start();now=900;assertEquals("cancelled",JSONObject(timing.finish("cancelled")!!).getString("result"))
        now=2000;timing.start();now=2100
        val report=timing.finish("failed")!!;assertEquals(100L,JSONObject(report).getLong("totalMs"))
        assertTrue(ReceiverConnectionTiming.describe(report).contains("未成功"))
        assertEquals("",ReceiverConnectionTiming.describe("broken"))
    }
}
