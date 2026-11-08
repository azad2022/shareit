package com.peyvand.core.storage

import org.junit.Assert.assertEquals
import org.junit.Test

class TransferMimeTypeResolverTest {
    @Test
    fun wildcardProviderMimeFallsBackToFileExtension() {
        assertEquals(
            "image/jpeg",
            TransferMimeTypeResolver.resolve(
                providerMimeType = "image/*",
                fallbackMimeType = "application/octet-stream",
                extensionMimeType = "image/jpeg",
            ),
        )
    }

    @Test
    fun genericProviderMimeDoesNotOverridePdfExtension() {
        assertEquals(
            "application/pdf",
            TransferMimeTypeResolver.resolve(
                providerMimeType = "application/octet-stream",
                fallbackMimeType = "application/octet-stream",
                extensionMimeType = "application/pdf",
            ),
        )
    }

    @Test
    fun specificProviderMimeWinsOverConflictingExtension() {
        assertEquals(
            "image/png",
            TransferMimeTypeResolver.resolve(
                providerMimeType = "IMAGE/PNG; charset=binary",
                fallbackMimeType = null,
                extensionMimeType = "image/jpeg",
            ),
        )
    }

    @Test
    fun invalidProviderValuesAreIgnoredAndUnknownTypesRemainGeneric() {
        assertEquals(
            "application/octet-stream",
            TransferMimeTypeResolver.resolve(
                providerMimeType = "not-a-mime-type",
                fallbackMimeType = "application/octet-stream",
                extensionMimeType = null,
            ),
        )
    }

    @Test
    fun legacyJpegProviderMimeIsCanonicalizedForSafPublication() {
        assertEquals(
            "image/jpeg",
            TransferMimeTypeResolver.resolve(
                providerMimeType = "image/jpg",
                fallbackMimeType = "application/octet-stream",
                extensionMimeType = "image/jpeg",
            ),
        )
    }

    @Test
    fun progressiveJpegProviderMimeIsCanonicalized() {
        assertEquals(
            "image/jpeg",
            TransferMimeTypeResolver.resolve(
                providerMimeType = "image/pjpeg",
                fallbackMimeType = null,
                extensionMimeType = null,
            ),
        )
    }

    @Test
    fun apkExtensionCanSupplyItsConcreteMimeType() {
        assertEquals(
            "application/vnd.android.package-archive",
            TransferMimeTypeResolver.resolve(
                providerMimeType = null,
                fallbackMimeType = null,
                extensionMimeType = "application/vnd.android.package-archive",
            ),
        )
    }
}
