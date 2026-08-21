package com.atomikpanda.groundcontrol.data

import com.atomikpanda.groundcontrol.data.dto.HostsResponse

/** The relay's directory was structurally readable but semantically unusable. */
class InvalidRelayDirectoryException(message: String) : IllegalArgumentException(message)

/** A complete directory which only [RelayDirectoryTransformer] may construct. */
internal data class ValidatedRelayDirectory internal constructor(
    val hosts: List<HostConnection>,
)

/**
 * Canonicalizes and validates the complete relay response before its data can
 * cross into the persistent fleet. A candidate route is canonicalized exactly
 * once here; later ownership and persistence paths consume that result verbatim.
 */
internal class RelayDirectoryTransformer(
    private val canonicalize: (String) -> String? = ::normalizedBaseUrl,
) {
    fun transform(response: HostsResponse, relayDomain: String): ValidatedRelayDirectory {
        val entries = response.hosts
            ?: throw InvalidRelayDirectoryException("Relay directory hosts are missing")
        val identities = mutableSetOf<String>()
        val routes = mutableSetOf<String>()
        val hosts = entries.map { info ->
            val pending = isSupportedPendingHostState(info.state)
            val identity = info.hostId?.takeIf(String::isNotBlank)
                ?: info.requestId?.takeIf { pending && it.isNotBlank() }?.let { "pending:$it" }
                ?: throw InvalidRelayDirectoryException("Relay host identity is missing")
            if (!identities.add(identity)) {
                throw InvalidRelayDirectoryException("Relay host identity is duplicated")
            }
            if (info.publicUrl != info.publicUrl.trim()) {
                throw InvalidRelayDirectoryException("Relay host route contains padding")
            }
            val route = canonicalize(info.publicUrl)
                ?: throw InvalidRelayDirectoryException("Relay host route is unusable")
            if (!routes.add(route)) {
                throw InvalidRelayDirectoryException("Relay host route identity is duplicated")
            }
            HostConnection(
                hostId = identity,
                label = info.label,
                subdomain = info.subdomain,
                publicUrl = route,
                state = info.state,
                refresh = info.refresh,
                relayDomain = relayDomain,
                lastSeen = info.lastSeen,
                runnerState = info.runner?.state,
                requestId = info.requestId,
            )
        }
        return ValidatedRelayDirectory(hosts)
    }
}
