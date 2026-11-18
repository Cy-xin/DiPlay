package com.shilapi.xcertplay.geely

import org.junit.Assert.*
import org.junit.Test

class ReceiverSettingsCatalogTest {
    @Test fun everySettingHasOnePrimaryGroupExceptTheExistingDiagnosticsDestination() {
        for(item in ReceiverSetting.entries) {
            val groups=ReceiverSettingsGroup.entries.filter {item.visible("",it)}
            assertEquals(if(item==ReceiverSetting.DIAGNOSTICS) emptyList<ReceiverSettingsGroup>() else listOf(item.group),groups)
        }
        assertTrue(ReceiverSetting.AUDIO_ROUTE.visible("navigation",ReceiverSettingsGroup.CONNECTION))
        assertTrue(ReceiverSetting.WHEEL.visible("实体按键",ReceiverSettingsGroup.CONNECTION))
        assertTrue(ReceiverSetting.DIAGNOSTICS.visible("upload",ReceiverSettingsGroup.APPEARANCE))
        assertTrue(ReceiverSetting.BUFFER.visible("音频缓冲",ReceiverSettingsGroup.CONNECTION))
        assertTrue(ReceiverSetting.DOCK.visible("CarPlay 菜单栏位置",ReceiverSettingsGroup.CONNECTION))
        assertTrue(ReceiverSetting.THEME.visible("跟随系统",ReceiverSettingsGroup.WHEEL))
        assertTrue(ReceiverSetting.AUDIO_ROUTE.visible("音量",ReceiverSettingsGroup.APPEARANCE))
        assertFalse(ReceiverSetting.THEME.visible("no-such-setting",ReceiverSettingsGroup.APPEARANCE))
    }
    @Test fun searchBackKeepsTheSameCategoryAndRunningSessionAndDoesNotPersistExpansion() {
        val ui=ReceiverUiState();ui.settingsOpen=true;ui.settingsGroup=ReceiverSettingsGroup.APPEARANCE
        ui.connected=true;ui.busy=true;ui.settingsQuery="导航"
        assertTrue(ui.dismissTopLayer());assertEquals("",ui.settingsQuery);assertTrue(ui.settingsOpen)
        assertEquals(ReceiverSettingsGroup.APPEARANCE,ui.settingsGroup);assertTrue(ui.connected);assertTrue(ui.busy)
        assertFalse(ui.audioRoutingExpanded);assertFalse(ui.wheelTestsExpanded)
    }
}
