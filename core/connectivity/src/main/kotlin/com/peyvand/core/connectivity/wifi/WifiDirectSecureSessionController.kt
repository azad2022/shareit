package com.peyvand.core.connectivity.wifi

import com.peyvand.core.domain.connection.PeerConnectionSnapshot
import com.peyvand.core.domain.connection.PeerTransferRole
import com.peyvand.core.domain.connection.SecureSessionFailure
import com.peyvand.core.domain.connection.SecureSessionSnapshot
import com.peyvand.core.domain.connection.SecureSessionStatus
import com.peyvand.core.transfer.protocol.TransferFrame
import com.peyvand.core.transfer.protocol.TransferFrameCodec
import com.peyvand.core.transfer.protocol.TransferFrameType
import com.peyvand.core.transfer.security.PairingBootstrapCodec
import com.peyvand.core.transfer.security.PairingBootstrapHello
import com.peyvand.core.transfer.security.PairingEndpointMaterial
import com.peyvand.core.transfer.security.PairingParticipantRole
import com.peyvand.core.transfer.security.PairingShortAuthenticationString
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.Socket
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.interfaces.ECPublicKey
import java.util.UUID
import javax.net.ssl.KeyManager
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/**
 * Session-scoped peer authentication over the already established P2P TCP socket.
 *
 * The only plaintext exchanged is a bounded ephemeral-certificate bootstrap and a
 * zero-payload bilateral approval frame. File metadata and file bytes are prohibited
 * until the SAS is approved on both devices and pinned mutual TLS completes.
 */
internal class WifiDirectSecureSessionController(
    private val tcpChannelProvider: () -> kotlinx.coroutines.flow.StateFlow<
        com.peyvand.core.domain.connection.P2pTcpChannelSnapshot
    >,
    private val rawSocketProvider: () -> Socket?,
) : com.peyvand.core.domain.connection.PeerSecureSessionRepository {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutableState = MutableStateFlow(SecureSessionSnapshot())
    override val secureSession: StateFlow<SecureSessionSnapshot> = mutableState.asStateFlow()

    private val lock = Any()
    private var generation = 0L
    private var sessionJob: Job? = null
    private var approval: CompletableDeferred<Boolean>? = null
    private var rawSocket: Socket? = null
    private var secureSocket: SSLSocket? = null
    private var activeKeyAlias: String? = null
    private val staleAliasCleanupJob = scope.launch { cleanupStaleKeyAliases() }

    fun start(connection: PeerConnectionSnapshot) {
        if (connection.status != com.peyvand.core.domain.connection.PeerConnectionStatus.GROUP_FORMED) {
            stop()
            return
        }
        val requestGeneration = synchronized(lock) {
            if (mutableState.value.status in setOf(
                    SecureSessionStatus.BOOTSTRAPPING,
                    SecureSessionStatus.WAITING_FOR_SAS_APPROVAL,
                    SecureSessionStatus.TLS_HANDSHAKING,
                    SecureSessionStatus.AUTHENTICATED,
                )
            ) return
            generation += 1L
            sessionJob?.cancel()
            closeSessionResourcesLocked()
            mutableState.value = SecureSessionSnapshot(SecureSessionStatus.BOOTSTRAPPING)
            generation
        }
        val job = scope.launch { establish(connection, requestGeneration) }
        synchronized(lock) {
            if (generation == requestGeneration) sessionJob = job else job.cancel()
        }
    }

    override fun approvePairing() {
        synchronized(lock) {
            if (mutableState.value.status == SecureSessionStatus.WAITING_FOR_SAS_APPROVAL) {
                approval?.complete(true)
            }
        }
    }

    override fun rejectPairing() {
        synchronized(lock) {
            if (mutableState.value.status == SecureSessionStatus.WAITING_FOR_SAS_APPROVAL) {
                approval?.complete(false)
            }
        }
    }

    fun stop() {
        synchronized(lock) {
            generation += 1L
            sessionJob?.cancel()
            sessionJob = null
            approval?.cancel()
            approval = null
            closeSessionResourcesLocked()
            mutableState.value = SecureSessionSnapshot()
        }
    }

    private suspend fun establish(
        connection: PeerConnectionSnapshot,
        expectedGeneration: Long,
    ) {
        var alias: String? = null
        var localSocket: Socket? = null
        var tlsSocket: SSLSocket? = null
        val diagnostics = SecureTlsDiagnostics()
        var currentStage = "TCP_CHANNEL_WAIT"
        var tlsRoleForDiagnostics = "UNKNOWN"
        try {
            diagnostics.event("SESSION_START")
            val channel = try {
                withTimeout(TCP_READY_TIMEOUT_MILLIS) {
                    tcpChannelProvider().first { snapshot ->
                        snapshot.status == com.peyvand.core.domain.connection.P2pTcpChannelStatus.CONNECTED ||
                            snapshot.status == com.peyvand.core.domain.connection.P2pTcpChannelStatus.FAILED
                    }
                }
            } catch (_: TimeoutCancellationException) {
                throw SessionSetupException(SecureSessionFailure.BOOTSTRAP_TIMEOUT)
            }
            ensureCurrent(expectedGeneration)
            diagnostics.event(
                "TCP_CHANNEL_RESULT",
                "status=${channel.status} groupRole=${channel.isGroupOwner ?: "unknown"}",
            )
            if (channel.status != com.peyvand.core.domain.connection.P2pTcpChannelStatus.CONNECTED) {
                throw SessionSetupException(SecureSessionFailure.IO_ERROR)
            }
            val groupOwner = channel.isGroupOwner
                ?: throw SessionSetupException(SecureSessionFailure.IO_ERROR)
            if (connection.isGroupOwner != groupOwner) {
                diagnostics.event(
                    "GROUP_ROLE_MISMATCH",
                    "connectionGroupOwner=${connection.isGroupOwner} tcpGroupOwner=$groupOwner",
                )
                throw SessionSetupException(SecureSessionFailure.IO_ERROR)
            }
            val activeSocket = rawSocketProvider()
                ?.takeIf { it.isConnected && !it.isClosed }
                ?: throw SessionSetupException(SecureSessionFailure.IO_ERROR)
            localSocket = activeSocket
            synchronized(lock) { rawSocket = activeSocket }
            currentStage = "TCP_CONNECTED"

            val localRole = if (connection.locallyInitiated) {
                PeerTransferRole.SENDER
            } else {
                PeerTransferRole.RECEIVER
            }
            val participantRole = when (localRole) {
                PeerTransferRole.SENDER -> PairingParticipantRole.SENDER
                PeerTransferRole.RECEIVER -> PairingParticipantRole.RECEIVER
            }
            diagnostics.deviceContext(
                groupRole = if (groupOwner) "GROUP_OWNER" else "GROUP_CLIENT",
                productRole = localRole.name,
            )
            diagnostics.event(
                "TCP_CONNECTED",
                "groupRole=${if (groupOwner) "GROUP_OWNER" else "GROUP_CLIENT"} " +
                    "productRole=${localRole.name} connectionGroupOwner=${connection.isGroupOwner}",
            )

            val sessionId: UUID
            val remoteHello: PairingBootstrapHello
            staleAliasCleanupJob.join()
            val keyAlias = "$SESSION_KEY_ALIAS_PREFIX${UUID.randomUUID().toString().replace("-", "")}"
            alias = keyAlias

            synchronized(lock) { activeKeyAlias = keyAlias }
            currentStage = "LOCAL_IDENTITY_CREATION"
            val identity = try {
                createEphemeralIdentity(keyAlias)
            } catch (exception: Exception) {
                diagnostics.failure("SESSION_FAILURE", currentStage, tlsRoleForDiagnostics, exception)
                throw SessionSetupException(SecureSessionFailure.KEYSTORE_ERROR, exception)
            }
            val localFingerprintAtCreation = sha256(identity.certificate.encoded)
            diagnostics.event(
                "LOCAL_IDENTITY_CREATED",
                "keyAlgorithm=EC curve=P-256 certificateValid=true selfSignatureValid=true " +
                    "certificateFingerprintPrefix=${SecureTlsDiagnostics.fingerprintPrefix(localFingerprintAtCreation)}",
            )
            val localNonce = ByteArray(PairingBootstrapHello.NONCE_BYTES).also {
                SecureRandom().nextBytes(it)
            }

            currentStage = "BOOTSTRAP_EXCHANGE"
            if (groupOwner) {
                sessionId = UUID.randomUUID()
                diagnostics.bindSession(sessionId)
                val localHello = PairingBootstrapHello(
                    sessionId = sessionId,
                    role = participantRole,
                    nonce = localNonce,
                    certificateDer = identity.certificate.encoded,
                )
                PairingBootstrapCodec.write(localHello, activeSocket.getOutputStream())
                diagnostics.event(
                    "LOCAL_BOOTSTRAP_SENT",
                    "participantRole=${participantRole.name} certificateBytes=${identity.certificate.encoded.size}",
                )
                remoteHello = PairingBootstrapCodec.read(activeSocket.getInputStream())
                diagnostics.event(
                    "REMOTE_BOOTSTRAP_RECEIVED",
                    "participantRole=${remoteHello.role.name} certificateBytes=${remoteHello.certificateBytes().size}",
                )
            } else {
                remoteHello = PairingBootstrapCodec.read(activeSocket.getInputStream())
                sessionId = remoteHello.sessionId
                diagnostics.bindSession(sessionId)
                diagnostics.event(
                    "REMOTE_BOOTSTRAP_RECEIVED",
                    "participantRole=${remoteHello.role.name} certificateBytes=${remoteHello.certificateBytes().size}",
                )
                val localHello = PairingBootstrapHello(
                    sessionId = sessionId,
                    role = participantRole,
                    nonce = localNonce,
                    certificateDer = identity.certificate.encoded,
                )
                PairingBootstrapCodec.write(localHello, activeSocket.getOutputStream())
                diagnostics.event(
                    "LOCAL_BOOTSTRAP_SENT",
                    "participantRole=${participantRole.name} certificateBytes=${identity.certificate.encoded.size}",
                )
            }
            ensureCurrent(expectedGeneration)

            if (remoteHello.sessionId != sessionId || remoteHello.role == participantRole) {
                throw SessionSetupException(SecureSessionFailure.PARTICIPANT_ROLE_MISMATCH)
            }
            currentStage = "REMOTE_CERTIFICATE_VALIDATION"
            val peerCertificate = parseAndValidatePeerCertificate(remoteHello.certificateBytes(), diagnostics)
            val localFingerprint = sha256(identity.certificate.encoded)
            val peerFingerprint = sha256(peerCertificate.encoded)
            diagnostics.event(
                "REMOTE_CERTIFICATE_VALIDATED",
                "validity=valid selfSignature=valid ecCurve=P-256 " +
                    "fingerprintPrefix=${SecureTlsDiagnostics.fingerprintPrefix(peerFingerprint)}",
            )
            val localMaterial = PairingEndpointMaterial(
                localFingerprint,
                localNonce,
                participantRole,
            )
            val remoteMaterial = PairingEndpointMaterial(
                peerFingerprint,
                remoteHello.nonceBytes(),
                remoteHello.role,
            )
            val sas = PairingShortAuthenticationString.derive(
                sessionId = sessionId,
                local = localMaterial,
                remote = remoteMaterial,
                protocolVersion = TransferFrameCodec.CURRENT_PROTOCOL_VERSION,
            )
            diagnostics.event(
                "SAS_DERIVED",
                "protocolVersion=${TransferFrameCodec.CURRENT_PROTOCOL_VERSION} sasValueLogged=false",
            )

            val approvalSignal = CompletableDeferred<Boolean>()
            synchronized(lock) {
                if (generation != expectedGeneration) throw CancellationException()
                approval = approvalSignal
                mutableState.value = SecureSessionSnapshot(
                    status = SecureSessionStatus.WAITING_FOR_SAS_APPROVAL,
                    sasCode = sas,
                )
            }
            val approved = try {
                withTimeout(USER_APPROVAL_TIMEOUT_MILLIS) { approvalSignal.await() }
            } catch (_: TimeoutCancellationException) {
                notifySessionClose(activeSocket, sessionId)
                throw SessionSetupException(SecureSessionFailure.USER_APPROVAL_TIMEOUT)
            }
            ensureCurrent(expectedGeneration)
            if (!approved) {
                diagnostics.event("LOCAL_APPROVAL_REJECTED")
                notifySessionClose(activeSocket, sessionId)
                throw PairingRejectedException()
            }
            diagnostics.event("LOCAL_APPROVAL_GRANTED")

            val approvalFrame = TransferFrame(
                protocolVersion = TransferFrameCodec.CURRENT_PROTOCOL_VERSION,
                type = TransferFrameType.PAIRING_APPROVED,
                sessionId = sessionId,
                payload = byteArrayOf(),
            )
            TransferFrameCodec.write(approvalFrame, activeSocket.getOutputStream())
            activeSocket.getOutputStream().flush()
            activeSocket.soTimeout = USER_APPROVAL_TIMEOUT_MILLIS.toInt()
            val remoteApproval = TransferFrameCodec.read(activeSocket.getInputStream())
                ?: throw SessionSetupException(SecureSessionFailure.PROTOCOL_ERROR)
            if (remoteApproval.sessionId != sessionId || remoteApproval.payloadSize != 0) {
                throw SessionSetupException(SecureSessionFailure.PROTOCOL_ERROR)
            }
            if (remoteApproval.type == TransferFrameType.SESSION_CLOSE) {
                throw PairingRejectedException()
            }
            if (remoteApproval.type != TransferFrameType.PAIRING_APPROVED) {
                throw SessionSetupException(SecureSessionFailure.PROTOCOL_ERROR)
            }
            diagnostics.event("REMOTE_APPROVAL_RECEIVED")

            ensureCurrent(expectedGeneration)
            currentStage = "TLS_HANDSHAKE"
            tlsRoleForDiagnostics = if (groupOwner) "SERVER" else "CLIENT"
            mutableState.value = SecureSessionSnapshot(SecureSessionStatus.TLS_HANDSHAKING)
            tlsSocket = createPinnedTlsSocket(
                raw = activeSocket,
                alias = keyAlias,
                identity = identity,
                expectedPeerFingerprint = peerFingerprint,
                isGroupOwner = groupOwner,
                diagnostics = diagnostics,
            )
            ensureCurrent(expectedGeneration)
            synchronized(lock) {
                if (generation != expectedGeneration) throw CancellationException()
                secureSocket = tlsSocket
                rawSocket = null
                approval = null
                mutableState.value = SecureSessionSnapshot(SecureSessionStatus.AUTHENTICATED)
                diagnostics.event("SECURE_SESSION_AUTHENTICATED")
            }
        } catch (exception: CancellationException) {
            diagnostics.event("SESSION_CANCELLED", "stage=$currentStage")
            closeQuietly(tlsSocket)
            closeQuietly(localSocket)
            alias?.let(::deleteKeyAlias)
            throw exception
        } catch (_: PairingRejectedException) {
            diagnostics.event("SESSION_REJECTED", "stage=$currentStage")
            closeQuietly(tlsSocket)
            closeQuietly(localSocket)
            alias?.let(::deleteKeyAlias)
            publishIfCurrent(expectedGeneration, SecureSessionSnapshot(SecureSessionStatus.REJECTED))
        } catch (exception: SessionSetupException) {
            diagnostics.failure("SESSION_FAILURE", currentStage, tlsRoleForDiagnostics, exception)
            closeQuietly(tlsSocket)
            closeQuietly(localSocket)
            alias?.let(::deleteKeyAlias)
            publishIfCurrent(
                expectedGeneration,
                SecureSessionSnapshot(
                    status = SecureSessionStatus.FAILED,
                    failure = exception.failure,
                ),
            )
        } catch (exception: java.security.GeneralSecurityException) {
            diagnostics.failure("SESSION_FAILURE", currentStage, tlsRoleForDiagnostics, exception)
            closeQuietly(tlsSocket)
            closeQuietly(localSocket)
            alias?.let(::deleteKeyAlias)
            publishIfCurrent(
                expectedGeneration,
                SecureSessionSnapshot(
                    status = SecureSessionStatus.FAILED,
                    failure = SecureSessionFailure.KEYSTORE_ERROR,
                ),
            )
        } catch (exception: IOException) {
            diagnostics.failure("SESSION_FAILURE", currentStage, tlsRoleForDiagnostics, exception)
            closeQuietly(tlsSocket)
            closeQuietly(localSocket)
            alias?.let(::deleteKeyAlias)
            publishIfCurrent(
                expectedGeneration,
                SecureSessionSnapshot(
                    status = SecureSessionStatus.FAILED,
                    failure = SecureSessionFailure.IO_ERROR,
                ),
            )
        } catch (exception: RuntimeException) {
            diagnostics.failure("SESSION_FAILURE", currentStage, tlsRoleForDiagnostics, exception)
            closeQuietly(tlsSocket)
            closeQuietly(localSocket)
            alias?.let(::deleteKeyAlias)
            publishIfCurrent(
                expectedGeneration,
                SecureSessionSnapshot(
                    status = SecureSessionStatus.FAILED,
                    failure = SecureSessionFailure.IO_ERROR,
                ),
            )
        }
    }

    private fun createEphemeralIdentity(alias: String): EphemeralTlsIdentity =
        AndroidEphemeralTlsIdentityFactory.create(alias)

    private fun parseAndValidatePeerCertificate(
        encoded: ByteArray,
        diagnostics: SecureTlsDiagnostics,
    ): X509Certificate {
        if (encoded.size !in 1..PairingBootstrapHello.MAX_CERTIFICATE_BYTES) {
            val exception = IllegalArgumentException("Peer certificate length is outside the bootstrap limit")
            diagnostics.failure("PEER_CERTIFICATE_VALIDATION_FAILURE", "CERTIFICATE_LENGTH", "unknown", exception)
            throw SessionSetupException(SecureSessionFailure.INVALID_PEER_CERTIFICATE, exception)
        }
        val certificate = try {
            CertificateFactory.getInstance("X.509")
                .generateCertificate(ByteArrayInputStream(encoded)) as X509Certificate
        } catch (exception: Exception) {
            diagnostics.failure("PEER_CERTIFICATE_VALIDATION_FAILURE", "CERTIFICATE_PARSE", "unknown", exception)
            throw SessionSetupException(SecureSessionFailure.INVALID_PEER_CERTIFICATE, exception)
        }
        try {
            certificate.checkValidity()
            certificate.verify(certificate.publicKey)
        } catch (exception: Exception) {
            diagnostics.failure(
                "PEER_CERTIFICATE_VALIDATION_FAILURE",
                "CERTIFICATE_VALIDITY_OR_SIGNATURE",
                "unknown",
                exception,
            )
            throw SessionSetupException(SecureSessionFailure.INVALID_PEER_CERTIFICATE, exception)
        }
        val ecKey = certificate.publicKey as? ECPublicKey
            ?: run {
                val exception = IllegalArgumentException("Peer certificate key is not EC")
                diagnostics.failure("PEER_CERTIFICATE_VALIDATION_FAILURE", "CERTIFICATE_KEY_TYPE", "unknown", exception)
                throw SessionSetupException(SecureSessionFailure.INVALID_PEER_CERTIFICATE, exception)
            }
        if (ecKey.params.curve.field.fieldSize != 256) {
            val exception = IllegalArgumentException("Peer EC key is not P-256")
            diagnostics.failure("PEER_CERTIFICATE_VALIDATION_FAILURE", "CERTIFICATE_CURVE", "unknown", exception)
            throw SessionSetupException(SecureSessionFailure.INVALID_PEER_CERTIFICATE, exception)
        }
        return certificate
    }

    private fun createPinnedTlsSocket(
        raw: Socket,
        alias: String,
        identity: EphemeralTlsIdentity,
        expectedPeerFingerprint: ByteArray,
        isGroupOwner: Boolean,
        diagnostics: SecureTlsDiagnostics,
    ): SSLSocket {
        val tlsRole = if (isGroupOwner) "SERVER" else "CLIENT"
        var stage = "TLS_CONTEXT_INITIALIZATION"
        var tlsSocket: SSLSocket? = null
        var handshakeStartedNanos: Long? = null
        try {
            val keyManager = SingleAliasKeyManager(
                alias = alias,
                privateKey = identity.privateKey,
                certificate = identity.certificate,
                diagnostics = diagnostics,
            )
            val trustManager = PinnedSessionTrustManager(expectedPeerFingerprint, diagnostics)
            val context = SSLContext.getInstance("TLS")
            context.init(
                arrayOf<KeyManager>(keyManager),
                arrayOf<TrustManager>(trustManager),
                SecureRandom(),
            )
            diagnostics.event("TLS_CONTEXT_INITIALIZED", "provider=${context.provider.name}")

            stage = "TLS_SOCKET_CREATION"
            val socket = context.socketFactory.createSocket(
                raw,
                raw.inetAddress.hostAddress,
                raw.port,
                false,
            ) as? SSLSocket ?: throw SessionSetupException(SecureSessionFailure.TLS_UNAVAILABLE)
            tlsSocket = socket
            diagnostics.event("TLS_SOCKET_CREATED", "autoCloseUnderlyingSocket=false")

            stage = "TLS_PROTOCOL_SELECTION"
            val supportedProtocols = socket.supportedProtocols.toList()
            val enabledProtocols = listOf("TLSv1.3", "TLSv1.2").filter(supportedProtocols::contains)
            diagnostics.event(
                "TLS_PROTOCOLS",
                "supported=${supportedProtocols.joinToString(",")} enabled=${enabledProtocols.joinToString(",")}",
            )
            diagnostics.event(
                "TLS_CIPHER_SUITES",
                "supported=${socket.supportedCipherSuites.joinToString(",")} " +
                    "enabled=${socket.enabledCipherSuites.joinToString(",")}",
            )
            if ("TLSv1.2" !in enabledProtocols) {
                val exception = IllegalStateException("TLSv1.2 is not supported by the active provider")
                diagnostics.failure("TLS_FAILURE", stage, tlsRole, exception)
                throw SessionSetupException(SecureSessionFailure.TLS_UNAVAILABLE, exception)
            }
            socket.enabledProtocols = enabledProtocols.toTypedArray()

            stage = "TLS_ROLE_CONFIGURATION"
            socket.useClientMode = !isGroupOwner
            if (isGroupOwner) socket.needClientAuth = true
            socket.soTimeout = TLS_HANDSHAKE_TIMEOUT_MILLIS
            val expectedClientMode = !isGroupOwner
            val expectedClientAuth = isGroupOwner
            diagnostics.event(
                "TLS_ROLE_CONFIGURED",
                "groupRole=${if (isGroupOwner) "GROUP_OWNER" else "GROUP_CLIENT"} " +
                    "tlsRole=$tlsRole useClientMode=${socket.useClientMode} " +
                    "needClientAuth=${socket.needClientAuth} " +
                    "roleConfigurationMatches=${socket.useClientMode == expectedClientMode && socket.needClientAuth == expectedClientAuth}",
            )
            if (socket.useClientMode != expectedClientMode || socket.needClientAuth != expectedClientAuth) {
                throw IllegalStateException("The TLS socket did not retain the required role configuration")
            }

            stage = "TLS_HANDSHAKE"
            diagnostics.event("TLS_HANDSHAKE_STARTED", "tlsRole=$tlsRole")
            handshakeStartedNanos = System.nanoTime()
            try {
                socket.startHandshake()
            } catch (exception: Exception) {
                val elapsedMillis = (System.nanoTime() - handshakeStartedNanos) / NANOS_PER_MILLI
                diagnostics.failure("TLS_FAILURE", stage, tlsRole, exception, elapsedMillis)
                throw SessionSetupException(SecureSessionFailure.TLS_HANDSHAKE_FAILED, exception)
            }

            val elapsedMillis = (System.nanoTime() - handshakeStartedNanos) / NANOS_PER_MILLI
            val peer = socket.session.peerCertificates.firstOrNull() as? X509Certificate
                ?: run {
                    val exception = IllegalStateException("The peer did not present an X.509 certificate")
                    diagnostics.failure("TLS_FAILURE", "TLS_PEER_CERTIFICATE", tlsRole, exception, elapsedMillis)
                    throw SessionSetupException(SecureSessionFailure.INVALID_PEER_CERTIFICATE, exception)
                }
            val actualPeerFingerprint = sha256(peer.encoded)
            val pinMatches = MessageDigest.isEqual(actualPeerFingerprint, expectedPeerFingerprint)
            diagnostics.event(
                "TLS_POST_HANDSHAKE_PIN",
                "matched=$pinMatches peerFingerprintPrefix=${SecureTlsDiagnostics.fingerprintPrefix(actualPeerFingerprint)}",
            )
            if (!pinMatches) {
                val exception = java.security.cert.CertificateException("Post-handshake peer certificate pin mismatch")
                diagnostics.failure("TLS_FAILURE", "TLS_POST_HANDSHAKE_PIN", tlsRole, exception, elapsedMillis)
                throw SessionSetupException(SecureSessionFailure.INVALID_PEER_CERTIFICATE, exception)
            }

            diagnostics.event(
                "TLS_HANDSHAKE_COMPLETED",
                "tlsRole=$tlsRole provider=${context.provider.name} " +
                    "protocol=${socket.session.protocol} cipherSuite=${socket.session.cipherSuite} " +
                    "peerCertificateCount=${socket.session.peerCertificates.size} " +
                    "durationMs=$elapsedMillis peerPinVerified=true",
            )
            return socket
        } catch (exception: SessionSetupException) {
            runCatching { tlsSocket?.close() }
            throw exception
        } catch (exception: Exception) {
            val elapsedMillis = handshakeStartedNanos?.let {
                (System.nanoTime() - it) / NANOS_PER_MILLI
            }
            diagnostics.failure("TLS_FAILURE", stage, tlsRole, exception, elapsedMillis)
            runCatching { tlsSocket?.close() }
            val failure = if (stage in setOf(
                    "TLS_CONTEXT_INITIALIZATION",
                    "TLS_SOCKET_CREATION",
                    "TLS_PROTOCOL_SELECTION",
                )
            ) {
                SecureSessionFailure.TLS_UNAVAILABLE
            } else {
                SecureSessionFailure.TLS_HANDSHAKE_FAILED
            }
            throw SessionSetupException(failure, exception)
        }
    }

    private fun notifySessionClose(socket: Socket, sessionId: UUID) {
        runCatching {
            TransferFrameCodec.write(
                TransferFrame(
                    protocolVersion = TransferFrameCodec.CURRENT_PROTOCOL_VERSION,
                    type = TransferFrameType.SESSION_CLOSE,
                    sessionId = sessionId,
                    payload = byteArrayOf(),
                ),
                socket.getOutputStream(),
            )
            socket.getOutputStream().flush()
        }
    }

    private fun sha256(bytes: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(bytes)

    private fun cleanupStaleKeyAliases() {
        runCatching {
            val store = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            java.util.Collections.list(store.aliases())
                .asSequence()
                .filter { it.startsWith(SESSION_KEY_ALIAS_PREFIX) }
                .take(MAX_STALE_KEY_ALIASES_PER_START)
                .forEach(store::deleteEntry)
        }
    }

    private fun deleteKeyAlias(alias: String) {
        runCatching {
            KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }.let { store ->
                if (store.containsAlias(alias)) store.deleteEntry(alias)
            }
        }
    }

    private fun ensureCurrent(expectedGeneration: Long) {
        synchronized(lock) {
            if (generation != expectedGeneration) throw CancellationException("Session replaced")
        }
    }

    private fun publishIfCurrent(expectedGeneration: Long, snapshot: SecureSessionSnapshot) {
        synchronized(lock) {
            if (generation == expectedGeneration) {
                approval = null
                rawSocket = null
                activeKeyAlias = null
                mutableState.value = snapshot
            }
        }
    }

    private fun closeSessionResourcesLocked() {
        closeQuietly(secureSocket)
        closeQuietly(rawSocket)
        secureSocket = null
        rawSocket = null
        activeKeyAlias?.let(::deleteKeyAlias)
        activeKeyAlias = null
    }

    private fun closeQuietly(closeable: AutoCloseable?) {
        runCatching { closeable?.close() }
    }

    private class SessionSetupException(
        val failure: SecureSessionFailure,
        cause: Throwable? = null,
    ) : IOException(failure.name, cause)

    private class PairingRejectedException : IOException("Pairing was rejected")

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val SESSION_KEY_ALIAS_PREFIX = "peyvand_session_"
        const val MAX_STALE_KEY_ALIASES_PER_START = 32
        const val TCP_READY_TIMEOUT_MILLIS = 25_000L
        const val USER_APPROVAL_TIMEOUT_MILLIS = 60_000L
        const val TLS_HANDSHAKE_TIMEOUT_MILLIS = 15_000
        const val NANOS_PER_MILLI = 1_000_000L
    }
}
