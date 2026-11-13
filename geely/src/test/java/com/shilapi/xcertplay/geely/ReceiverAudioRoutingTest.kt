package com.shilapi.xcertplay.geely

import com.shilapi.xcertplay.media.AudioOutputDevice
import org.junit.Assert.*
import org.junit.Test

class ReceiverAudioRoutingTest {
    @Test fun geelyDefaultsMatchCapturedBusesAndPhoneKeepsSystemPolicy() {
        val routes=ReceiverAudioRouting.routes(emptyMap(),"KX11_A2",emptyList())
        assertEquals("bus0_media_out",routes.media!!.address)
        assertEquals("bus1_navigation_out",routes.navigation!!.address)
        assertEquals("bus4_call_out",routes.phone!!.address)
        assertEquals("bus2_voice_command_out",routes.assistant!!.address)
        assertEquals("bus3_call_ring_out",routes.ringtone!!.address)
        val phone=ReceiverAudioRouting.routes(emptyMap(),"Redmi K40",emptyList())
        assertNull(phone.media);assertNull(phone.navigation);assertNull(phone.phone);assertNull(phone.assistant)
    }
    @Test fun manualMappingIsIndependentAndUsesFreshRuntimeDeviceId() {
        val first=AudioOutputDevice(17,21,"bus1_navigation_out","navigation")
        val second=first.copy(id=95)
        val choices=mapOf(ReceiverAudioRole.MEDIA to first.address,ReceiverAudioRole.PHONE to ReceiverAudioRouting.SYSTEM)
        assertEquals(17,ReceiverAudioRouting.routes(choices,"KX11_A2",listOf(first)).media!!.id)
        val usages=ReceiverAudioRouting.usages(choices)
        assertEquals(12,usages[1]);assertFalse(usages.containsKey(2));assertFalse(usages.containsKey(16))
        val routes=ReceiverAudioRouting.routes(choices,"KX11_A2",listOf(second))
        assertEquals(95,routes.media!!.id);assertEquals(first.address,routes.navigation!!.address)
        assertNull(routes.phone);assertEquals("bus2_voice_command_out",routes.assistant!!.address)
    }
    @Test fun missingOrAmbiguousOutputsDoNotChooseAnUnrelatedSpeaker() {
        val speaker=AudioOutputDevice(1,2,"","speaker")
        val output=ReceiverAudioRouting.routes(emptyMap(),"KX11_A2",listOf(speaker)).media!!
        assertEquals(-1,output.id);assertEquals("bus0_media_out",output.address)
        val duplicate=listOf(output.copy(id=2),output.copy(id=3))
        assertEquals(-1,ReceiverAudioRouting.routes(emptyMap(),"KX11_A2",duplicate).media!!.id)
        assertEquals(ReceiverAudioRouting.AUTO,ReceiverAudioRouting.sanitize("bus8_ecall_ring_out"))
        assertEquals(ReceiverAudioRouting.AUTO,ReceiverAudioRouting.sanitize("17"))
    }
}
