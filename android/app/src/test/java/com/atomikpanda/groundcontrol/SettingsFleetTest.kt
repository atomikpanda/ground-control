package com.atomikpanda.groundcontrol

import com.atomikpanda.groundcontrol.data.AuthException
import com.atomikpanda.groundcontrol.data.RelayAccount
import com.atomikpanda.groundcontrol.data.WorkspaceConnection
import com.atomikpanda.groundcontrol.ui.settings.classifyFleetRefreshFailure
import com.atomikpanda.groundcontrol.ui.settings.observeRelayAccountChanges
import com.atomikpanda.groundcontrol.data.unresolvedLegacyConnections
import com.atomikpanda.groundcontrol.ui.settings.visibleSettingsResult
import java.io.IOException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
                hasHostsForAccount = { false },
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
                hasHostsForAccount = { true },
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

    @Test fun an_initial_relay_account_refreshes_when_only_direct_hosts_exist() = runTest {
        val account = RelayAccount("relay.example", "fleet-token")
        val accounts = MutableStateFlow<RelayAccount?>(account)
        var inspected: RelayAccount? = null
        var refreshes = 0
        val collecting = backgroundScope.launch {
            observeRelayAccountChanges(
                accounts = accounts,
                hasHostsForAccount = {
                    inspected = it
                    false
                },
                refreshFleet = { refreshes += 1 },
            )
        }

        runCurrent()

        assertEquals(account, inspected)
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

    @Test fun unresolved_legacy_rows_remain_eligible_after_the_host_is_known() {
        val legacy = WorkspaceConnection(
            id = "manual",
            baseUrl = "http://lan/workspaces/ws-1",
            hostId = "http://lan",
            workspaceId = "ws-1",
        )
        val adopted = legacy.copy(hostId = "host-a")

        assertEquals(listOf(legacy), unresolvedLegacyConnections(listOf(legacy, adopted)))
    }

    @Test fun fleet_status_is_hidden_as_soon_as_its_relay_account_is_replaced() {
        val old = RelayAccount("relay.example", "old-token")
        val replacement = RelayAccount("relay.example", "new-token")

        assertEquals("Fleet: 2 host(s)", visibleSettingsResult("Fleet: 2 host(s)", old, old))
        assertNull(visibleSettingsResult("Fleet: 2 host(s)", old, replacement))
    }
}
