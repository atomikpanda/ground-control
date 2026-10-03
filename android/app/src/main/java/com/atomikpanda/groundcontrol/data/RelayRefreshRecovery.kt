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
 * A credential that stays refused is re-checked no sooner than a delay doubling
 * from [DIRECTORY_RECHECK_INITIAL_MILLIS] to [DIRECTORY_RECHECK_MAX_MILLIS];
 * inside that window the last outcome repeats without a read.
 */
internal class RelayRefreshRecovery(
    private val hosts: HostsRepository,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val readDirectory: suspend (RelayAccount) -> ValidatedRelayDirectory,
) {
    private data class Recheck(
        val refused: String,
        val retryAtMillis: Long,
        val delayMillis: Long,
        val failure: IOException?,
    )

    private val locks = ConcurrentHashMap<String, Mutex>()
    private val rechecks = ConcurrentHashMap<String, Recheck>()

    /** Throws [IOException] when the directory cannot be read or validated. */
    suspend fun recover(hostId: String, rejectedRefresh: String) {
        locks.getOrPut(hostId) { Mutex() }.withLock {
            val snapshot = hosts.routeOwnershipSnapshot()
            val account = snapshot.account ?: return
            val stored = snapshot.hosts.firstOrNull { it.hostId == hostId }?.refresh
            if (stored != rejectedRefresh) return
            val prior = rechecks[hostId]?.takeIf { it.refused == rejectedRefresh }
            if (prior != null && nowMillis() < prior.retryAtMillis) {
                // An unreadable directory must stay a reachability failure,
                // not turn into a re-pair while the recheck is throttled.
                prior.failure?.let { throw it }
                return
            }
            val delayMillis = prior
                ?.let { minOf(it.delayMillis * 2, DIRECTORY_RECHECK_MAX_MILLIS) }
                ?: DIRECTORY_RECHECK_INITIAL_MILLIS
            fun schedule(failure: IOException?) {
                rechecks[hostId] = Recheck(rejectedRefresh, nowMillis() + delayMillis, delayMillis, failure)
            }
            val directory = try {
                readDirectory(account)
            } catch (error: CancellationException) {
                throw error
            } catch (_: AuthException) {
                // The relay refused the fleet token itself: the snapshot keeps
                // the refused credential, so the caller asks for a re-pair.
                schedule(failure = null)
                return
            } catch (error: IOException) {
                schedule(error)
                throw error
            } catch (error: Exception) {
                val failure = IOException("relay directory unavailable", error)
                schedule(failure)
                throw failure
            }
            schedule(failure = null)
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
            val failure = IOException("relay directory could not be applied")
            schedule(failure)
            throw failure
        }
    }
}

private const val MAX_DIRECTORY_APPLY_ATTEMPTS = 3
internal const val DIRECTORY_RECHECK_INITIAL_MILLIS = 30_000L
internal const val DIRECTORY_RECHECK_MAX_MILLIS = 15 * 60_000L
