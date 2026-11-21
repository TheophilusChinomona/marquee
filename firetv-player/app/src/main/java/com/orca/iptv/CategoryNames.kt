package com.orca.iptv

/**
 * A provider category, split into the language/region it belongs to and a cleaned
 * shelf name.
 *
 * Providers ship categories as raw machine labels — `[MULTI-LANG] NETFLIX`,
 * `AF | DSTV ENTERTAIMENT`, `AM | USA KIDS&FAMLIY ` — which are unreadable on a TV.
 * This keeps the language as a chip for filtering and turns the rest into something
 * that reads like a shelf.
 */
data class CategoryLabel(
    val chip: String,
    val shelf: String,
    val raw: String
) {
    /** Single-line form: the chip only when it is not the default. */
    val line: String
        get() = if (chip == ALL) shelf else "$chip · $shelf"

    companion object {
        const val ALL = "All"
    }
}

object CategoryNames {

    /**
     * Tokens that must not be title-cased. Brands first, then acronyms; both are
     * matched case-insensitively.
     */
    private val BRANDS = mapOf(
        "netflix" to "Netflix", "hulu" to "Hulu", "disney" to "Disney", "amazon" to "Amazon",
        "prime" to "Prime", "showtime" to "Showtime", "starz" to "Starz", "paramount" to "Paramount",
        "peacock" to "Peacock", "crunchyroll" to "Crunchyroll", "apple" to "Apple", "quibi" to "Quibi",
        "dazn" to "DAZN", "dstv" to "DSTV", "canal+" to "Canal+", "canal" to "Canal", "fifa" to "FIFA",
        "ufc" to "UFC", "wwe" to "WWE", "sky" to "Sky", "tnt" to "TNT", "espn" to "ESPN",
        "hbo" to "HBO", "metv" to "MeTV", "vix" to "VIX", "flo" to "FLO", "ion" to "ION",
        "nfl" to "NFL", "nba" to "NBA", "mlb" to "MLB", "mls" to "MLS", "nhl" to "NHL",
        "ncaa" to "NCAA", "f1" to "F1", "abc" to "ABC", "cbs" to "CBS", "nbc" to "NBC",
        "fox" to "FOX", "pbs" to "PBS", "cw" to "CW", "mtv" to "MTV", "bbc" to "BBC",
        "ppv" to "PPV", "vip" to "VIP", "usa" to "USA", "uk" to "UK", "eu" to "EU", "hd" to "HD",
        "uhd" to "UHD", "4k" to "4K", "8k" to "8K", "3d" to "3D", "fps" to "FPS", "tv" to "TV",
        "vod" to "VOD", "ar" to "AR", "vr" to "VR", "bab" to "BAB", "as" to "AS",
        "fr" to "FR", "nl" to "NL", "de" to "DE", "pt" to "PT"
    )

    private val CHIP_NAMES = mapOf(
        "multi-lang" to "Multi-language",
        "multilang" to "Multi-language",
        "multi" to "Multi-language",
        "en" to "English",
        "af" to "Africa",
        "eu" to "Europe",
        "am" to "America",
        "as" to "Asia",
        "vip" to "VIP",
        "bab" to "BAB",
        "24/7" to "24/7"
    )

    /** Display order for the language chips; anything unknown sorts last. */
    private val CHIP_ORDER = listOf(
        CategoryLabel.ALL, "English", "Multi-language", "VIP",
        "Africa", "America", "Europe", "Asia", "BAB", "24/7"
    )

    private val BRACKET = Regex("^\\[([^\\]]+)]\\s*(.*)$")
    private val PIPE = Regex("^([A-Za-z0-9/+\\-]{2,10})\\s*\\|\\s*(.*)$")
    private val LEAD = Regex("^[^A-Za-z0-9]+")
    private val TRAIL = Regex("[^A-Za-z0-9]+$")
    private val SPACES = Regex("\\s+")

    fun parse(raw: String): CategoryLabel {
        val clean = raw.replace(SPACES, " ").trim()
        var shelf = clean
        var chip = CategoryLabel.ALL

        val bracket = BRACKET.find(clean)
        if (bracket != null) {
            chip = chipName(bracket.groupValues[1])
            shelf = bracket.groupValues[2]
        } else {
            val pipe = PIPE.find(clean)
            if (pipe != null) {
                chip = chipName(pipe.groupValues[1])
                shelf = pipe.groupValues[2]
            }
        }

        val spaced = shelf
            .replace(Regex("\\s*&\\s*"), " & ")
            .replace(Regex("\\s*/\\s*"), " / ")
            .replace(SPACES, " ")
            .trim(' ', '-')
        val label = spaced.split(" ")
            .filter { it.isNotBlank() }
            .joinToString(" ") { token(it) }

        return CategoryLabel(chip, label.ifBlank { clean }, clean)
    }

    fun sortKey(chip: String): Int =
        CHIP_ORDER.indexOf(chip).let { if (it >= 0) it else CHIP_ORDER.size }

    private fun chipName(raw: String): String =
        CHIP_NAMES[raw.lowercase()]
            ?: raw.lowercase().replaceFirstChar { it.uppercase() }

    private fun token(word: String): String {
        val lead = LEAD.find(word)?.value.orEmpty()
        val headless = word.removePrefix(lead)
        val trail = TRAIL.find(headless)?.value.orEmpty()
        val core = headless.removeSuffix(trail)
        if (core.isEmpty()) return word

        val mapped = BRANDS[core.lowercase()] ?: core.split("-").joinToString("-") { part ->
            BRANDS[part.lowercase()]
                ?: part.lowercase().replaceFirstChar { it.uppercase() }
        }
        return lead + mapped + trail
    }
}
