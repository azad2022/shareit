package com.peyvand.core.data.chat

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.peyvand.core.domain.chat.LocalPeerIdentity
import com.peyvand.core.domain.chat.LocalPeerIdentityUnavailableException
import com.peyvand.core.domain.chat.PeerIdentityKeyProvider
import com.peyvand.core.domain.chat.TrustedPeerRepository
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/** Stable P-256 signing identity, protected by the Android Keystore. */
class AndroidKeystorePeerIdentityKeyProvider(
    private val trustedPeers: TrustedPeerRepository,
    private val keyAlias: String = DEFAULT_KEY_ALIAS,
) : PeerIdentityKeyProvider {
    init {
        require(keyAlias.isNotBlank())
    }

    override suspend fun getOrCreateIdentity(): LocalPeerIdentity = withContext(Dispatchers.IO) {
        // If the key disappeared after peers were paired, silently minting a new identity
        // would make this installation appear to be a different device to every peer.
        // Read trust state before entering the Keystore critical section. The alias is
        // checked again under the lock, so a key disappearing between checks still fails closed.
        val trustedPeersExist = trustedPeers.observeTrustedPeers().first().isNotEmpty()

        val certificate = synchronized(KEYSTORE_LOCK) {
            if (!loadKeyStore().containsAlias(keyAlias)) {
                if (trustedPeersExist) {
                    throw LocalPeerIdentityUnavailableException()
                }
                val generator = KeyPairGenerator.getInstance(KEY_ALGORITHM, ANDROID_KEYSTORE)
                generator.initialize(
                    KeyGenParameterSpec.Builder(
                        keyAlias,
                        KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY,
                    )
                        .setAlgorithmParameterSpec(ECGenParameterSpec(CURVE))
                        .setDigests(KeyProperties.DIGEST_SHA256)
                        .setUserAuthenticationRequired(false)
                        .build(),
                )
                generator.generateKeyPair()
            }
            loadKeyStore().getCertificate(keyAlias)
                ?: error("The persistent PEYVAND identity certificate is unavailable.")
        }

        val publicKey = certificate.publicKey.encoded
            ?: error("The persistent PEYVAND public key is not exportable.")
        LocalPeerIdentity(
            publicKeyEncoded = publicKey.copyOf(),
            fingerprintHex = sha256Hex(publicKey),
        )
    }

    override suspend fun signIdentityProof(payload: ByteArray): ByteArray =
        withContext(Dispatchers.IO) {
            require(payload.isNotEmpty() && payload.size <= MAX_PROOF_BYTES)
            val store = loadKeyStore()
            val privateKey = store.getKey(keyAlias, null) as? java.security.PrivateKey
                ?: error("The persistent PEYVAND identity key is unavailable.")
            Signature.getInstance(SIGNATURE_ALGORITHM).run {
                initSign(privateKey)
                update(payload)
                sign()
            }
        }

    private fun loadKeyStore(): KeyStore =
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
            "%02x".format(it)
        }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val DEFAULT_KEY_ALIAS = "peyvand_persistent_peer_identity_v1"
        const val KEY_ALGORITHM = "EC"
        const val CURVE = "secp256r1"
        const val SIGNATURE_ALGORITHM = "SHA256withECDSA"
        const val MAX_PROOF_BYTES = 4096
        val KEYSTORE_LOCK = Any()
    }
}
