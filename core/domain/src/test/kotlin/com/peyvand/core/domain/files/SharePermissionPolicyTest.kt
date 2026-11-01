package com.peyvand.core.domain.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SharePermissionPolicyTest {
    @Test
    fun `music uses scoped audio permission on api 33 and newer`() {
        assertEquals(
            listOf(SharePermission.READ_MEDIA_AUDIO),
            SharePermissionPolicy.permissionsFor(ShareCategory.MUSIC, 33),
        )
    }

    @Test
    fun `legacy media uses external storage permission through api 32`() {
        assertEquals(
            listOf(SharePermission.READ_EXTERNAL_STORAGE),
            SharePermissionPolicy.permissionsFor(ShareCategory.PHOTOS, 32),
        )
    }

    @Test
    fun `api 34 visual media includes user selected permission`() {
        assertEquals(
            listOf(
                SharePermission.READ_MEDIA_IMAGES,
                SharePermission.READ_MEDIA_VISUAL_USER_SELECTED,
            ),
            SharePermissionPolicy.permissionsFor(ShareCategory.PHOTOS, 34),
        )
    }

    @Test
    fun `selected visual media grant is sufficient on api 34`() {
        assertTrue(
            SharePermissionPolicy.canRead(
                category = ShareCategory.VIDEOS,
                apiLevel = 34,
                granted = setOf(SharePermission.READ_MEDIA_VISUAL_USER_SELECTED),
            ),
        )
    }

    @Test
    fun `unselected media remains inaccessible without permission`() {
        assertFalse(
            SharePermissionPolicy.canRead(
                category = ShareCategory.PHOTOS,
                apiLevel = 34,
                granted = emptySet(),
            ),
        )
    }

    @Test
    fun `documents and apps do not request broad storage permission`() {
        assertTrue(
            SharePermissionPolicy.permissionsFor(ShareCategory.FILES, 36).isEmpty(),
        )
        assertTrue(
            SharePermissionPolicy.permissionsFor(ShareCategory.APPLICATIONS, 36).isEmpty(),
        )
    }
}
