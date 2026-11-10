package com.shilapi.xcertplay.network

import com.shilapi.xcertplay.airplay.*
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class WirelessHostTest {
    @Test fun wirelessListenerAcceptsTcpAndReleasesPortOnDetach() {
        val address=InetAddress.getByName("127.0.0.1")
        val port=ServerSocket(0,1,address).use { it.localPort }
        val host=WirelessAirPlayHost()
        val accepted=CountDownLatch(1)
        val result=host.attachWireless(address, AirPlayConfig("test","02:00:00:00:00:02","","950.7.1",AirPlayDisplayConfig(1280,720),port=port),AirPlayIdentity.generate(),PairingStore(),null,
            object:AirPlaySessionListener { override fun onTcpAccepted(event:AirPlayTcpAccepted) { accepted.countDown() } },object:AirPlayMediaHandler {},listenerIdentity=AirPlayListenerIdentity(1))
        assertEquals(WirelessAirPlayHost.AttachResult.Started,result)
        assertEquals(port,host.boundPort())
        Socket(address,port).use { assertTrue(accepted.await(3,TimeUnit.SECONDS)) }
        host.detach();assertFalse(host.isAttached())
        ServerSocket(port,1,address).use { assertEquals(port,it.localPort) }
    }
    @Test fun busyPreferredPortFallsBackAndOwnerScopedDetachIsSafe() {
        val address=InetAddress.getByName("127.0.0.1")
        ServerSocket(0,1,address).use { occupied ->
            val host=WirelessAirPlayHost();val owner=AirPlayListenerIdentity(2)
            val result=host.attachWireless(address,AirPlayConfig("test","02:00:00:00:00:02","","950.7.1",AirPlayDisplayConfig(1280,720),port=occupied.localPort),AirPlayIdentity.generate(),PairingStore(),null,object:AirPlaySessionListener {},object:AirPlayMediaHandler {},listenerIdentity=owner)
            assertEquals(WirelessAirPlayHost.AttachResult.Started,result)
            assertNotEquals(occupied.localPort,host.boundPort())
            host.detachWireless(AirPlayListenerIdentity(3));assertTrue(host.isAttached())
            host.detachWireless(owner);assertFalse(host.isAttached())
        }
    }
}
