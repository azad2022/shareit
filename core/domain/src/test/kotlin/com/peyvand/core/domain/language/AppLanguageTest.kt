package com.peyvand.core.domain.language

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLanguageTest {
    @Test
    fun `catalog contains the seven approved language targets`() {
        assertEquals(
            setOf("fa", "en", "ar", "tr", "ku", "de", "fr"),
            AppLanguage.targets.map(AppLanguage::targetCode).toSet(),
        )
    }

    @Test
    fun `only Persian is selectable until translations are complete`() {
        assertEquals(listOf(AppLanguage.PERSIAN), AppLanguage.targets.filter(AppLanguage::isSelectable))
    }

    @Test
    fun `Kurdish remains unresolved until the locale and script are approved`() {
        assertNull(AppLanguage.KURDISH.localeTag)
        assertFalse(AppLanguage.KURDISH.isSelectable)
    }

    @Test
    fun `locale lookup accepts a region subtag`() {
        assertEquals(AppLanguage.PERSIAN, AppLanguage.fromLocaleTag("fa-IR"))
        assertEquals(AppLanguage.ENGLISH, AppLanguage.fromLocaleTag("en-US"))
    }

    @Test
    fun `unknown and blank locales are rejected`() {
        assertNull(AppLanguage.fromLocaleTag("   "))
        assertNull(AppLanguage.fromLocaleTag("es-ES"))
        assertNull(AppLanguage.fromLocaleTag(null))
    }

    @Test
    fun `Persian is explicitly ready`() {
        assertTrue(AppLanguage.PERSIAN.isSelectable)
        assertEquals("fa", AppLanguage.PERSIAN.localeTag)
    }
}
