package com.peyvand.core.data.profile

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.peyvand.core.data.chat.ChatDatabase
import com.peyvand.core.data.chat.ChatDatabaseFactory
import com.peyvand.core.data.chat.RoomTrustedPeerRepository
import com.peyvand.core.domain.chat.PeerIdentityId
import com.peyvand.core.domain.chat.TrustedPeerIdentity
import com.peyvand.core.domain.chat.TrustedPeerProfileConflictException
import java.io.ByteArrayOutputStream
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.spec.ECGenParameterSpec
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DeviceProfileRepositoryTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val databaseName = "device-profile-test-" + UUID.randomUUID() + ".db"
    private lateinit var database: ChatDatabase
    private lateinit var repository: RoomDeviceProfileRepository

    @Before
    fun setUp() {
        openDatabase()
    }

    @After
    fun tearDown() {
        if (::database.isInitialized) database.close()
        context.deleteDatabase(databaseName)
    }

    @Test
    fun localProfilePersistsAndIncrementsRevisionOnlyWhenChanged() = runBlocking {
        val avatar = createCanonicalAvatar()
        val first = repository.saveLocalProfile(
            deviceNameOverride = "گوشی آزاد",
            profileName = "آزاد",
            avatarBytes = avatar,
            shareWithTrustedPeers = true,
        )
        assertEquals(1L, first.revision)

        val unchanged = repository.saveLocalProfile(
            deviceNameOverride = "گوشی آزاد",
            profileName = "آزاد",
            avatarBytes = avatar.copyOf(),
            shareWithTrustedPeers = true,
        )
        assertEquals(1L, unchanged.revision)

        database.close()
        openDatabase()
        val restored = repository.getLocalProfile()
        assertEquals("گوشی آزاد", restored.deviceNameOverride)
        assertEquals("آزاد", restored.profileName)
        assertEquals(1L, restored.revision)
        assertArrayEquals(avatar, restored.avatarBytes)

        val cleared = repository.saveLocalProfile(
            deviceNameOverride = "",
            profileName = "",
            avatarBytes = null,
            shareWithTrustedPeers = false,
        )
        assertNull(cleared.deviceNameOverride)
        assertNull(cleared.profileName)
        assertNull(cleared.avatarBytes)
        assertEquals(2L, cleared.revision)
    }

    @Test
    fun trustedPeerProfileIsBoundToPersistentFingerprintAndRejectsStaleOrConflictingRevisions() =
        runBlocking {
            val publicKey = generatePeerPublicKey()
            val identityId = PeerIdentityId.fromSha256Fingerprint(sha256Hex(publicKey))
            val trustedPeers = RoomTrustedPeerRepository(database)
            val avatar = createCanonicalAvatar()
            val first = TrustedPeerIdentity(
                identityId = identityId,
                publicKeyEncoded = publicKey,
                displayName = "Galaxy A12",
                firstSeenAtEpochMillis = 100L,
                lastAuthenticatedAtEpochMillis = 200L,
                profileName = "آزاد",
                profileAvatarHash = sha256Hex(avatar),
                profileAvatarBytes = avatar,
                profileRevision = 2L,
            )
            trustedPeers.recordAuthenticatedPeer(first)

            val stale = trustedPeers.recordAuthenticatedPeer(
                first.copy(
                    displayName = "Old device label",
                    profileName = "نام قدیمی",
                    profileAvatarHash = null,
                    profileAvatarBytes = null,
                    profileRevision = 1L,
                    lastAuthenticatedAtEpochMillis = 300L,
                ),
            )
            assertEquals("Galaxy A12", stale.displayName)
            assertEquals("آزاد", stale.profileName)
            assertArrayEquals(avatar, stale.profileAvatarBytes)
            assertEquals(2L, stale.profileRevision)
            assertEquals(300L, stale.lastAuthenticatedAtEpochMillis)

            org.junit.Assert.assertThrows(TrustedPeerProfileConflictException::class.java) {
                runBlocking {
                    trustedPeers.recordAuthenticatedPeer(
                        first.copy(
                            displayName = "Conflicting same-version label",
                            lastAuthenticatedAtEpochMillis = 400L,
                        ),
                    )
                }
            }
        }

    @Test
    fun localProfileFlowPublishesLatestCommittedRevision() = runBlocking {
        repository.saveLocalProfile(
            deviceNameOverride = "Device",
            profileName = null,
            avatarBytes = null,
            shareWithTrustedPeers = true,
        )
        val observed = repository.observeLocalProfile().first()
        assertEquals("Device", observed.deviceNameOverride)
        assertEquals(1L, observed.revision)
    }

    private fun openDatabase() {
        database = ChatDatabaseFactory.open(context, databaseName)
        repository = RoomDeviceProfileRepository(context, database)
    }

    private fun createCanonicalAvatar(): ByteArray {
        val bitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
        return try {
            val output = ByteArrayOutputStream()
            check(bitmap.compress(Bitmap.CompressFormat.JPEG, 70, output))
            output.toByteArray()
        } finally {
            bitmap.recycle()
        }
    }

    private fun generatePeerPublicKey(): ByteArray {
        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(ECGenParameterSpec("secp256r1"))
        return generator.generateKeyPair().public.encoded
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
            "%02x".format(it)
        }
}
