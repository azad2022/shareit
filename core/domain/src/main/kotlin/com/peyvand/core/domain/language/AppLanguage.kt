package com.peyvand.core.domain.language

/**
 * Product language targets. A language is selectable only after its complete
 * resource bundle and RTL/LTR verification have been approved.
 *
 * Kurdish deliberately has no locale tag until the product owner selects the
 * supported variant and script (for example, Sorani or Kurmanji).
 */
enum class AppLanguage(
    val targetCode: String,
    val localeTag: String?,
    val isSelectable: Boolean,
) {
    PERSIAN(targetCode = "fa", localeTag = "fa", isSelectable = true),
    ENGLISH(targetCode = "en", localeTag = "en", isSelectable = false),
    ARABIC(targetCode = "ar", localeTag = "ar", isSelectable = false),
    TURKISH(targetCode = "tr", localeTag = "tr", isSelectable = false),
    KURDISH(targetCode = "ku", localeTag = null, isSelectable = false),
    GERMAN(targetCode = "de", localeTag = "de", isSelectable = false),
    FRENCH(targetCode = "fr", localeTag = "fr", isSelectable = false),
    ;

    companion object {
        val targets: List<AppLanguage> = entries.toList()

        fun fromLocaleTag(localeTag: String?): AppLanguage? {
            val languageSubtag = localeTag
                ?.trim()
                ?.takeIf(String::isNotEmpty)
                ?.substringBefore('-')
                ?.substringBefore('_')
                ?.lowercase()
                ?: return null

            return entries.firstOrNull { it.localeTag == languageSubtag }
        }
    }
}
