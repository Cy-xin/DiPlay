package com.shilapi.xcertplay

import android.content.ComponentName
import android.content.Intent
import android.content.pm.ActivityInfo
import com.shilapi.xcertplay.host.R
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Standard Android eligibility, not a claim about the OEM's frequent-app ranking policy. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class GeelyLauncherEligibilityTest {
    @Test fun launcherCanResolveAnEnabledExportedMainEntry() {
        val context = RuntimeEnvironment.getApplication()
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            .setPackage(context.packageName)
        val matches = context.packageManager.queryIntentActivities(intent, 0)
        val entry = matches.single { it.activityInfo.name == DiPlayActivity::class.java.name }.activityInfo
        assertTrue(entry.enabled)
        assertTrue(entry.exported)
        assertEquals(0, entry.flags and ActivityInfo.FLAG_EXCLUDE_FROM_RECENTS)
        assertEquals(DiPlayActivity::class.java.name,
            context.packageManager.getLaunchIntentForPackage(context.packageName)?.component?.className)
    }

    @Test fun playbackTaskRemainsVisibleWithoutChangingTheExistingTaskBoundary() {
        val context = RuntimeEnvironment.getApplication()
        val playback = context.packageManager.getActivityInfo(
            ComponentName(context, CarPlayHostActivity::class.java), 0)
        assertTrue(playback.enabled)
        assertTrue(playback.exported)
        assertEquals(0, playback.flags and ActivityInfo.FLAG_EXCLUDE_FROM_RECENTS)
        assertEquals("${context.packageName}.carplay", playback.taskAffinity)
    }

    @Test fun mobileEntryKeepsItsRecognizableApplicationLabelAndIcon() {
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        val application = factory.newDocumentBuilder()
            .parse(File("../mobile/src/main/AndroidManifest.xml"))
            .getElementsByTagName("application").item(0)
        val android = "http://schemas.android.com/apk/res/android"
        assertEquals("@string/app_name", application.attributes.getNamedItemNS(android, "label").nodeValue)
        assertEquals("@drawable/ic_carplay", application.attributes.getNamedItemNS(android, "icon").nodeValue)
        assertEquals("DiPlay", RuntimeEnvironment.getApplication().getString(R.string.app_name))
    }
}
