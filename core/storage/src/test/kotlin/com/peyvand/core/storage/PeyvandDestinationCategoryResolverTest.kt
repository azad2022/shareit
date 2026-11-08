package com.peyvand.core.storage

import org.junit.Assert.assertEquals
import org.junit.Test

class PeyvandDestinationCategoryResolverTest {
    @Test
    fun imagesAndVideosUseMediaStoreCompatiblePublicRoots() {
        assertEquals(
            PeyvandDestinationCategory("Pictures/PEYVAND/Images", PeyvandMediaCollection.IMAGES),
            PeyvandDestinationCategoryResolver.resolve("photo.jpg", "image/jpeg"),
        )
        assertEquals(
            PeyvandDestinationCategory("Pictures/PEYVAND/Images", PeyvandMediaCollection.IMAGES),
            PeyvandDestinationCategoryResolver.resolve("photo.jpg", "image/jpg"),
        )
        assertEquals(
            PeyvandDestinationCategory("Movies/PEYVAND/Videos", PeyvandMediaCollection.VIDEOS),
            PeyvandDestinationCategoryResolver.resolve("clip.mp4", "video/mp4"),
        )
    }

    @Test
    fun audioUsesMusicRootAndAvoidsGuessingAmbiguousVoiceContent() {
        assertEquals(
            PeyvandDestinationCategory("Music/PEYVAND/Audio/Music", PeyvandMediaCollection.AUDIO),
            PeyvandDestinationCategoryResolver.resolve("track.mp3", "audio/mpeg"),
        )
        assertEquals(
            PeyvandDestinationCategory("Music/PEYVAND/Audio/Voice", PeyvandMediaCollection.AUDIO),
            PeyvandDestinationCategoryResolver.resolve("memo.amr", "audio/amr"),
        )
        assertEquals(
            PeyvandDestinationCategory("Music/PEYVAND/Audio/Other", PeyvandMediaCollection.AUDIO),
            PeyvandDestinationCategoryResolver.resolve("spoken.m4a", "audio/mp4"),
        )
    }

    @Test
    fun documentsAppsAndUnknownItemsUseDownloadsRoot() {
        assertEquals(
            PeyvandDestinationCategory("Download/PEYVAND/Documents", PeyvandMediaCollection.DOWNLOADS),
            PeyvandDestinationCategoryResolver.resolve("report.pdf", "application/pdf"),
        )
        assertEquals(
            PeyvandDestinationCategory("Download/PEYVAND/Apps", PeyvandMediaCollection.DOWNLOADS),
            PeyvandDestinationCategoryResolver.resolve(
                "sample.apk",
                "application/vnd.android.package-archive",
            ),
        )
        assertEquals(
            PeyvandDestinationCategory("Download/PEYVAND/Other", PeyvandMediaCollection.DOWNLOADS),
            PeyvandDestinationCategoryResolver.resolve("archive.zip", "application/zip"),
        )
    }

    @Test
    fun explicitMimeOverridesConflictingExtension() {
        assertEquals(
            PeyvandDestinationCategory("Download/PEYVAND/Documents", PeyvandMediaCollection.DOWNLOADS),
            PeyvandDestinationCategoryResolver.resolve("photo.jpg", "application/pdf"),
        )
        assertEquals(
            PeyvandDestinationCategory("Download/PEYVAND/Other", PeyvandMediaCollection.DOWNLOADS),
            PeyvandDestinationCategoryResolver.resolve(
                "installer.apk",
                "application/zip",
            ),
        )
    }

    @Test
    fun genericMimeFallsBackToKnownExtension() {
        assertEquals(
            PeyvandDestinationCategory("Pictures/PEYVAND/Images", PeyvandMediaCollection.IMAGES),
            PeyvandDestinationCategoryResolver.resolve("photo.jpg", "application/octet-stream"),
        )
        assertEquals(
            PeyvandDestinationCategory("Download/PEYVAND/Documents", PeyvandMediaCollection.DOWNLOADS),
            PeyvandDestinationCategoryResolver.resolve("notes.txt", "application/octet-stream"),
        )
    }
}
