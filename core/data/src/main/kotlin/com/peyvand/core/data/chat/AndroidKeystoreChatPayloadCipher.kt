package com.peyvand.core.data.chat

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal data class EncryptedChatPayload(
    val nonce: ByteArray,
    val ciphertext: ByteArray,
)

class ChatEncryptionKeyUnavailableException(cause: Throwable? = null) :
    IllegalStateException(
        "The local chat encryption key is unavailable. Existing messages were not replaced.",
        cause,
    )

class ChatPayloadIntegrityException(cause: Throwable? = null) :
    SecurityException("The encrypted local chat payload failed authentication.", cause)

class ChatPayloadEncryptionException(cause: Throwable? = null) :
    IllegalStateException("The local chat payload could not be encrypted.", cause)

/**
 * AES-256-GCM payload encryption using a non-exportable Android Keystore key.
 *
 * The Android Keystore provider may use hardware-backed storage when available, but
 * this implementation deliberately makes no hardware-backed or StrongBox guarantee.
 */
class AndroidKeystoreChatPayloadCipher(
    private val keyAlias: String = DEFAULT_KEY_ALIAS,
) {
    init {
        require(keyAlias.isNotBlank())
    }

    fun getOrCreateKey(allowCreate: Boolean): SecretKey = synchronized(KEYSTORE_LOCK) {
        try {
            val store = loadKeyStore()
            val existing = store.getKey(keyAlias, null) as? SecretKey
            if (existing != null) return@synchronized existing

            if (!allowCreate) {
                throw ChatEncryptionKeyUnavailableException()
            }

            val generator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES,
                ANDROID_KEYSTORE_PROVIDER,
            )
            val specification = KeyGenParameterSpec.Builder(
                keyAlias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .setKeySize(256)
                .build()

            generator.init(specification)
            generator.generateKey()
        } catch (exception: ChatEncryptionKeyUnavailableException) {
            throw exception
        } catch (exception: Exception) {
            throw ChatEncryptionKeyUnavailableException(exception)
        }
    }

    internal fun encrypt(
        plaintext: ByteArray,
        associatedData: ByteArray,
        key: SecretKey,
    ): EncryptedChatPayload {
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key)
            cipher.updateAAD(associatedData)
            val ciphertext = cipher.doFinal(plaintext)
            val nonce = cipher.iv
            if (nonce.size != GCM_NONCE_BYTES) {
                throw ChatPayloadEncryptionException()
            }
            return EncryptedChatPayload(nonce = nonce, ciphertext = ciphertext)
        } catch (exception: ChatPayloadEncryptionException) {
            throw exception
        } catch (exception: GeneralSecurityException) {
            throw ChatPayloadEncryptionException(exception)
        }
    }

    internal fun decrypt(
        nonce: ByteArray,
        ciphertext: ByteArray,
        associatedData: ByteArray,
        key: SecretKey,
    ): ByteArray {
        if (nonce.size != GCM_NONCE_BYTES || ciphertext.size < GCM_TAG_BYTES) {
            throw ChatPayloadIntegrityException()
        }

        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, nonce))
            cipher.updateAAD(associatedData)
            return cipher.doFinal(ciphertext)
        } catch (exception: AEADBadTagException) {
            throw ChatPayloadIntegrityException(exception)
        } catch (exception: GeneralSecurityException) {
            throw ChatPayloadIntegrityException(exception)
        }
    }

    private fun loadKeyStore(): KeyStore =
        KeyStore.getInstance(ANDROID_KEYSTORE_PROVIDER).apply { load(null) }

    companion object {
        const val DEFAULT_KEY_ALIAS = "peyvand.local-chat.payload.v1"

        private const val ANDROID_KEYSTORE_PROVIDER = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_NONCE_BYTES = 12
        private const val GCM_TAG_BITS = 128
        private const val GCM_TAG_BYTES = GCM_TAG_BITS / 8
        private val KEYSTORE_LOCK = Any()
    }
}
