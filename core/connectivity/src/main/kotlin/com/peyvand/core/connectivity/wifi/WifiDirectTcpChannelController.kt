package com.peyvand.core.connectivity.wifi

import android.annotation.SuppressLint
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pManager
import com.peyvand.core.domain.connection.P2pIpv4InterfaceAddress
import com.peyvand.core.domain.connection.P2pTcpChannelFailure
import com.peyvand.core.domain.connection.P2pTcpChannelSnapshot
import com.peyvand.core.domain.connection.P2pTcpChannelStateRepository
import com.peyvand.core.domain.connection.P2pTcpChannelStatus
import com.peyvand.core.domain.connection.P2pTcpEndpointPolicy
import com.peyvand.core.domain.connection.PeerConnectionSnapshot
import com.peyvand.core.domain.connection.PeerConnectionStatus
import java.io.IOException
import java.net.ConnectException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.NoRouteToHostException
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Establishes one raw TCP socket inside the active Wi-Fi Direct group.
 *
 * The Group Owner listens on an IPv4 address owned by the exact P2P interface returned
 * by WifiP2pGroup.getInterface(). The Group Client binds its source address to that same
 * interface and connects to WifiP2pInfo.groupOwnerAddress. No wildcard bind, default
 * network fallback, or public-network discovery is used.
 *
 * This class deliberately does not read or write application bytes. The connected socket
 * is retained privately for the next session-authentication/mTLS layer. A TCP connection
 * is not peer authentication and must never authorize file metadata or content.
 */
internal class WifiDirectTcpChannelController(
    private val managerProvider: () -> WifiP2pManager?,
    private val channelProvider: () -> WifiP2pManager.Channel?,
    private val isMonitoring: () -> Boolean,
    private val hasRequiredRuntimePermission: () -> Boolean,
    private val port: Int = DEFAULT_PORT,
    private val acceptTimeoutMillis: Long = DEFAULT_ACCEPT_TIMEOUT_MILLIS,
    private val connectDeadlineMillis: Long = DEFAULT_CONNECT_DEADLINE_MILLIS,
    private val groupInfoTimeoutMillis: Long = DEFAULT_GROUP_INFO_TIMEOUT_MILLIS,
) : P2pTcpChannelStateRepository {
    private val mutableState = MutableStateFlow(P2pTcpChannelSnapshot())
    override val tcpChannel = mutableState.asStateFlow()

    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var generation = 0L
    private var activeGroupKey: String? = null
    private var sessionJob: Job? = null
    private var listenerSocket: ServerSocket? = null
    private var pendingSocket: Socket? = null
    private var connectedSocket: Socket? = null

    init {
        require(port in 1..65535)
        require(acceptTimeoutMillis > 0L)
        require(connectDeadlineMillis > 0L)
        require(groupInfoTimeoutMillis > 0L)
    }

    fun start(group: PeerConnectionSnapshot) {
        if (group.status != PeerConnectionStatus.GROUP_FORMED || group.isGroupOwner == null) {
            stop()
            publishFailure(P2pTcpChannelFailure.GROUP_INFO_UNAVAILABLE)
            return
        }

        val groupKey = "${group.isGroupOwner}|${group.groupOwnerAddress.orEmpty()}"
        val requestGeneration = synchronized(lock) {
            val currentStatus = mutableState.value.status
            if (activeGroupKey == groupKey &&
                currentStatus in ACTIVE_STATUSES
            ) {
                return
            }

            generation += 1L
            closeSocketsLocked()
            sessionJob?.cancel()
            activeGroupKey = groupKey
            mutableState.value = P2pTcpChannelSnapshot(
                status = P2pTcpChannelStatus.RESOLVING_INTERFACE,
                isGroupOwner = group.isGroupOwner,
            )
            generation
        }

        val job = scope.launch {
            establishChannel(group, requestGeneration)
        }
        synchronized(lock) {
            if (generation == requestGeneration) {
                sessionJob = job
            } else {
                job.cancel()
            }
        }
    }

    /** Close listeners and sockets immediately when the P2P group is removed or invalidated. */
    fun stop() {
        synchronized(lock) {
            generation += 1L
            sessionJob?.cancel()
            sessionJob = null
            activeGroupKey = null
            closeSocketsLocked()
            mutableState.value = P2pTcpChannelSnapshot()
        }
    }

    /**
     * Internal handoff point for the security layer in this module.
     * Presentation and file-transfer code must never receive this raw socket.
     */
    internal fun connectedSocketForSecurityUpgrade(): Socket? = synchronized(lock) {
        connectedSocket?.takeIf { it.isConnected && !it.isClosed }
    }

    private suspend fun establishChannel(
        connection: PeerConnectionSnapshot,
        requestGeneration: Long,
    ) {
        try {
            val isGroupOwner = connection.isGroupOwner
                ?: throw ChannelSetupException(P2pTcpChannelFailure.GROUP_INFO_UNAVAILABLE)
            ensureCurrent(requestGeneration)
            if (!hasRequiredRuntimePermission()) {
                throw ChannelSetupException(P2pTcpChannelFailure.PERMISSION_DENIED)
            }

            val manager = managerProvider()
                ?: throw ChannelSetupException(P2pTcpChannelFailure.GROUP_INFO_UNAVAILABLE)
            val activeChannel = channelProvider()
                ?: throw ChannelSetupException(P2pTcpChannelFailure.GROUP_INFO_UNAVAILABLE)

            val groupInfo = try {
                withTimeout(groupInfoTimeoutMillis) {
                    requestGroupInfo(manager, activeChannel)
                }
            } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
                throw ChannelSetupException(P2pTcpChannelFailure.GROUP_INFO_UNAVAILABLE)
            }

            ensureCurrent(requestGeneration)
            if (groupInfo.isGroupOwner != isGroupOwner) {
                throw ChannelSetupException(P2pTcpChannelFailure.GROUP_ROLE_MISMATCH)
            }

            val interfaceName = groupInfo.getInterface()
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: throw ChannelSetupException(P2pTcpChannelFailure.INTERFACE_UNAVAILABLE)

            val networkInterface = NetworkInterface.getByName(interfaceName)
                ?: throw ChannelSetupException(P2pTcpChannelFailure.INTERFACE_UNAVAILABLE)

            if (!networkInterface.isUp || networkInterface.isLoopback) {
                throw ChannelSetupException(P2pTcpChannelFailure.INTERFACE_DOWN)
            }

            val interfaceAddresses = networkInterface.interfaceAddresses.mapNotNull { item ->
                val address = item.address as? Inet4Address ?: return@mapNotNull null
                P2pIpv4InterfaceAddress(
                    address = address,
                    prefixLength = item.networkPrefixLength.toInt(),
                )
            }
            val localAddress = P2pTcpEndpointPolicy.selectLocalAddress(interfaceAddresses)
                ?: throw ChannelSetupException(P2pTcpChannelFailure.LOCAL_IPV4_UNAVAILABLE)

            ensureCurrent(requestGeneration)
            if (isGroupOwner) {
                acceptGroupClient(localAddress, requestGeneration)
            } else {
                connectToGroupOwner(
                    localAddress = localAddress,
                    ownerAddressValue = connection.groupOwnerAddress,
                    requestGeneration = requestGeneration,
                )
            }
        } catch (exception: CancellationException) {
            resetStateIfCurrent(requestGeneration)
            throw exception
        } catch (exception: ChannelSetupException) {
            failIfCurrent(requestGeneration, exception.failure)
        } catch (_: SecurityException) {
            failIfCurrent(requestGeneration, P2pTcpChannelFailure.PERMISSION_DENIED)
        } catch (_: SocketTimeoutException) {
            failIfCurrent(requestGeneration, P2pTcpChannelFailure.CONNECT_TIMEOUT)
        } catch (_: SocketException) {
            failIfCurrent(requestGeneration, P2pTcpChannelFailure.IO_ERROR)
        } catch (_: IOException) {
            failIfCurrent(requestGeneration, P2pTcpChannelFailure.IO_ERROR)
        } catch (_: RuntimeException) {
            failIfCurrent(requestGeneration, P2pTcpChannelFailure.IO_ERROR)
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun requestGroupInfo(
        manager: WifiP2pManager,
        channel: WifiP2pManager.Channel,
    ): WifiP2pGroup = suspendCancellableCoroutine { continuation ->
        try {
            manager.requestGroupInfo(
                channel,
                WifiP2pManager.GroupInfoListener { group ->
                    if (!continuation.isActive) return@GroupInfoListener
                    if (group == null) {
                        continuation.resumeWithException(
                            ChannelSetupException(P2pTcpChannelFailure.GROUP_INFO_UNAVAILABLE),
                        )
                    } else {
                        continuation.resume(group)
                    }
                },
            )
        } catch (exception: SecurityException) {
            continuation.resumeWithException(exception)
        } catch (exception: RuntimeException) {
            continuation.resumeWithException(exception)
        }
    }

    private suspend fun acceptGroupClient(
        localAddress: P2pIpv4InterfaceAddress,
        requestGeneration: Long,
    ) {
        val server = ServerSocket()
        synchronized(lock) {
            if (generation != requestGeneration) {
                server.close()
                throw CancellationException("P2P group changed before listener start")
            }
            listenerSocket = server
        }

        try {
            server.reuseAddress = false
            server.soTimeout = ACCEPT_POLL_TIMEOUT_MILLIS
            try {
                server.bind(InetSocketAddress(localAddress.address, port), SERVER_BACKLOG)
            } catch (_: IOException) {
                throw ChannelSetupException(P2pTcpChannelFailure.LISTENER_BIND_FAILED)
            }

            publishIfCurrent(
                requestGeneration,
                P2pTcpChannelSnapshot(
                    status = P2pTcpChannelStatus.LISTENING,
                    isGroupOwner = true,
                ),
            )

            val deadline = System.nanoTime() + acceptTimeoutMillis * NANOS_PER_MILLI
            var rejectedConnections = 0

            while (System.nanoTime() < deadline) {
                ensureCurrent(requestGeneration)
                val socket = try {
                    server.accept()
                } catch (_: SocketTimeoutException) {
                    continue
                }

                val remoteAddress = socket.inetAddress as? Inet4Address
                if (remoteAddress == null ||
                    !P2pTcpEndpointPolicy.isOnSameSubnet(localAddress, remoteAddress)
                ) {
                    closeQuietly(socket)
                    rejectedConnections += 1
                    if (rejectedConnections >= MAX_REJECTED_CONNECTIONS) {
                        throw ChannelSetupException(P2pTcpChannelFailure.CONNECTION_REJECTED)
                    }
                    continue
                }

                configureConnectedSocket(socket)
                synchronized(lock) {
                    if (generation != requestGeneration) {
                        closeQuietly(socket)
                        throw CancellationException("P2P group changed during TCP accept")
                    }
                    connectedSocket = socket
                    if (listenerSocket === server) listenerSocket = null
                }
                publishIfCurrent(
                    requestGeneration,
                    P2pTcpChannelSnapshot(
                        status = P2pTcpChannelStatus.CONNECTED,
                        isGroupOwner = true,
                    ),
                )
                return
            }

            throw ChannelSetupException(P2pTcpChannelFailure.ACCEPT_TIMEOUT)
        } finally {
            closeQuietly(server)
            synchronized(lock) {
                if (listenerSocket === server) listenerSocket = null
            }
        }
    }

    private suspend fun connectToGroupOwner(
        localAddress: P2pIpv4InterfaceAddress,
        ownerAddressValue: String?,
        requestGeneration: Long,
    ) {
        val ownerAddress = parseIpv4Literal(ownerAddressValue)
            ?: throw ChannelSetupException(P2pTcpChannelFailure.OWNER_ADDRESS_UNAVAILABLE)

        if (!P2pTcpEndpointPolicy.isOnSameSubnet(localAddress, ownerAddress)) {
            throw ChannelSetupException(P2pTcpChannelFailure.OWNER_OUTSIDE_P2P_SUBNET)
        }

        publishIfCurrent(
            requestGeneration,
            P2pTcpChannelSnapshot(
                status = P2pTcpChannelStatus.CONNECTING,
                isGroupOwner = false,
            ),
        )

        val deadline = System.nanoTime() + connectDeadlineMillis * NANOS_PER_MILLI
        var lastTransientFailure: IOException? = null

        while (System.nanoTime() < deadline) {
            ensureCurrent(requestGeneration)
            val socket = Socket()
            synchronized(lock) {
                if (generation != requestGeneration) {
                    closeQuietly(socket)
                    throw CancellationException("P2P group changed before TCP connect")
                }
                pendingSocket = socket
            }

            try {
                // Binding the source address pins routing to the P2P interface.
                socket.bind(InetSocketAddress(localAddress.address, 0))
                socket.connect(
                    InetSocketAddress(ownerAddress, port),
                    CONNECT_ATTEMPT_TIMEOUT_MILLIS,
                )
                configureConnectedSocket(socket)

                synchronized(lock) {
                    if (generation != requestGeneration) {
                        closeQuietly(socket)
                        throw CancellationException("P2P group changed during TCP connect")
                    }
                    pendingSocket = null
                    connectedSocket = socket
                }
                publishIfCurrent(
                    requestGeneration,
                    P2pTcpChannelSnapshot(
                        status = P2pTcpChannelStatus.CONNECTED,
                        isGroupOwner = false,
                    ),
                )
                return
            } catch (exception: ConnectException) {
                lastTransientFailure = exception
            } catch (exception: NoRouteToHostException) {
                lastTransientFailure = exception
            } catch (exception: SocketTimeoutException) {
                lastTransientFailure = exception
            } finally {
                val isConnected = synchronized(lock) { connectedSocket === socket }
                if (!isConnected) {
                    closeQuietly(socket)
                    synchronized(lock) {
                        if (pendingSocket === socket) pendingSocket = null
                    }
                }
            }

            delay(CONNECT_RETRY_DELAY_MILLIS)
        }

        if (lastTransientFailure == null) {
            throw ChannelSetupException(P2pTcpChannelFailure.CONNECT_TIMEOUT)
        }
        throw ChannelSetupException(P2pTcpChannelFailure.CONNECT_TIMEOUT, lastTransientFailure)
    }

    private fun configureConnectedSocket(socket: Socket) {
        socket.tcpNoDelay = true
        socket.keepAlive = true
        // Reserved for the immediately following authenticated TLS handshake.
        // No application payload is read or written by this raw-channel controller.
        socket.soTimeout = TLS_HANDSHAKE_TIMEOUT_MILLIS
    }

    private fun parseIpv4Literal(value: String?): Inet4Address? {
        if (value.isNullOrBlank()) return null
        val pieces = value.split('.')
        if (pieces.size != 4) return null

        val octets = pieces.map { part ->
            if (part.isEmpty() || part.any { !it.isDigit() }) return null
            val number = part.toIntOrNull() ?: return null
            if (number !in 0..255) return null
            number.toByte()
        }.toByteArray()

        return try {
            InetAddress.getByAddress(octets) as? Inet4Address
        } catch (_: IOException) {
            null
        }
    }

    private suspend fun ensureCurrent(expectedGeneration: Long) {
        currentCoroutineContext().ensureActive()
        synchronized(lock) {
            if (generation != expectedGeneration) {
                throw CancellationException("P2P group generation changed")
            }
        }
        if (!isMonitoring()) throw CancellationException("P2P monitoring stopped")
        if (!hasRequiredRuntimePermission()) {
            throw ChannelSetupException(P2pTcpChannelFailure.PERMISSION_DENIED)
        }
    }

    private fun publishIfCurrent(
        expectedGeneration: Long,
        snapshot: P2pTcpChannelSnapshot,
    ) {
        synchronized(lock) {
            if (generation == expectedGeneration) {
                mutableState.value = snapshot
            }
        }
    }

    private fun failIfCurrent(
        expectedGeneration: Long,
        failure: P2pTcpChannelFailure,
    ) {
        synchronized(lock) {
            if (generation != expectedGeneration) return
            closeSocketsLocked()
            activeGroupKey = null
            mutableState.value = P2pTcpChannelSnapshot(
                status = P2pTcpChannelStatus.FAILED,
                failure = failure,
            )
        }
    }

    private fun resetStateIfCurrent(expectedGeneration: Long) {
        synchronized(lock) {
            if (generation != expectedGeneration) return
            closeSocketsLocked()
            activeGroupKey = null
            mutableState.value = P2pTcpChannelSnapshot()
        }
    }

    private fun publishFailure(failure: P2pTcpChannelFailure) {
        synchronized(lock) {
            closeSocketsLocked()
            activeGroupKey = null
            mutableState.value = P2pTcpChannelSnapshot(
                status = P2pTcpChannelStatus.FAILED,
                failure = failure,
            )
        }
    }

    private fun closeSocketsLocked() {
        closeQuietly(listenerSocket)
        closeQuietly(pendingSocket)
        closeQuietly(connectedSocket)
        listenerSocket = null
        pendingSocket = null
        connectedSocket = null
    }

    private fun closeQuietly(closeable: AutoCloseable?) {
        try {
            closeable?.close()
        } catch (_: IOException) {
            // Cleanup is best effort; the caller owns the authoritative state transition.
        } catch (_: RuntimeException) {
            // Cleanup must not throw while cancelling a partially created channel.
        }
    }

    private class ChannelSetupException(
        val failure: P2pTcpChannelFailure,
        cause: Throwable? = null,
    ) : IOException(failure.name, cause)

    private companion object {
        const val DEFAULT_PORT = 49273
        const val SERVER_BACKLOG = 1
        const val ACCEPT_POLL_TIMEOUT_MILLIS = 400
        const val CONNECT_ATTEMPT_TIMEOUT_MILLIS = 1_200
        const val CONNECT_RETRY_DELAY_MILLIS = 250L
        const val DEFAULT_ACCEPT_TIMEOUT_MILLIS = 20_000L
        const val DEFAULT_CONNECT_DEADLINE_MILLIS = 15_000L
        const val DEFAULT_GROUP_INFO_TIMEOUT_MILLIS = 5_000L
        const val TLS_HANDSHAKE_TIMEOUT_MILLIS = 15_000
        const val MAX_REJECTED_CONNECTIONS = 3
        const val NANOS_PER_MILLI = 1_000_000L
        val ACTIVE_STATUSES = setOf(
            P2pTcpChannelStatus.RESOLVING_INTERFACE,
            P2pTcpChannelStatus.LISTENING,
            P2pTcpChannelStatus.CONNECTING,
            P2pTcpChannelStatus.CONNECTED,
        )
    }
}
