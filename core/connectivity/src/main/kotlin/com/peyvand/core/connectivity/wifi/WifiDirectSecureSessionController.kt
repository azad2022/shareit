package com.peyvand.core.connectivity.wifi

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
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
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.Principal
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.math.BigInteger
import java.util.Date
import java.util.UUID
import javax.net.ssl.KeyManager
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509KeyManager
import javax.net.ssl.X509TrustManager
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
        try {
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
            if (channel.status != com.peyvand.core.domain.connection.P2pTcpChannelStatus.CONNECTED) {
                throw SessionSetupException(SecureSessionFailure.IO_ERROR)
            }
            val groupOwner = channel.isGroupOwner
                ?: throw SessionSetupException(SecureSessionFailure.IO_ERROR)
            val activeSocket = rawSocketProvider()
                ?.takeIf { it.isConnected && !it.isClosed }
                ?: throw SessionSetupException(SecureSessionFailure.IO_ERROR)
            localSocket = activeSocket
            synchronized(lock) { rawSocket = activeSocket }

            val localRole = if (connection.locallyInitiated) {
                PeerTransferRole.SENDER
            } else {
                PeerTransferRole.RECEIVER
            }
            val participantRole = when (localRole) {
                PeerTransferRole.SENDER -> PairingParticipantRole.SENDER
                PeerTransferRole.RECEIVER -> PairingParticipantRole.RECEIVER
            }

            val sessionId: UUID
            val remoteHello: PairingBootstrapHello
            val keyAlias = "peyvand_session_${UUID.randomUUID().toString().replace("-", "")}"
            alias = keyAlias

            synchronized(lock) { activeKeyAlias = keyAlias }
            val identity = createEphemeralIdentity(keyAlias)
            val localNonce = ByteArray(PairingBootstrapHello.NONCE_BYTES).also {
                SecureRandom().nextBytes(it)
            }

            if (groupOwner) {
                sessionId = UUID.randomUUID()
                val localHello = PairingBootstrapHello(
                    sessionId = sessionId,
                    role = participantRole,
                    nonce = localNonce,
                    certificateDer = identity.certificate.encoded,
                )
                PairingBootstrapCodec.write(localHello, activeSocket.getOutputStream())
                remoteHello = PairingBootstrapCodec.read(activeSocket.getInputStream())
            } else {
                remoteHello = PairingBootstrapCodec.read(activeSocket.getInputStream())
                sessionId = remoteHello.sessionId
                val localHello = PairingBootstrapHello(
                    sessionId = sessionId,
                    role = participantRole,
                    nonce = localNonce,
                    certificateDer = identity.certificate.encoded,
                )
                PairingBootstrapCodec.write(localHello, activeSocket.getOutputStream())
            }
            ensureCurrent(expectedGeneration)

            if (remoteHello.sessionId != sessionId || remoteHello.role == participantRole) {
                throw SessionSetupException(SecureSessionFailure.PARTICIPANT_ROLE_MISMATCH)
            }
            val peerCertificate = parseAndValidatePeerCertificate(remoteHello.certificateBytes())
            val localFingerprint = sha256(identity.certificate.encoded)
            val peerFingerprint = sha256(peerCertificate.encoded)
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
                throw SessionSetupException(SecureSessionFailure.USER_APPROVAL_TIMEOUT)
            }
            ensureCurrent(expectedGeneration)
            if (!approved) throw PairingRejectedException()

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
            if (remoteApproval.type != TransferFrameType.PAIRING_APPROVED ||
                remoteApproval.sessionId != sessionId ||
                remoteApproval.payloadSize != 0
            ) {
                throw SessionSetupException(SecureSessionFailure.PROTOCOL_ERROR)
            }

            ensureCurrent(expectedGeneration)
            mutableState.value = SecureSessionSnapshot(SecureSessionStatus.TLS_HANDSHAKING)
            tlsSocket = createPinnedTlsSocket(
                raw = activeSocket,
                alias = keyAlias,
                identity = identity,
                expectedPeerFingerprint = peerFingerprint,
                isGroupOwner = groupOwner,
            )
            ensureCurrent(expectedGeneration)
            synchronized(lock) {
                if (generation != expectedGeneration) throw CancellationException()
                secureSocket = tlsSocket
                rawSocket = null
                approval = null
                mutableState.value = SecureSessionSnapshot(SecureSessionStatus.AUTHENTICATED)
            }
        } catch (exception: CancellationException) {
            closeQuietly(tlsSocket)
            closeQuietly(localSocket)
            alias?.let(::deleteKeyAlias)
            throw exception
        } catch (_: PairingRejectedException) {
            closeQuietly(tlsSocket)
            closeQuietly(localSocket)
            alias?.let(::deleteKeyAlias)
            publishIfCurrent(expectedGeneration, SecureSessionSnapshot(SecureSessionStatus.REJECTED))
        } catch (exception: SessionSetupException) {
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
        } catch (_: java.security.GeneralSecurityException) {
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
        } catch (_: IOException) {
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
        } catch (_: RuntimeException) {
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

    private fun createEphemeralIdentity(alias: String): EphemeralIdentity {
        val now = System.currentTimeMillis()
        val store = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        if (store.containsAlias(alias)) store.deleteEntry(alias)
        val generator = KeyPairGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_EC,
            ANDROID_KEYSTORE,
        )
        generator.initialize(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY,
            )
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256)
                .setCertificateSubject(javax.security.auth.x500.X500Principal("CN=PEYVAND Session"))
                .setCertificateSerialNumber(BigInteger(128, SecureRandom()).abs().max(BigInteger.ONE))
                .setCertificateNotBefore(Date(now - CERTIFICATE_CLOCK_SKEW_MILLIS))
                .setCertificateNotAfter(Date(now + CERTIFICATE_LIFETIME_MILLIS))
                .build(),
        )
        generator.generateKeyPair()
        val refreshedStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val certificate = refreshedStore.getCertificate(alias) as? X509Certificate
            ?: throw SessionSetupException(SecureSessionFailure.KEYSTORE_ERROR)
        certificate.checkValidity()
        certificate.verify(certificate.publicKey)
        val privateKey = refreshedStore.getKey(alias, null) as? PrivateKey
            ?: throw SessionSetupException(SecureSessionFailure.KEYSTORE_ERROR)
        return EphemeralIdentity(privateKey, certificate)
    }

    private fun parseAndValidatePeerCertificate(encoded: ByteArray): X509Certificate {
        if (encoded.size !in 1..PairingBootstrapHello.MAX_CERTIFICATE_BYTES) {
            throw SessionSetupException(SecureSessionFailure.INVALID_PEER_CERTIFICATE)
        }
        val certificate = try {
            CertificateFactory.getInstance("X.509")
                .generateCertificate(ByteArrayInputStream(encoded)) as X509Certificate
        } catch (_: Exception) {
            throw SessionSetupException(SecureSessionFailure.INVALID_PEER_CERTIFICATE)
        }
        try {
            certificate.checkValidity()
            certificate.verify(certificate.publicKey)
        } catch (_: Exception) {
            throw SessionSetupException(SecureSessionFailure.INVALID_PEER_CERTIFICATE)
        }
        val ecKey = certificate.publicKey as? ECPublicKey
            ?: throw SessionSetupException(SecureSessionFailure.INVALID_PEER_CERTIFICATE)
        if (ecKey.params.curve.field.fieldSize != 256) {
            throw SessionSetupException(SecureSessionFailure.INVALID_PEER_CERTIFICATE)
        }
        return certificate
    }

    private fun createPinnedTlsSocket(
        raw: Socket,
        alias: String,
        identity: EphemeralIdentity,
        expectedPeerFingerprint: ByteArray,
        isGroupOwner: Boolean,
    ): SSLSocket {
        val keyManager = SingleAliasKeyManager(alias, identity.privateKey, identity.certificate)
        val trustManager = PinnedSessionTrustManager(expectedPeerFingerprint)
        val context = SSLContext.getInstance("TLS")
        context.init(
            arrayOf<KeyManager>(keyManager),
            arrayOf<TrustManager>(trustManager),
            SecureRandom(),
        )
        val socket = context.socketFactory.createSocket(
            raw,
            raw.inetAddress.hostAddress,
            raw.port,
            false,
        ) as? SSLSocket ?: throw SessionSetupException(SecureSessionFailure.TLS_UNAVAILABLE)
        val supported = socket.supportedProtocols.toSet()
        val enabled = listOf("TLSv1.3", "TLSv1.2").filter(supported::contains)
        if ("TLSv1.2" !in enabled) {
            closeQuietly(socket)
            throw SessionSetupException(SecureSessionFailure.TLS_UNAVAILABLE)
        }
        socket.enabledProtocols = enabled.toTypedArray()
        socket.useClientMode = !isGroupOwner
        if (isGroupOwner) socket.needClientAuth = true
        socket.soTimeout = TLS_HANDSHAKE_TIMEOUT_MILLIS
        try {
            socket.startHandshake()
        } catch (exception: Exception) {
            closeQuietly(socket)
            throw SessionSetupException(SecureSessionFailure.TLS_HANDSHAKE_FAILED, exception)
        }
        val peer = socket.session.peerCertificates.firstOrNull() as? X509Certificate
            ?: run {
                closeQuietly(socket)
                throw SessionSetupException(SecureSessionFailure.INVALID_PEER_CERTIFICATE)
            }
        if (!MessageDigest.isEqual(sha256(peer.encoded), expectedPeerFingerprint)) {
            closeQuietly(socket)
            throw SessionSetupException(SecureSessionFailure.INVALID_PEER_CERTIFICATE)
        }
        return socket
    }

    private fun sha256(bytes: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(bytes)

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

    private data class EphemeralIdentity(
        val privateKey: PrivateKey,
        val certificate: X509Certificate,
    )

    private class SingleAliasKeyManager(
        private val alias: String,
        private val privateKey: PrivateKey,
        private val certificate: X509Certificate,
    ) : X509KeyManager {
        override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? =
            if (supportsEc(keyType)) arrayOf(alias) else null

        override fun chooseClientAlias(
            keyType: Array<out String>?,
            issuers: Array<out Principal>?,
            socket: Socket?,
        ): String? = keyType?.firstOrNull(::supportsEc)?.let { alias }

        override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? =
            if (supportsEc(keyType)) arrayOf(alias) else null

        override fun chooseServerAlias(
            keyType: String?,
            issuers: Array<out Principal>?,
            socket: Socket?,
        ): String? = alias.takeIf { supportsEc(keyType) }

        override fun getCertificateChain(alias: String?): Array<X509Certificate>? =
            if (alias == this.alias) arrayOf(certificate) else null

        override fun getPrivateKey(alias: String?): PrivateKey? =
            if (alias == this.alias) privateKey else null

        private fun supportsEc(value: String?): Boolean =
            value.equals("EC", ignoreCase = true) ||
                value.equals("ECDSA", ignoreCase = true)
    }

    private class PinnedSessionTrustManager(
        expectedFingerprint: ByteArray,
    ) : X509TrustManager {
        private val pin = expectedFingerprint.copyOf()

        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) =
            verifyPeer(chain)

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) =
            verifyPeer(chain)

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()

        private fun verifyPeer(chain: Array<out X509Certificate>?) {
            if (chain == null || chain.size != 1) {
                throw java.security.cert.CertificateException("Unexpected peer certificate chain")
            }
            val certificate = chain[0]
            try {
                certificate.checkValidity()
                certificate.verify(certificate.publicKey)
            } catch (exception: Exception) {
                throw java.security.cert.CertificateException("Invalid self-signed peer certificate", exception)
            }
            if (!MessageDigest.isEqual(
                    MessageDigest.getInstance("SHA-256").digest(certificate.encoded),
                    pin,
                )
            ) {
                throw java.security.cert.CertificateException("Peer certificate pin mismatch")
            }
        }
    }

    private class SessionSetupException(
        val failure: SecureSessionFailure,
        cause: Throwable? = null,
    ) : IOException(failure.name, cause)

    private class PairingRejectedException : IOException("Pairing was rejected")

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TCP_READY_TIMEOUT_MILLIS = 25_000L
        const val USER_APPROVAL_TIMEOUT_MILLIS = 60_000L
        const val CERTIFICATE_CLOCK_SKEW_MILLIS = 60_000L
        const val CERTIFICATE_LIFETIME_MILLIS = 10 * 60_000L
        const val TLS_HANDSHAKE_TIMEOUT_MILLIS = 15_000
    }
}
