package com.atomikpanda.groundcontrol

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import com.atomikpanda.groundcontrol.data.AuthException
import com.atomikpanda.groundcontrol.data.HostsRepository
import com.atomikpanda.groundcontrol.data.InvalidRelayDirectoryException
import com.atomikpanda.groundcontrol.data.RelayAccount
import com.atomikpanda.groundcontrol.data.RelayDirectoryTransformer
import com.atomikpanda.groundcontrol.data.RelayRefreshRecovery
import com.atomikpanda.groundcontrol.data.ValidatedRelayDirectory
import com.atomikpanda.groundcontrol.data.dto.HostInfo
import com.atomikpanda.groundcontrol.data.dto.HostsResponse
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Recovering a host refresh credential the host rotated into the relay directory. */
class RelayRefreshRecoveryTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val account = RelayAccount("relay.example.com", "fleet-token")
    private val hostId = "h-1"

    private fun newDataStore(scope: CoroutineScope): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(scope = scope) {
            temporaryFolder.newFile("hosts.preferences_pb").also { check(it.delete()) }
        }

    private fun directory(refresh: String, label: String = ""): ValidatedRelayDirectory =
        RelayDirectoryTransformer().transform(
            HostsResponse(listOf(HostInfo(
                hostId = hostId,
                label = label,
                publicUrl = "https://h-1abc.relay.example.com",
                refresh = refresh,
            ))),
            account.relayDomain,
        )

    private suspend fun pairedRepository(scope: CoroutineScope, refresh: String): HostsRepository {
        val repository = HostsRepository(newDataStore(scope))
        repository.setRelayAccount(account)
        assertTrue(
            repository.replaceValidatedRelayDirectory(
                expectedAccount = account,
                directory = directory(refresh),
                expectedGeneration = repository.routeOwnershipSnapshot().generation,
            )
        )
        return repository
    }

    private suspend fun HostsRepository.storedRefresh(): String? =
        snapshot().single { it.hostId == hostId }.refresh

    @Test fun a_rotated_credential_is_adopted_from_the_directory() = runTest {
        val repository = pairedRepository(backgroundScope, refresh = "rotated-out")
        val reads = AtomicInteger()
        val recovery = RelayRefreshRecovery(repository) {
            reads.incrementAndGet()
            directory(refresh = "current")
        }

        recovery.recover(hostId, "rotated-out")

        assertEquals("current", repository.storedRefresh())
        assertEquals(1, reads.get())
    }

    @Test fun an_already_replaced_credential_skips_the_directory() = runTest {
        val repository = pairedRepository(backgroundScope, refresh = "current")
        val reads = AtomicInteger()
        val recovery = RelayRefreshRecovery(repository) {
            reads.incrementAndGet()
            directory(refresh = "current")
        }

        recovery.recover(hostId, "rotated-out")

        assertEquals(0, reads.get())
        assertEquals("current", repository.storedRefresh())
    }

    @Test fun a_refused_fleet_token_keeps_the_refused_credential() = runTest {
        val repository = pairedRepository(backgroundScope, refresh = "rotated-out")
        val recovery = RelayRefreshRecovery(repository) {
            throw AuthException("fleet token refused")
        }

        recovery.recover(hostId, "rotated-out")

        assertEquals("rotated-out", repository.storedRefresh())
    }

    @Test fun a_malformed_directory_is_a_reachability_failure() = runTest {
        val repository = pairedRepository(backgroundScope, refresh = "rotated-out")
        val recovery = RelayRefreshRecovery(repository) {
            throw InvalidRelayDirectoryException("Relay directory response is malformed")
        }

        val error = runCatching { recovery.recover(hostId, "rotated-out") }.exceptionOrNull()

        assertTrue("$error", error is IOException)
        assertEquals("rotated-out", repository.storedRefresh())
    }

    @Test fun adopting_the_directory_preserves_phone_owned_contact_time() = runTest {
        val repository = pairedRepository(backgroundScope, refresh = "rotated-out")
        repository.recordContact(hostId, "https://h-1abc.relay.example.com")
        val contacted = repository.snapshot().single { it.hostId == hostId }.lastContactAtMillis
        val recovery = RelayRefreshRecovery(repository) { directory(refresh = "current") }

        recovery.recover(hostId, "rotated-out")

        val stored = repository.snapshot().single { it.hostId == hostId }
        assertEquals("current", stored.refresh)
        assertNotNull(contacted)
        assertEquals(contacted, stored.lastContactAtMillis)
    }

    @Test fun concurrent_refusals_share_one_directory_read() = runBlocking {
        // Real dispatchers: every caller must find the first read in flight.
        val storeScope = CoroutineScope(Dispatchers.IO + Job())
        val repository = pairedRepository(storeScope, refresh = "rotated-out")
        val reads = AtomicInteger()
        val release = CompletableDeferred<Unit>()
        val recovery = RelayRefreshRecovery(repository) {
            reads.incrementAndGet()
            release.await()
            directory(refresh = "current")
        }

        withContext(Dispatchers.Default) {
            val callers = List(4) { async { recovery.recover(hostId, "rotated-out") } }
            release.complete(Unit)
            callers.awaitAll()
        }

        assertEquals(1, reads.get())
        assertEquals("current", repository.storedRefresh())
        storeScope.cancel()
    }
}
