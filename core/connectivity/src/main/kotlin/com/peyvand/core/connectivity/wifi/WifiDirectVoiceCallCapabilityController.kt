package com.peyvand.core.connectivity.wifi

import com.peyvand.core.calling.VoiceCallCapabilityCodec
import com.peyvand.core.calling.VoiceCallCapabilityMessageType
import com.peyvand.core.calling.VoiceCallCapabilityPacket
import com.peyvand.core.domain.chat.LocalPeerIdentity
import com.peyvand.core.domain.chat.TrustedPeerIdentity
import com.peyvand.core.domain.connection.P2pIpv4InterfaceAddress
import com.peyvand.core.domain.connection.P2pTcpEndpointPolicy
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.SocketException
import java.security.SecureRandom
import java.util.LinkedHashMap
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * Backwards-compatible voice capability discovery over a UDP socket bound to the exact
 * Wi-Fi Direct interface. Unknown peers can ignore these datagrams; no CALL_* TLS frame
 * is sent until a valid signed response is received.
 */
internal class WifiDirectVoiceCallCapabilityController(
    private val sessionId: UUID,
    private val localIdentity: LocalPeerIdentity,
    private val remoteIdentity: TrustedPeerIdentity,
    private val localAddress: Inet4Address,
    private val remoteAddress: Inet4Address,
    private val signIdentityProof: suspend (ByteArray) -> ByteArray,
    private val scope: CoroutineScope,
    private val onVerified: () -> Unit,
) : AutoCloseable {

    private val lock = Any()
    private val verificationMutex = Mutex()
    private val closed = AtomicBoolean(false)
    private val random = SecureRandom()
    private val pending = LinkedHashMap<String, CompletableDeferred<Boolean>>()
    private val cachedResponses = LinkedHashMap<String, ByteArray>()
    private val mutableVerified = MutableStateFlow(false)
    val verifiedState = mutableVerified.asStateFlow()

    private var socket: DatagramSocket? = null
    private var receiverJob: Job? = null
    private var acceptedRequestCount = 0

    fun start(): Boolean {
        if (closed.get()) return false
        synchronized(lock) {
            if (socket?.isClosed == false) return true
        }

        return try {
            val p2pInterface = NetworkInterface.getByInetAddress(localAddress)
                ?: return false
            if (!p2pInterface.isUp || p2pInterface.isLoopback) return false

            val localP2pAddress = p2pInterface.interfaceAddresses
                .mapNotNull { address ->
                    val ipv4 = address.address as? Inet4Address ?: return@mapNotNull null
                    if (ipv4.address.contentEquals(localAddress.address)) {
                        P2pIpv4InterfaceAddress(ipv4, address.networkPrefixLength.toInt())
                    } else {
                        null
                    }
                }
                .firstOrNull()
                ?: return false

            if (!P2pTcpEndpointPolicy.isOnSameSubnet(localP2pAddress, remoteAddress)) return false

            val datagram = DatagramSocket(null).apply {
                reuseAddress = false
                broadcast = false
                soTimeout = RECEIVE_POLL_TIMEOUT_MILLIS
                bind(InetSocketAddress(localAddress, PORT))
            }
            synchronized(lock) {
                if (closed.get()) {
                    datagram.close()
                    return false
                }
                socket = datagram
                receiverJob = scope.launch(Dispatchers.IO) { receiveLoop(datagram) }
            }
            true
        } catch (_: SecurityException) {
            false
        } catch (_: SocketException) {
            false
        } catch (_: RuntimeException) {
            false
        }
    }

    suspend fun verify(): Boolean = verificationMutex.withLock {
        if (closed.get() || mutableVerified.value) return@withLock mutableVerified.value
        val ready = synchronized(lock) {
            socket?.isClosed == false && receiverJob?.isActive == true
        }
        if (!ready) return@withLock false

        val localFingerprint = runCatching {
            VoiceCallCapabilityCodec.fingerprintBytes(localIdentity.fingerprintHex)
        }.getOrNull() ?: return@withLock false
        val remoteFingerprint = runCatching {
            VoiceCallCapabilityCodec.fingerprintBytes(remoteIdentity.identityId.sha256Fingerprint)
        }.getOrNull() ?: return@withLock false

        val nonce = ByteArray(VoiceCallCapabilityCodec.NONCE_BYTES).also(random::nextBytes)
        val signingPayload = VoiceCallCapabilityCodec.signingPayload(
            type = VoiceCallCapabilityMessageType.REQUEST,
            sessionId = sessionId,
            nonce = nonce,
            callProtocolVersion = VoiceCallCapabilityCodec.CALL_PROTOCOL_VERSION,
            initiatorFingerprint = localFingerprint,
            responderFingerprint = remoteFingerprint,
        )
        val signature = try {
            signIdentityProof(signingPayload)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            return@withLock false
        }
        val encoded = try {
            VoiceCallCapabilityCodec.encode(
                VoiceCallCapabilityPacket(
                    type = VoiceCallCapabilityMessageType.REQUEST,
                    sessionId = sessionId,
                    nonce = nonce,
                    callProtocolVersion = VoiceCallCapabilityCodec.CALL_PROTOCOL_VERSION,
                    initiatorFingerprint = localFingerprint,
                    responderFingerprint = remoteFingerprint,
                    signature = signature,
                ),
            )
        } catch (_: IllegalArgumentException) {
            return@withLock false
        }

        val nonceKey = nonce.toHex()
        val response = CompletableDeferred<Boolean>()
        synchronized(lock) {
            if (closed.get()) return@withLock false
            pending[nonceKey] = response
        }

        try {
            repeat(MAX_SEND_ATTEMPTS) {
                if (closed.get()) return@withLock false
                val target = synchronized(lock) { socket?.takeUnless { it.isClosed } }
                    ?: return@withLock false
                try {
                    withContext(Dispatchers.IO) {
                        target.send(DatagramPacket(encoded, encoded.size, remoteAddress, PORT))
                    }
                    val received = try {
                        withTimeout(RESPONSE_TIMEOUT_MILLIS) { response.await() }
                    } catch (_: TimeoutCancellationException) {
                        false
                    }
                    if (received) return@withLock true
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: IOException) {
                    return@withLock false
                } catch (_: RuntimeException) {
                    return@withLock false
                }
            }
            false
        } finally {
            synchronized(lock) { pending.remove(nonceKey) }
        }
    }

    private suspend fun receiveLoop(datagram: DatagramSocket) {
        val buffer = ByteArray(VoiceCallCapabilityCodec.MAX_PACKET_BYTES)
        while (!closed.get() && !datagram.isClosed) {
            try {
                val incoming = DatagramPacket(buffer, buffer.size)
                datagram.receive(incoming)
                if (incoming.length !in 1..VoiceCallCapabilityCodec.MAX_PACKET_BYTES ||
                    incoming.address != remoteAddress ||
                    incoming.port != PORT
                ) continue

                val packet = runCatching {
                    VoiceCallCapabilityCodec.decode(incoming.data.copyOfRange(0, incoming.length))
                }.getOrNull() ?: continue
                if (packet.sessionId != sessionId ||
                    packet.callProtocolVersion != VoiceCallCapabilityCodec.CALL_PROTOCOL_VERSION
                ) continue

                when (packet.type) {
                    VoiceCallCapabilityMessageType.REQUEST -> handleRequest(datagram, incoming, packet)
                    VoiceCallCapabilityMessageType.RESPONSE -> handleResponse(packet)
                }
            } catch (_: java.net.SocketTimeoutException) {
                continue
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: SocketException) {
                if (datagram.isClosed || closed.get()) return
            } catch (_: IOException) {
                if (datagram.isClosed || closed.get()) return
            } catch (_: RuntimeException) {
                // Malformed peer packets do not terminate the authenticated TLS session.
            }
        }
    }

    private suspend fun handleRequest(
        datagram: DatagramSocket,
        source: DatagramPacket,
        packet: VoiceCallCapabilityPacket,
    ) {
        val localFingerprint = VoiceCallCapabilityCodec.fingerprintBytes(localIdentity.fingerprintHex)
        val remoteFingerprint =
            VoiceCallCapabilityCodec.fingerprintBytes(remoteIdentity.identityId.sha256Fingerprint)
        if (!packet.initiatorFingerprint.contentEquals(remoteFingerprint) ||
            !packet.responderFingerprint.contentEquals(localFingerprint) ||
            !VoiceCallCapabilityCodec.verifySignature(packet, remoteIdentity.publicKeyEncoded)
        ) return

        val nonceKey = packet.nonce.toHex()
        val cached = synchronized(lock) {
            if (closed.get()) return
            cachedResponses[nonceKey]
        }
        if (cached != null) {
            runCatching {
                datagram.send(DatagramPacket(cached, cached.size, source.address, source.port))
            }
            return
        }
        synchronized(lock) {
            if (closed.get() || acceptedRequestCount >= MAX_ACCEPTED_REQUESTS_PER_SESSION) return
            acceptedRequestCount += 1
        }

        val responsePayload = VoiceCallCapabilityCodec.signingPayload(
            type = VoiceCallCapabilityMessageType.RESPONSE,
            sessionId = sessionId,
            nonce = packet.nonce,
            callProtocolVersion = VoiceCallCapabilityCodec.CALL_PROTOCOL_VERSION,
            initiatorFingerprint = packet.initiatorFingerprint,
            responderFingerprint = packet.responderFingerprint,
        )
        val signature = try {
            signIdentityProof(responsePayload)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            return
        }
        val responseBytes = try {
            VoiceCallCapabilityCodec.encode(
                packet.copy(
                    type = VoiceCallCapabilityMessageType.RESPONSE,
                    signature = signature,
                ),
            )
        } catch (_: IllegalArgumentException) {
            return
        }
        if (datagram.isClosed) return
        val sent = runCatching {
            datagram.send(DatagramPacket(responseBytes, responseBytes.size, source.address, source.port))
        }.isSuccess
        if (!sent) return

        synchronized(lock) {
            cachedResponses[nonceKey] = responseBytes
            while (cachedResponses.size > MAX_CACHED_RESPONSES) {
                cachedResponses.remove(cachedResponses.keys.first())
            }
        }
        markVerified()
    }

    private fun handleResponse(packet: VoiceCallCapabilityPacket) {
        val localFingerprint = VoiceCallCapabilityCodec.fingerprintBytes(localIdentity.fingerprintHex)
        val remoteFingerprint =
            VoiceCallCapabilityCodec.fingerprintBytes(remoteIdentity.identityId.sha256Fingerprint)
        if (!packet.initiatorFingerprint.contentEquals(localFingerprint) ||
            !packet.responderFingerprint.contentEquals(remoteFingerprint) ||
            !VoiceCallCapabilityCodec.verifySignature(packet, remoteIdentity.publicKeyEncoded)
        ) return

        val deferred = synchronized(lock) {
            if (closed.get()) return
            pending[packet.nonce.toHex()]
        } ?: return
        deferred.complete(true)
        markVerified()
    }

    private fun markVerified() {
        if (closed.get()) return
        if (mutableVerified.compareAndSet(false, true)) {
            runCatching(onVerified)
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        val activeSocket = synchronized(lock) {
            pending.values.forEach { it.complete(false) }
            pending.clear()
            cachedResponses.clear()
            socket.also { socket = null }
        }
        runCatching { activeSocket?.close() }
        receiverJob?.cancel()
        receiverJob = null
        mutableVerified.value = false
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private companion object {
        const val PORT = 49_274
        const val RECEIVE_POLL_TIMEOUT_MILLIS = 500
        const val RESPONSE_TIMEOUT_MILLIS = 900L
        const val MAX_SEND_ATTEMPTS = 3
        const val MAX_ACCEPTED_REQUESTS_PER_SESSION = 64
        const val MAX_CACHED_RESPONSES = 32
    }
}
