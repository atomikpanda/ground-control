package com.atomikpanda.groundcontrol.data

import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Recovers a host refresh credential the host has rotated. A host mints a
 * replacement when its credential expires and publishes it only in the relay
 * directory, so a refused exchange first re-reads the directory; the caller then
 * retries with whatever credential the stored snapshot now holds.
 *
 * Single-flight per host: requests that hit the same refusal share one directory
 * read, and a caller whose refused credential was already replaced skips it.
 */
internal class RelayRefreshRecovery(
    private val hosts: HostsRepository,
    private val readDirectory: suspend (RelayAccount) -> ValidatedRelayDirectory,
) {
    private val locks = ConcurrentHashMap<String, Mutex>()

    /** Throws [IOException] when the directory cannot be read or validated. */
    suspend fun recover(hostId: String, rejectedRefresh: String) {
        locks.getOrPut(hostId) { Mutex() }.withLock {
            val snapshot = hosts.routeOwnershipSnapshot()
            val account = snapshot.account ?: return
            val stored = snapshot.hosts.firstOrNull { it.hostId == hostId }?.refresh
            if (stored != rejectedRefresh) return
            val directory = try {
                readDirectory(account)
            } catch (error: CancellationException) {
                throw error
            } catch (_: AuthException) {
                // The relay refused the fleet token itself: the snapshot keeps
                // the refused credential, so the caller asks for a re-pair.
                return
            } catch (error: IOException) {
                throw error
            } catch (error: Exception) {
                throw IOException("relay directory unavailable", error)
            }
            // A concurrent route write stales the snapshot, not the directory:
            // apply the same directory against a fresh one.
            var expectedGeneration = snapshot.generation
            repeat(MAX_DIRECTORY_APPLY_ATTEMPTS) {
                if (hosts.replaceValidatedRelayDirectory(account, directory, expectedGeneration)) return
                val current = hosts.routeOwnershipSnapshot()
                if (current.account != account) return
                if (current.hosts.firstOrNull { it.hostId == hostId }?.refresh != rejectedRefresh) return
                expectedGeneration = current.generation
            }
            throw IOException("relay directory could not be applied")
        }
    }
}

private const val MAX_DIRECTORY_APPLY_ATTEMPTS = 3
