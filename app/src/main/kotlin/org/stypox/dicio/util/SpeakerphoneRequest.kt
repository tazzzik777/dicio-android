package org.stypox.dicio.util

/**
 * Detects whether the user asked for a call on speakerphone, e.g. "никите по громкой связи" or
 * "mom on speaker", and removes those words so that only the contact name remains.
 */
object SpeakerphoneRequest {

    private val SPEAKERPHONE_PATTERNS = listOf(
        // Russian: "по громкой связи", "на громкой", "на громкую", "через динамик", "с громкой
        // связью", "громкая связь", "на спикерфоне"
        Regex(
            "(?iU)(?:\\b(?:по|на|через|с|со|включи|включив|включить)\\s+)?" +
                    "(?:громк\\w*(?:\\s+связ\\w*)?|динамик\\w*|спикер\\w*)"
        ),
        // English: "on speaker", "with speakerphone", "using the loudspeaker"
        Regex(
            "(?iU)(?:\\b(?:on|with|using|via|through)\\s+)?(?:the\\s+)?" +
                    "(?:speaker\\s*phone|loud\\s*speaker|speaker)\\b"
        ),
    )

    /**
     * @return the [text] without the words that ask for speakerphone, and whether the user asked
     * for speakerphone
     */
    fun extract(text: String): Pair<String, Boolean> {
        var result = text.replace('ё', 'е').replace('Ё', 'Е')
        var found = false
        for (pattern in SPEAKERPHONE_PATTERNS) {
            if (pattern.containsMatchIn(result)) {
                found = true
                result = pattern.replace(result, " ")
            }
        }
        if (!found) {
            return Pair(text, false)
        }
        return Pair(result.replace(Regex("\\s+"), " ").trim(), true)
    }
}
