package com.local.spacedcards.data.lan

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.util.Collections
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject

class LanDiscovery {
    suspend fun discover(timeoutMs: Long = DEFAULT_TIMEOUT_MS): List<DiscoveredPc> = withContext(Dispatchers.IO) {
        val deadlineMs = System.currentTimeMillis() + timeoutMs.coerceAtLeast(1L)
        val discoveredByHost = linkedMapOf<String, DiscoveredPc>()
        val payload = DISCOVERY_PAYLOAD.toByteArray(Charsets.US_ASCII)
        val targets = buildBroadcastTargets()

        DatagramSocket().use { socket ->
            socket.broadcast = true

            repeat(PROBE_REPETITIONS) { attempt ->
                currentCoroutineContext().ensureActive()
                targets.forEach { target ->
                    socket.send(
                        DatagramPacket(
                            payload,
                            payload.size,
                            target,
                            DISCOVERY_PORT,
                        ),
                    )
                }
                if (attempt < PROBE_REPETITIONS - 1) {
                    delay(PROBE_INTERVAL_MS)
                }
            }

            val buffer = ByteArray(RESPONSE_BUFFER_SIZE)
            while (true) {
                currentCoroutineContext().ensureActive()
                val remainingMs = deadlineMs - System.currentTimeMillis()
                if (remainingMs <= 0L) {
                    break
                }
                socket.soTimeout = remainingMs.coerceAtMost(RECEIVE_POLL_TIMEOUT_MS).toInt()
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(packet)
                } catch (_: SocketTimeoutException) {
                    continue
                }
                parseResponse(packet)?.let { pc ->
                    discoveredByHost.putIfAbsent(pc.host, pc)
                }
            }
        }

        discoveredByHost.values
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER, DiscoveredPc::name).thenBy(DiscoveredPc::host))
    }

    private fun buildBroadcastTargets(): List<InetAddress> {
        val targets = linkedSetOf(InetAddress.getByName(GLOBAL_BROADCAST_ADDRESS))
        val interfaces = NetworkInterface.getNetworkInterfaces() ?: return targets.toList()
        Collections.list(interfaces).forEach { networkInterface ->
            val usable = runCatching {
                networkInterface.isUp && !networkInterface.isLoopback
            }.getOrDefault(false)
            if (!usable) {
                return@forEach
            }
            networkInterface.interfaceAddresses
                .mapNotNull { it.broadcast as? Inet4Address }
                .forEach(targets::add)
        }
        return targets.toList()
    }

    private fun parseResponse(packet: DatagramPacket): DiscoveredPc? {
        val body = packet.data.decodeToString(
            startIndex = packet.offset,
            endIndex = packet.offset + packet.length,
        ).trim()
        if (body.isBlank()) {
            return null
        }

        val json = try {
            JSONObject(body)
        } catch (_: JSONException) {
            return null
        }
        if (json.optString("app", "").trim() != EXPECTED_APP) {
            return null
        }

        val port = json.optInt("port", -1)
        if (port !in 1..65535) {
            return null
        }

        val host = packet.address.hostAddress?.trim().orEmpty()
        if (host.isBlank()) {
            return null
        }

        return DiscoveredPc(
            name = json.optString("name", "").trim().ifBlank { host },
            host = host,
            port = port,
            version = json.optString("version", "").trim().ifBlank { "?" },
        )
    }

    private companion object {
        private const val DEFAULT_TIMEOUT_MS = 2_500L
        private const val DISCOVERY_PORT = 8_766
        private const val PROBE_REPETITIONS = 3
        private const val PROBE_INTERVAL_MS = 300L
        private const val RECEIVE_POLL_TIMEOUT_MS = 250L
        private const val RESPONSE_BUFFER_SIZE = 2_048
        private const val EXPECTED_APP = "mindloop-baker"
        private const val DISCOVERY_PAYLOAD = "MINDLOOP-DISCOVER-v1"
        private const val GLOBAL_BROADCAST_ADDRESS = "255.255.255.255"
    }
}
