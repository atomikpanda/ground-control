package com.atomikpanda.groundcontrol

import com.atomikpanda.groundcontrol.data.NotificationsSetting
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

private class FakeNotificationsSetting : NotificationsSetting {
    private val state = MutableStateFlow(false)
    override val enabled: StateFlow<Boolean> = state
    override suspend fun set(value: Boolean) { state.value = value }
}

class SettingsNotificationsTest {
    @Test fun toggle_persists_through_the_setting() = runTest {
        val setting = FakeNotificationsSetting()
        assertEquals(false, setting.enabled.first())
        setting.set(true)
        assertEquals(true, setting.enabled.first())
        setting.set(false)
        assertEquals(false, setting.enabled.first())
    }
}
