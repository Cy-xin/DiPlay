package com.shilapi.xcertplay.geely

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.json.JSONObject
import java.util.zip.GZIPInputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[30])
class ReceiverDiagnosticsTest {
    @Test fun redactsKnownCredentialsQuotedJsonBearerAddressesAndBlobs() {
        val values=setOf("wifi-secret","Personal iPhone","Home-SSID")
        val raw="password=wifi-secret ssid=Home-SSID phone=Personal iPhone mac=AA:BB:CC:DD:EE:FF ip=192.168.5.29 Authorization: Bearer upload-token {\"password\":\"quoted secret\"} "+"A".repeat(200)
        val safe=ReceiverDiagnostics.redactValue(raw,values)
        for(secret in listOf("wifi-secret","Personal iPhone","Home-SSID","AA:BB:CC:DD:EE:FF","192.168.5.29","upload-token","quoted secret","A".repeat(100))) assertFalse(secret,safe.contains(secret))
    }
    @Test fun snapshotContainsOrderedCorrelatedEventsAndSurvivesRetryWithoutCredentials() {
        val recorder=ReceiverDiagnostics.get(RuntimeEnvironment.getApplication())
        recorder.protect("do-not-upload")
        recorder.attempt="test-attempt"
        recorder.event("operation","connect_pressed")
        recorder.event("protocol","stage","password=do-not-upload")
        recorder.crash(IllegalStateException("do-not-upload"))
        val bundle=recorder.snapshot("audio failed do-not-upload")
        val raw=GZIPInputStream(bundle.inputStream()).bufferedReader().use {it.readText()}
        assertFalse(raw.contains("do-not-upload"))
        val json=JSONObject(raw)
        assertEquals(1,json.getInt("schemaVersion"));assertEquals(recorder.installation,json.getString("installationId"))
        val events=json.getJSONArray("events")
        assertTrue((0 until events.length()).any {events.getJSONObject(it).getString("event")=="connect_pressed"})
        assertTrue((0 until events.length()).any {events.getJSONObject(it).getString("event")=="uncaught_exception"})
        assertEquals(bundle,recorder.pending());assertTrue(bundle.exists())
        System.getenv("DIPLAY_DIAGNOSTIC_TEST_FIXTURE")?.let {java.io.File(it).writeBytes(bundle.readBytes())}
    }
    @Test fun boundedJournalRollsFilesAndKeepsRecentActions() {
        val recorder=ReceiverDiagnostics.get(RuntimeEnvironment.getApplication())
        // Batches avoid overflowing the intentionally bounded asynchronous write queue.
        repeat(10) {batch ->repeat(300) {recorder.event("test","event_$batch","x".repeat(3900))};recorder.flush()}
        recorder.event("operation","latest_action");recorder.flush()
        val directory=java.io.File(RuntimeEnvironment.getApplication().noBackupFilesDir,"diagnostics")
        assertTrue(directory.listFiles {f->f.extension=="jsonl"}!!.size<=8)
        assertTrue(recorder.recent().contains("latest_action"))
    }
}
