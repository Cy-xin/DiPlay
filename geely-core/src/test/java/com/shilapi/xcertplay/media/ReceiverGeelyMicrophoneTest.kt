package com.shilapi.xcertplay.media

import android.Manifest
import android.media.AudioRecord
import android.media.MediaRecorder
import com.shilapi.xcertplay.airplay.MicrophoneConfig
import com.shilapi.xcertplay.network.GeelyHeadUnit
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAudioRecord
import org.robolectric.shadows.ShadowBuild
import java.net.*
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[30],manifest=Config.NONE)
class ReceiverGeelyMicrophoneTest {
    private var uplink:MicrophoneUplink?=null
    @After fun cleanup() {uplink?.close();ShadowAudioRecord.clearSource()}
    @Test fun kx11UsesExistingGeelySourceAndCanSendCapturedFrames() {
        ShadowBuild.setModel("KX11_A2");ShadowBuild.setManufacturer("QTI")
        assertTrue("KX11 must not fall through to ordinary-phone microphone routing",GeelyHeadUnit.detected)
        assertSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
    }
    @Test fun redmiKeepsOrdinarySpeechRecognitionRouting() {
        ShadowBuild.setModel("M2012K11AC");ShadowBuild.setManufacturer("Xiaomi")
        assertFalse(GeelyHeadUnit.detected)
        assertSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
    }
    private fun assertSource(expected:Int) {
        Shadows.shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.RECORD_AUDIO)
        val source=AtomicInteger(-1)
        ShadowAudioRecord.setSourceProvider {record ->
            source.set(record.audioSource)
            object:ShadowAudioRecord.AudioRecordSource {
                override fun readInByteArray(buffer:ByteArray,offset:Int,size:Int,blocking:Boolean):Int {
                    Thread.sleep(5);java.util.Arrays.fill(buffer,offset,offset+size,1.toByte());return size
                }
            }
        }
        DatagramSocket(0,InetAddress.getLoopbackAddress()).use {receiver ->
            receiver.soTimeout=3000
            val config=MicrophoneConfig(audioType="speechrecognition",sampleRate=16000,channels=1,payloadType=100,frameMillis=20,host=InetAddress.getLoopbackAddress(),port=receiver.localPort,key=ByteArray(32))
            val next=MicrophoneUplink(config);uplink=next
            assertTrue(next.start())
            val packet=DatagramPacket(ByteArray(4096),4096);receiver.receive(packet)
            assertEquals(expected,source.get());assertTrue(packet.length>12)
            next.close();uplink=null
        }
    }
}
