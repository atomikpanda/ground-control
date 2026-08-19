package com.atomikpanda.groundcontrol

import com.atomikpanda.groundcontrol.data.AuthException
import com.atomikpanda.groundcontrol.data.RelayAccount
import com.atomikpanda.groundcontrol.ui.settings.classifyFleetRefreshFailure
import com.atomikpanda.groundcontrol.ui.settings.observeRelayAccountChanges
import java.io.IOException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsFleetTest {
    @Test fun an_external_relay_account_update_refreshes_an_existing_settings_screen() = runTest {
        val accounts = MutableStateFlow<RelayAccount?>(null)
        var refreshes = 0
        val collecting = backgroundScope.launch {
            observeRelayAccountChanges(
                accounts = accounts,
                hasHosts = { false },
                refreshFleet = { refreshes += 1 },
            )
        }
        runCurrent()

        val account = RelayAccount("relay.example", "fleet-token")
        accounts.value = account
        runCurrent()
        accounts.value = account
        runCurrent()

        assertEquals(1, refreshes)
        collecting.cancel()
    }

    @Test fun an_existing_fleet_only_refreshes_when_the_account_changes() = runTest {
        val accounts = MutableStateFlow<RelayAccount?>(RelayAccount("old.example", "old"))
        var refreshes = 0
        val collecting = backgroundScope.launch {
            observeRelayAccountChanges(
                accounts = accounts,
                hasHosts = { true },
                refreshFleet = { refreshes += 1 },
            )
        }
        runCurrent()
        assertEquals(0, refreshes)

        accounts.value = RelayAccount("new.example", "new")
        runCurrent()

        assertEquals(1, refreshes)
        collecting.cancel()
    }

    @Test fun fleet_auth_failure_requires_repair_but_transport_failure_is_an_outage() {
        val rejected = classifyFleetRefreshFailure(AuthException("unauthorized"))
        val unreachable = classifyFleetRefreshFailure(IOException("offline"))

        assertTrue(rejected.requiresRePair)
        assertEquals("Re-pair needed — scan the relay account again", rejected.message)
        assertFalse(unreachable.requiresRePair)
        assertEquals("Couldn't reach the relay — showing last known hosts", unreachable.message)
    }
}
