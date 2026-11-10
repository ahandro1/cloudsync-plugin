package com.cloudsync.core

import com.cloudsync.core.TitleMatch.Candidate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TitleMatchTest {

    private fun n(s: String) = TitleMatch.normalize(s)

    // ---------------------------------------------------------------- normalize

    @Test
    fun lowercasesAndCollapsesPunctuationAndWhitespace() {
        assertEquals("attack on titan", n("Attack on Titan"))
        assertEquals("spy x family", n("  Spy   x   Family  "))
        assertEquals("kaguya sama love is war", n("Kaguya-sama: Love is War"))
        assertEquals("re zero starting life in another world", n("Re:Zero - Starting Life in Another World"))
        assertEquals("dr stone", n("Dr. Stone"))
        assertEquals("steins gate 0", n("Steins;Gate 0"))
        assertEquals("86 eighty six", n("86 EIGHTY-SIX"))
        assertEquals("kono subarashii sekai ni shukufuku wo", n("Kono Subarashii Sekai ni Shukufuku wo!"))
        assertEquals("", n(""))
        assertEquals("", n(" !? "))
    }

    @Test
    fun stripsStreamingSiteTags() {
        assertEquals("attack on titan", n("Attack on Titan (Dub)"))
        assertEquals("attack on titan", n("Attack on Titan (Sub)"))
        assertEquals("attack on titan", n("Attack on Titan [Dub]"))
        assertEquals("attack on titan", n("Attack on Titan [SUB]"))
        assertEquals("fullmetal alchemist brotherhood", n("Fullmetal Alchemist: Brotherhood (TV)"))
        assertEquals("redo of healer", n("Redo of Healer (Uncensored)"))
        assertEquals("demon slayer", n("Demon Slayer (Sub) (Dub)"))
        assertEquals("bocchi the rock", n("Bocchi the Rock! (Sub & Dub)"))
        assertEquals("one piece", n("One Piece (English Dub)"))
        assertEquals("naruto shippuden", n("Naruto Shippuden Dubbed"))
        assertEquals("naruto shippuden", n("Naruto Shippuden - Dubbed"))
        assertEquals("one piece", n("One Piece English Dub"))
        assertEquals("one piece", n("One Piece Dub"))
        assertEquals("kaguya sama season 2", n("Kaguya-sama (TV) Season 2 (Dub)"))
    }

    @Test
    fun keepsNonTagParenthesesAndYears() {
        assertEquals("hunter x hunter 2011", n("Hunter x Hunter (2011)"))
        assertEquals("fruits basket 2019", n("Fruits Basket (2019)"))
        assertEquals("gintama the movie", n("Gintama (The Movie)"))
    }

    @Test
    fun aTitleThatIsOnlyATagIsKeptOrEmpty() {
        assertEquals("", n("(Dub)"))
        assertEquals("dub", n("Dub"))
    }

    @Test
    fun ampersandBecomesAnd() {
        assertEquals("rock and roll", n("Rock & Roll"))
        assertEquals("rock and roll", n("Rock&Roll"))
        assertEquals(n("Kaguya-sama & Friends"), n("Kaguya sama and Friends"))
    }

    @Test
    fun multiplicationSignIsX() {
        assertEquals("hunter x hunter 2011", n("HUNTER×HUNTER (2011)"))
        assertEquals("spy x family", n("SPY×FAMILY"))
    }

    @Test
    fun apostrophesAreRemovedNotSplit() {
        assertEquals("hells paradise", n("Hell's Paradise"))
        assertEquals("hells paradise", n("Hell’s Paradise"))
        assertEquals("kinos journey", n("Kino's Journey"))
    }

    @Test
    fun foldsAccentsAndMacrons() {
        assertEquals("pokemon", n("Pokémon"))
        assertEquals("shogeki no soma", n("Shōgeki no Sōma"))
        assertEquals("kyokai no kanata", n("Kyōkai no Kanata"))
    }

    @Test
    fun keepsNativeScript() {
        assertEquals("進撃の巨人", n("進撃の巨人"))
        assertEquals("進撃の巨人 season 2", n("進撃の巨人 Season 2"))
    }

    @Test
    fun keepsSeasonIndicators() {
        assertEquals("attack on titan season 2", n("Attack on Titan Season 2"))
        assertEquals("attack on titan season 2", n("Attack on Titan 2nd Season"))
        assertEquals("attack on titan season 3", n("Attack on Titan 3rd Season"))
        assertEquals("tensura season 1", n("Tensura 1st Season"))
        assertEquals("jujutsu kaisen part 2", n("Jujutsu Kaisen Part 2"))
        assertEquals("overlord ii", n("Overlord II"))
        assertEquals("mob psycho 100 iii", n("Mob Psycho 100 III"))
        assertEquals("tokyo ghoul a", n("Tokyo Ghoul √A"))
    }

    // ---------------------------------------------------------------- pick

    private val aot = Candidate(16498, listOf("Shingeki no Kyojin", "Attack on Titan", "進撃の巨人", "AoT"), 2013, "TV")
    private val aot2 = Candidate(20958, listOf("Shingeki no Kyojin Season 2", "Attack on Titan Season 2", "進撃の巨人 Season 2"), 2017, "TV")
    private val aot3 = Candidate(99147, listOf("Shingeki no Kyojin Season 3", "Attack on Titan Season 3"), 2018, "TV")
    private val aotJr = Candidate(21000, listOf("Shingeki! Kyojin Chuugakkou", "Attack on Titan: Junior High"), 2015, "TV")
    private val aotSearch = listOf(aot2, aot3, aotJr, aot)

    @Test
    fun picksTheExactTitleNotTheSequel() {
        assertEquals(16498, TitleMatch.pick("Attack on Titan", 2013, aotSearch))
        assertEquals(16498, TitleMatch.pick("Attack on Titan", null, aotSearch))
        assertEquals(16498, TitleMatch.pick("Shingeki no Kyojin", null, aotSearch))
        assertEquals(16498, TitleMatch.pick("進撃の巨人", null, aotSearch))
    }

    @Test
    fun picksTheSequelForASequelName() {
        assertEquals(20958, TitleMatch.pick("Attack on Titan Season 2", 2017, aotSearch))
        assertEquals(20958, TitleMatch.pick("Attack on Titan 2nd Season", null, aotSearch))
        assertEquals(20958, TitleMatch.pick("Attack on Titan Season 2 (Dub)", 2017, aotSearch))
        assertEquals(99147, TitleMatch.pick("Attack On Titan 3rd Season [Sub]", 2018, aotSearch))
    }

    @Test
    fun handlesDubSuffixes() {
        assertEquals(16498, TitleMatch.pick("Attack on Titan (Dub)", 2013, aotSearch))
        assertEquals(16498, TitleMatch.pick("Attack on Titan Dubbed", null, aotSearch))
        val ds = Candidate(101922, listOf("Kimetsu no Yaiba", "Demon Slayer: Kimetsu no Yaiba", "鬼滅の刃"), 2019, "TV")
        assertEquals(101922, TitleMatch.pick("Demon Slayer: Kimetsu no Yaiba (Dub)", 2019, listOf(ds)))
        assertEquals(101922, TitleMatch.pick("Demon Slayer - Kimetsu no Yaiba [English Dub]", null, listOf(ds)))
    }

    @Test
    fun partialTitleIsNotAMatch() {
        assertNull(TitleMatch.pick("Attack on Titan", 2013, listOf(aot2, aot3, aotJr)))
        assertNull(TitleMatch.pick("Attack on Titan Season 4", 2020, aotSearch))
        assertNull(TitleMatch.pick("Attack", null, aotSearch))
    }

    @Test
    fun differentSeasonSpellingsAreNotGuessed() {
        val ol2 = Candidate(98437, listOf("Overlord II"), 2018, "TV")
        assertNull(TitleMatch.pick("Overlord Season 2", 2018, listOf(ol2)))
        assertEquals(98437, TitleMatch.pick("Overlord II (Dub)", 2018, listOf(ol2)))
    }

    private val fb2001 = Candidate(120, listOf("Fruits Basket"), 2001, "TV")
    private val fb2019 = Candidate(105334, listOf("Fruits Basket (2019)", "Fruits Basket"), 2019, "TV")
    private val fbSearch = listOf(fb2019, fb2001)

    @Test
    fun ambiguousRemakeWithoutYearIsNull() {
        assertNull(TitleMatch.pick("Fruits Basket", null, fbSearch))
        assertNull(TitleMatch.pick("Fruits Basket (Dub)", null, fbSearch))
    }

    @Test
    fun remakeIsResolvedByYear() {
        assertEquals(120, TitleMatch.pick("Fruits Basket", 2001, fbSearch))
        assertEquals(120, TitleMatch.pick("Fruits Basket", 2002, fbSearch))
        assertEquals(105334, TitleMatch.pick("Fruits Basket", 2019, fbSearch))
        assertEquals(105334, TitleMatch.pick("Fruits Basket", 2020, fbSearch))
        assertNull(TitleMatch.pick("Fruits Basket", 2010, fbSearch))
    }

    @Test
    fun remakeIsResolvedByYearInTitle() {
        assertEquals(105334, TitleMatch.pick("Fruits Basket (2019)", null, fbSearch))
        val hxh = Candidate(136, listOf("HUNTER×HUNTER", "Hunter x Hunter"), 1999, "TV")
        val hxh11 = Candidate(11061, listOf("HUNTER×HUNTER (2011)", "Hunter x Hunter (2011)"), 2011, "TV")
        assertEquals(11061, TitleMatch.pick("Hunter x Hunter (2011)", null, listOf(hxh, hxh11)))
        assertEquals(136, TitleMatch.pick("Hunter x Hunter", null, listOf(hxh, hxh11)))
    }

    @Test
    fun yearToleranceIsOne() {
        val c = Candidate(1, listOf("Dororo"), 2019, "TV")
        assertEquals(1, TitleMatch.pick("Dororo", 2018, listOf(c)))
        assertEquals(1, TitleMatch.pick("Dororo", 2020, listOf(c)))
        assertNull(TitleMatch.pick("Dororo", 2017, listOf(c)))
        assertNull(TitleMatch.pick("Dororo", 2021, listOf(c)))
    }

    @Test
    fun exactYearBreaksTieBetweenNeighbouringYears() {
        val a = Candidate(1, listOf("Same Title"), 2019, "TV")
        val b = Candidate(2, listOf("Same Title"), 2020, "MOVIE")
        assertEquals(1, TitleMatch.pick("Same Title", 2019, listOf(a, b)))
        assertEquals(2, TitleMatch.pick("Same Title", 2020, listOf(a, b)))
        assertEquals(1, TitleMatch.pick("Same Title", 2018, listOf(a, b)))
        assertEquals(2, TitleMatch.pick("Same Title", 2021, listOf(a, b)))
        assertNull(TitleMatch.pick("Same Title", null, listOf(a, b)))
    }

    @Test
    fun sameYearDuplicatesAreAmbiguous() {
        val tv = Candidate(1, listOf("Same Title"), 2019, "TV")
        val movie = Candidate(2, listOf("Same Title"), 2019, "MOVIE")
        assertNull(TitleMatch.pick("Same Title", 2019, listOf(tv, movie)))
        assertNull(TitleMatch.pick("Same Title", null, listOf(tv, movie)))
    }

    @Test
    fun unknownCandidateYearQualifiesButDoesNotCountAsExact() {
        val known = Candidate(1, listOf("Same Title"), 2019, "TV")
        val unknown = Candidate(2, listOf("Same Title"), null, "TV")
        assertEquals(2, TitleMatch.pick("Same Title", 2005, listOf(known, unknown)))
        assertEquals(1, TitleMatch.pick("Same Title", 2019, listOf(known, unknown)))
        assertNull(TitleMatch.pick("Same Title", null, listOf(known, unknown)))
        assertEquals(2, TitleMatch.pick("Same Title", 2019, listOf(unknown)))
    }

    @Test
    fun duplicateCandidateIdsCountOnce() {
        assertEquals(16498, TitleMatch.pick("Attack on Titan", null, listOf(aot, aot, aot2)))
    }

    @Test
    fun matchesViaSynonym() {
        assertEquals(16498, TitleMatch.pick("AoT", null, aotSearch))
    }

    @Test
    fun ambiguousSynonymIsNull() {
        val a = Candidate(1, listOf("Real Title A", "Hero"), 2015, "TV")
        val b = Candidate(2, listOf("Real Title B", "Hero"), 2015, "TV")
        assertNull(TitleMatch.pick("Hero", 2015, listOf(a, b)))
    }

    @Test
    fun emptyInputsAreNull() {
        assertNull(TitleMatch.pick("", null, aotSearch))
        assertNull(TitleMatch.pick("(Dub)", null, aotSearch))
        assertNull(TitleMatch.pick("Attack on Titan", null, emptyList()))
        assertNull(TitleMatch.pick("Attack on Titan", null, listOf(Candidate(5, emptyList(), 2013, "TV"))))
    }
}
