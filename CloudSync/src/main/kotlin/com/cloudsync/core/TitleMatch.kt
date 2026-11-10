package com.cloudsync.core

/**
 * Conservative matching of a CloudStream show name to an AniList search result.
 * A wrong match writes to the wrong anime on the user's account, so anything ambiguous is null.
 */
object TitleMatch {
    /** @param titles romaji, english, native, synonyms (nulls already removed). */
    data class Candidate(val id: Int, val titles: List<String>, val year: Int?, val format: String?)

    /** Words that, alone or together inside (...) or [...], are a streaming-site tag, not title. */
    private val TAG_WORDS = setOf(
        "dub", "dubbed", "sub", "subbed", "tv", "uncensored", "english", "eng", "dual", "audio",
    )

    /** Trailing bare words removed from the end of a title (after punctuation is collapsed). */
    private val TRAILING_TAGS = setOf("dub", "dubbed", "sub", "subbed")
    private val TRAILING_TAG_PREFIX = setOf("english", "eng")

    private val BRACKET = Regex("[(\\[]([^()\\[\\]]*)[)\\]]")
    private val ORDINAL_SEASON = Regex("\\b(\\d+)(?:st|nd|rd|th) season\\b")
    private val SPACES = Regex("\\s+")

    private val FOLD: Map<Char, Char> = listOf(
        "àáâãäåāă" to 'a',
        "çć" to 'c',
        "èéêëēė" to 'e',
        "ìíîïī" to 'i',
        "ñ" to 'n',
        "òóôõöøō" to 'o',
        "ùúûüū" to 'u',
        "ýÿ" to 'y',
    ).flatMap { (chars, to) -> chars.map { it to to } }.toMap()

    /**
     * Lowercase; drop site tags like "(Dub)", "[Sub]", "(TV)", "(Uncensored)", trailing "Dubbed";
     * "&" -> "and", "×" -> "x"; fold common Latin accents and macrons; remove apostrophes; every
     * other non letter/digit becomes a space; "2nd Season" -> "season 2". Season / part numbers
     * and roman numerals are kept as they are, because they tell sequels apart.
     */
    fun normalize(title: String): String {
        var s = title.lowercase()
        s = buildString(s.length) {
            for (c in s) {
                when (c) {
                    '&' -> append(" and ")
                    '×' -> append(" x ")
                    '\'', '’', '‘', '`' -> {}
                    else -> append(FOLD[c] ?: c)
                }
            }
        }
        s = BRACKET.replace(s) { m ->
            val words = m.groupValues[1].split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }
            val tag = words.any { it in TAG_WORDS } && words.all { it in TAG_WORDS || it == "and" } // "(Sub & Dub)"
            if (tag) " " else " ${m.groupValues[1]} "
        }
        s = buildString(s.length) { for (c in s) append(if (c.isLetterOrDigit()) c else ' ') }
        s = s.trim().replace(SPACES, " ")
        s = ORDINAL_SEASON.replace(s) { "season ${it.groupValues[1]}" }
        val words = s.split(' ').filter { it.isNotEmpty() }.toMutableList()
        while (words.size > 1 && words.last() in TRAILING_TAGS) {
            words.removeAt(words.size - 1)
            if (words.size > 1 && words.last() in TRAILING_TAG_PREFIX) words.removeAt(words.size - 1)
        }
        return words.joinToString(" ")
    }

    /**
     * The AniList id for `name`, or null when there is no single confident match.
     * A candidate qualifies if one of its titles normalizes to the same string as `name` and,
     * when both years are known, the years differ by at most 1. Several qualifying candidates
     * are resolved only by a unique exact year match.
     */
    fun pick(name: String, year: Int?, candidates: List<Candidate>): Int? {
        val n = normalize(name)
        if (n.isEmpty()) return null
        val qualified = candidates
            .filter { c -> yearOk(year, c.year) && c.titles.any { normalize(it) == n } }
            .distinctBy { it.id }
        if (qualified.size == 1) return qualified[0].id
        if (qualified.isEmpty() || year == null) return null
        val exact = qualified.filter { it.year == year }
        return if (exact.size == 1) exact[0].id else null
    }

    private fun yearOk(want: Int?, have: Int?): Boolean =
        want == null || have == null || kotlin.math.abs(want - have) <= 1
}
