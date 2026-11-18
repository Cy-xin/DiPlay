package com.shilapi.xcertplay.geely

import android.os.Looper
import com.geely.lib.oneosapi.input.IInputListener
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[30],manifest=Config.NONE)
class ReceiverWheelKeysTest {
    @Test fun rawAndHoldCallbacksNeverDuplicateMediaOrSiriAndCloseDropsPendingInput() {
        val calls=mutableListOf<Pair<Int,Boolean>>()
        val keys=ReceiverWheelKeys(RuntimeEnvironment.getApplication(),{key,long->calls+=key to long},{})
        val listener=ReceiverWheelKeys::class.java.getDeclaredField("listener").apply {isAccessible=true}.get(keys) as IInputListener
        listener.onKeyCodeEvent(200087,0,0);listener.onKeyCodeEvent(200087,1,0);listener.onShortClick(200087,0)
        listener.onShortClick(200088,0);listener.onShortClick(200085,0)
        listener.onShortClick(200400,0);listener.onHoldingPressStarted(200400,0);listener.onLongPressTriggered(200400,0);listener.onHoldingPressStopped(200400,0)
        listener.onShortClick(200231,0);listener.onLongPressTriggered(200231,0)
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf(4 to false,5 to false,-1 to true),calls)
        listener.onShortClick(200087,0);keys.close();Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertEquals(3,calls.size)
    }
}
