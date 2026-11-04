package com.peyvand.core.connectivity.wifi

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import java.math.BigInteger
import java.net.Socket
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.Principal
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.util.Date
import java.util.UUID
import javax.net.ssl.X509KeyManager
import javax.net.ssl.X509TrustManager
import javax.security.auth.x500.X500Principal

internal data class EphemeralTlsIdentity(
    val privateKey: PrivateKey,
    val certificate: X509Certificate,
)

internal object AndroidEphemeralTlsIdentityFactory {
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val CERTIFICATE_CLOCK_SKEW_MILLIS = 60_000L
    private const val CERTIFICATE_LIFETIME_MILLIS = 10 * 60_000L

    fun create(alias: String): EphemeralTlsIdentity {
        val now = System.currentTimeMillis()
        val store = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        if (store.containsAlias(alias)) store.deleteEntry(alias)
        val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEYSTORE)
        generator.initialize(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY,
            )
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256)
                .setCertificateSubject(X500Principal("CN=PEYVAND Session"))
                .setCertificateSerialNumber(BigInteger(128, SecureRandom()).abs().max(BigInteger.ONE))
                .setCertificateNotBefore(Date(now - CERTIFICATE_CLOCK_SKEW_MILLIS))
                .setCertificateNotAfter(Date(now + CERTIFICATE_LIFETIME_MILLIS))
                .build(),
        )
        generator.generateKeyPair()
        val refreshedStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val certificate = refreshedStore.getCertificate(alias) as? X509Certificate
            ?: throw IllegalStateException("Android Keystore did not return an X.509 certificate")
        certificate.checkValidity()
        certificate.verify(certificate.publicKey)
        val privateKey = refreshedStore.getKey(alias, null) as? PrivateKey
            ?: throw IllegalStateException("Android Keystore did not return the session private key")
        return EphemeralTlsIdentity(privateKey, certificate)
    }

    fun delete(alias: String) {
        runCatching {
            KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }.let { store ->
                if (store.containsAlias(alias)) store.deleteEntry(alias)
            }
        }
    }
}

/** Logs safe per-attempt TLS diagnostics; never pass SAS, nonce, key, raw certificate or file data. */
internal class SecureTlsDiagnostics {
    private val traceId: UUID = UUID.randomUUID()
    @Volatile private var sessionId: UUID? = null

    fun bindSession(value: UUID) {
        sessionId = value
        event("SESSION_BOUND")
    }

    fun event(name: String, details: String = "") {
        val suffix = details
            .replace('\r', ' ')
            .replace('\n', ' ')
            .replace('\t', ' ')
            .take(MAX_EVENT_DETAIL_CHARS)
        val line = "event=$name traceId=$traceId sessionId=${sessionId ?: "pending"}"
        Log.i(TAG, if (suffix.isBlank()) line else "$line $suffix")
    }

    fun deviceContext(groupRole: String, productRole: String) {
        event(
            "DEVICE_CONTEXT",
            "manufacturer=${safe(Build.MANUFACTURER)} model=${safe(Build.MODEL)} " +
                "sdk=${Build.VERSION.SDK_INT} release=${safe(Build.VERSION.RELEASE)} " +
                "build=${safe(Build.DISPLAY)} groupRole=$groupRole productRole=$productRole",
        )
    }

    fun failure(
        eventName: String,
        stage: String,
        tlsRole: String,
        exception: Throwable,
        elapsedMillis: Long? = null,
    ) {
        Log.e(
            TAG,
            "event=$eventName traceId=$traceId sessionId=${sessionId ?: "pending"} " +
                "stage=$stage tlsRole=$tlsRole exceptionClass=${exception.javaClass.name} " +
                "message=${safe(exception.message)} durationMs=${elapsedMillis ?: "unknown"}",
        )
        val seen = java.util.Collections.newSetFromMap(
            java.util.IdentityHashMap<Throwable, Boolean>(),
        )
        var cause: Throwable? = exception
        var causeIndex = 0
        while (cause != null && causeIndex < MAX_CAUSE_DEPTH && seen.add(cause)) {
            Log.e(
                TAG,
                "event=EXCEPTION_CAUSE traceId=$traceId sessionId=${sessionId ?: "pending"} " +
                    "index=$causeIndex exceptionClass=${cause.javaClass.name} message=${safe(cause.message)}",
            )
            cause = cause.cause
            causeIndex += 1
        }
        val stackTrace = Log.getStackTraceString(exception)
        if (stackTrace.isNotBlank()) {
            stackTrace.chunked(STACK_TRACE_CHUNK_CHARS).forEachIndexed { index, chunk ->
                Log.e(
                    TAG,
                    "event=STACK_TRACE traceId=$traceId sessionId=${sessionId ?: "pending"} part=$index $chunk",
                )
            }
        }
    }

    companion object {
        const val TAG = "PeyvandSecureTLS"
        private const val MAX_EVENT_DETAIL_CHARS = 2_800
        private const val MAX_CAUSE_DEPTH = 12
        private const val STACK_TRACE_CHUNK_CHARS = 2_800

        fun fingerprintPrefix(fingerprint: ByteArray): String =
            fingerprint.take(6).joinToString(separator = "") { byte ->
                "%02x".format(byte.toInt() and 0xff)
            }

        private fun safe(value: String?): String =
            value.orEmpty()
                .replace('\r', ' ')
                .replace('\n', ' ')
                .replace('\t', ' ')
                .take(256)
    }
}

internal class SingleAliasKeyManager(
    private val alias: String,
    private val privateKey: PrivateKey,
    private val certificate: X509Certificate,
    private val diagnostics: SecureTlsDiagnostics,
) : X509KeyManager {
    override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? {
        val available = supportsEc(keyType)
        diagnostics.event(
            "KEY_MANAGER_ALIAS_QUERY",
            "callback=getClientAliases keyTypes=${keyType ?: "null"} " +
                "issuerCount=${issuers?.size ?: -1} aliasAvailable=$available",
        )
        return if (available) arrayOf(alias) else null
    }

    override fun chooseClientAlias(
        keyType: Array<out String>?,
        issuers: Array<out Principal>?,
        socket: Socket?,
    ): String? {
        val selectedType = keyType?.firstOrNull(::supportsEc)
        val selectedAlias = alias.takeIf { selectedType != null }
        diagnostics.event(
            "KEY_MANAGER_ALIAS_SELECTED",
            "callback=chooseClientAlias requestedKeyTypes=${keyType?.joinToString(",") ?: "null"} " +
                "issuerCount=${issuers?.size ?: -1} selectedKeyType=${selectedType ?: "none"} " +
                "aliasSelected=${selectedAlias != null}",
        )
        return selectedAlias
    }

    override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? {
        val available = supportsEc(keyType)
        diagnostics.event(
            "KEY_MANAGER_ALIAS_QUERY",
            "callback=getServerAliases keyTypes=${keyType ?: "null"} " +
                "issuerCount=${issuers?.size ?: -1} aliasAvailable=$available",
        )
        return if (available) arrayOf(alias) else null
    }

    override fun chooseServerAlias(
        keyType: String?,
        issuers: Array<out Principal>?,
        socket: Socket?,
    ): String? {
        val selectedAlias = alias.takeIf { supportsEc(keyType) }
        diagnostics.event(
            "KEY_MANAGER_ALIAS_SELECTED",
            "callback=chooseServerAlias requestedKeyType=${keyType ?: "null"} " +
                "issuerCount=${issuers?.size ?: -1} aliasSelected=${selectedAlias != null}",
        )
        return selectedAlias
    }

    override fun getCertificateChain(alias: String?): Array<X509Certificate>? {
        val matches = alias == this.alias
        diagnostics.event(
            "KEY_MANAGER_MATERIAL",
            "material=certificateChain aliasMatched=$matches chainCount=${if (matches) 1 else 0}",
        )
        return if (matches) arrayOf(certificate) else null
    }

    override fun getPrivateKey(alias: String?): PrivateKey? {
        val matches = alias == this.alias
        diagnostics.event(
            "KEY_MANAGER_MATERIAL",
            "material=privateKey aliasMatched=$matches keyAvailable=$matches",
        )
        return if (matches) privateKey else null
    }

    private fun supportsEc(value: String?): Boolean =
        value.equals("EC", ignoreCase = true) ||
            value.equals("ECDSA", ignoreCase = true)
}

internal class PinnedSessionTrustManager(
    expectedFingerprint: ByteArray,
    private val diagnostics: SecureTlsDiagnostics,
) : X509TrustManager {
    private val pin = expectedFingerprint.copyOf()

    init {
        require(pin.size == 32) { "Peer certificate pin must be a SHA-256 digest" }
    }

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) =
        verifyPeer("checkClientTrusted", chain, authType)

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) =
        verifyPeer("checkServerTrusted", chain, authType)

    override fun getAcceptedIssuers(): Array<X509Certificate> {
        diagnostics.event("TRUST_MANAGER_ACCEPTED_ISSUERS", "issuerCount=0")
        return emptyArray()
    }

    private fun verifyPeer(
        callback: String,
        chain: Array<out X509Certificate>?,
        authType: String?,
    ) {
        diagnostics.event(
            "TRUST_MANAGER_VALIDATION",
            "callback=$callback outcome=started authType=${authType ?: "null"} " +
                "chainCount=${chain?.size ?: -1}",
        )
        if (chain == null || chain.size != 1) {
            val exception = CertificateException("Unexpected peer certificate chain")
            diagnostics.failure("TRUST_MANAGER_FAILURE", callback, "unknown", exception)
            throw exception
        }

        val certificate = chain[0]
        try {
            certificate.checkValidity()
            diagnostics.event("TRUST_MANAGER_CERT_VALIDITY", "callback=$callback result=valid")
        } catch (exception: Exception) {
            diagnostics.failure("TRUST_MANAGER_FAILURE", "$callback/certificateValidity", "unknown", exception)
            throw CertificateException("Invalid peer certificate validity", exception)
        }

        try {
            certificate.verify(certificate.publicKey)
            diagnostics.event("TRUST_MANAGER_SELF_SIGNATURE", "callback=$callback result=valid")
        } catch (exception: Exception) {
            diagnostics.failure("TRUST_MANAGER_FAILURE", "$callback/selfSignature", "unknown", exception)
            throw CertificateException("Invalid self-signed peer certificate", exception)
        }

        val actualFingerprint = MessageDigest.getInstance("SHA-256").digest(certificate.encoded)
        val pinMatches = MessageDigest.isEqual(actualFingerprint, pin)
        diagnostics.event(
            "TRUST_MANAGER_PIN",
            "callback=$callback matched=$pinMatches " +
                "expectedPrefix=${SecureTlsDiagnostics.fingerprintPrefix(pin)} " +
                "actualPrefix=${SecureTlsDiagnostics.fingerprintPrefix(actualFingerprint)}",
        )
        if (!pinMatches) {
            val exception = CertificateException("Peer certificate pin mismatch")
            diagnostics.failure("TRUST_MANAGER_FAILURE", "$callback/pin", "unknown", exception)
            throw exception
        }
        diagnostics.event("TRUST_MANAGER_VALIDATION", "callback=$callback outcome=accepted")
    }
}
