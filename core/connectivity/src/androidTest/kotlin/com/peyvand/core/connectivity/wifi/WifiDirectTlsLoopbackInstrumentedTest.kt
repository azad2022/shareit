package com.peyvand.core.connectivity.wifi

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WifiDirectTlsLoopbackInstrumentedTest {
    @Test
    fun mutualTlsWithAndroidKeystoreIdentityNegotiatesTls12() {
        assertMutualTls("TLSv1.2")
    }

    @Test
    fun mutualTlsWithAndroidKeystoreIdentityNegotiatesTls13WhenSupported() {
        assumeTrue("TLS 1.3 is not supported by this Android provider", supportsProtocol("TLSv1.3"))
        assertMutualTls("TLSv1.3")
    }

    @Test
    fun pinnedTrustManagerRejectsAnIncorrectPeerFingerprint() {
        val alias = "peyvand_test_pin_" + UUID.randomUUID().toString().replace("-", "")
        val diagnostics = SecureTlsDiagnostics()
        try {
            val identity = AndroidEphemeralTlsIdentityFactory.create(alias)
            val trustManager = PinnedSessionTrustManager(ByteArray(32) { 0x5a.toByte() }, diagnostics)
            try {
                trustManager.checkServerTrusted(arrayOf(identity.certificate), "EC")
                fail("A mismatched certificate fingerprint must be rejected")
            } catch (_: CertificateException) {
                // Expected: the exact session pin is mandatory.
            }
        } finally {
            AndroidEphemeralTlsIdentityFactory.delete(alias)
        }
    }

    private fun assertMutualTls(protocol: String) {
        val trace = SecureTlsDiagnostics()
        trace.bindSession(UUID.randomUUID())
        val serverAlias = "peyvand_test_server_" + UUID.randomUUID().toString().replace("-", "")
        val clientAlias = "peyvand_test_client_" + UUID.randomUUID().toString().replace("-", "")
        var listener: ServerSocket? = null
        var clientRaw: Socket? = null
        var clientTls: SSLSocket? = null
        val executor = Executors.newSingleThreadExecutor()

        try {
            val serverIdentity = AndroidEphemeralTlsIdentityFactory.create(serverAlias)
            val clientIdentity = AndroidEphemeralTlsIdentityFactory.create(clientAlias)
            val serverContext = createContext(serverAlias, serverIdentity, clientIdentity.certificate, trace)
            val clientContext = createContext(clientAlias, clientIdentity, serverIdentity.certificate, trace)
            val loopback = InetAddress.getByName(LOOPBACK_ADDRESS)
            val activeListener = ServerSocket(0, 1, loopback)
            listener = activeListener
            activeListener.soTimeout = HANDSHAKE_TIMEOUT_MILLIS

            val serverFuture = executor.submit<HandshakeSummary> {
                val accepted = activeListener.accept()
                accepted.soTimeout = HANDSHAKE_TIMEOUT_MILLIS
                val secure = activeServerSocket(
                    serverContext,
                    accepted,
                    activeListener.localPort,
                    protocol,
                )
                try {
                    secure.startHandshake()
                    val session = secure.session
                    val peer = session.peerCertificates.firstOrNull()
                        ?: throw AssertionError("Server did not receive a client certificate")
                    HandshakeSummary(session.protocol, session.cipherSuite, sha256(peer.encoded))
                } finally {
                    runCatching { secure.close() }
                    runCatching { accepted.close() }
                }
            }

            val raw = Socket()
            clientRaw = raw
            raw.soTimeout = HANDSHAKE_TIMEOUT_MILLIS
            raw.connect(InetSocketAddress(loopback, activeListener.localPort), CONNECT_TIMEOUT_MILLIS)
            val secureClient = clientContext.socketFactory.createSocket(
                raw,
                LOOPBACK_ADDRESS,
                activeListener.localPort,
                false,
            ) as SSLSocket
            clientTls = secureClient
            secureClient.enabledProtocols = arrayOf(protocol)
            secureClient.useClientMode = true
            secureClient.soTimeout = HANDSHAKE_TIMEOUT_MILLIS
            secureClient.startHandshake()

            val clientSession = secureClient.session
            val serverCertificate = clientSession.peerCertificates.firstOrNull()
                ?: throw AssertionError("Client did not receive a server certificate")
            val serverSummary = serverFuture.get(30, TimeUnit.SECONDS)

            assertEquals(protocol, clientSession.protocol)
            assertEquals(protocol, serverSummary.protocol)
            assertNotNull(clientSession.cipherSuite)
            assertNotNull(serverSummary.cipherSuite)
            assertArrayEquals(sha256(serverIdentity.certificate.encoded), sha256(serverCertificate.encoded))
            assertArrayEquals(sha256(clientIdentity.certificate.encoded), serverSummary.peerFingerprint)
            trace.event(
                "LOOPBACK_MTLS_TEST_PASSED",
                "protocol=$protocol clientCipher=" + clientSession.cipherSuite +
                    " serverCipher=" + serverSummary.cipherSuite,
            )
        } finally {
            runCatching { clientTls?.close() }
            runCatching { clientRaw?.close() }
            runCatching { listener?.close() }
            executor.shutdownNow()
            executor.awaitTermination(5, TimeUnit.SECONDS)
            AndroidEphemeralTlsIdentityFactory.delete(serverAlias)
            AndroidEphemeralTlsIdentityFactory.delete(clientAlias)
        }
    }

    private fun activeServerSocket(
        context: SSLContext,
        accepted: Socket,
        port: Int,
        protocol: String,
    ): SSLSocket {
        val secure = context.socketFactory.createSocket(accepted, LOOPBACK_ADDRESS, port, false) as SSLSocket
        secure.enabledProtocols = arrayOf(protocol)
        secure.useClientMode = false
        secure.needClientAuth = true
        secure.soTimeout = HANDSHAKE_TIMEOUT_MILLIS
        return secure
    }

    private fun createContext(
        alias: String,
        identity: EphemeralTlsIdentity,
        expectedPeer: java.security.cert.X509Certificate,
        diagnostics: SecureTlsDiagnostics,
    ): SSLContext =
        SSLContext.getInstance("TLS").apply {
            init(
                arrayOf(SingleAliasKeyManager(alias, identity.privateKey, identity.certificate, diagnostics)),
                arrayOf(PinnedSessionTrustManager(sha256(expectedPeer.encoded), diagnostics)),
                SecureRandom(),
            )
        }

    private fun supportsProtocol(protocol: String): Boolean {
        val probe = SSLContext.getInstance("TLS").socketFactory.createSocket() as SSLSocket
        return try {
            protocol in probe.supportedProtocols
        } finally {
            probe.close()
        }
    }

    private fun sha256(bytes: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(bytes)

    private data class HandshakeSummary(
        val protocol: String,
        val cipherSuite: String,
        val peerFingerprint: ByteArray,
    )

    private companion object {
        const val LOOPBACK_ADDRESS = "127.0.0.1"
        const val CONNECT_TIMEOUT_MILLIS = 5_000
        const val HANDSHAKE_TIMEOUT_MILLIS = 15_000
    }
}
