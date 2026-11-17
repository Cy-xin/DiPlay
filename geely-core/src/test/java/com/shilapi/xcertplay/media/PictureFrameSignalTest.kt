package com.shilapi.xcertplay.media

import org.junit.Assert.*
import org.junit.Test

class PictureFrameSignalTest {
    @Test fun onlyTheExpectedSurfaceCanCompleteAWatchAndOnlyOnce() {
        val signal=PictureFrameSignal();val surface=Any();val other=Any();var delivered=0
        signal.onFrame(surface);val baseline=signal.sequenceOn(surface)
        signal.observe(surface,baseline) {delivered++}
        signal.onFrame(other);assertEquals(0,delivered);assertEquals(0L,signal.sequenceOn(surface))
        signal.onFrame(surface);signal.onFrame(surface)
        assertEquals(1,delivered);assertTrue(signal.sequenceOn(surface)>baseline)
    }
    @Test fun registrationCatchesTheFrameBetweenSnapshotAndWatch() {
        val signal=PictureFrameSignal();val surface=Any();val before=signal.sequenceOn(surface)
        signal.onFrame(surface);var delivered=0
        signal.observe(surface,before) {delivered++}
        assertEquals(1,delivered)
    }
    @Test fun detachAndCloseCancelWhileOldObserverCloseCannotRemoveItsReplacement() {
        val signal=PictureFrameSignal();val surface=Any();var old=0;var new=0
        val first=signal.observe(surface,0) {old++}
        signal.observe(surface,0) {new++};first.close();signal.onFrame(surface)
        assertEquals(0,old);assertEquals(1,new)
        val sequence=signal.sequenceOn(surface)
        signal.observe(surface,sequence) {new++};signal.detach(surface)
        assertEquals(0L,signal.sequenceOn(surface));signal.onFrame(surface)
        assertEquals(1,new);assertTrue(signal.sequenceOn(surface)>sequence)
        signal.observe(surface,signal.sequenceOn(surface)) {new++};signal.close();signal.onFrame(surface)
        assertEquals(1,new)
    }
}
