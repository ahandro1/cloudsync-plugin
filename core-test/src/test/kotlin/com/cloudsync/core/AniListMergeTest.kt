package com.cloudsync.core

import com.cloudsync.core.AniListMerge.Action.Push
import com.cloudsync.core.AniListMerge.Action.SetLocal
import com.cloudsync.core.AniListMerge.LocalShow
import com.cloudsync.core.AniListMerge.RemoteEntry
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val NOW = 1_700_000_000_000L

// CloudStream watch types
private const val WATCHING = 0
private const val COMPLETED_WT = 1
private const val ONHOLD = 2
private const val DROPPED_WT = 3
private const val PLAN = 4

private const val CUR = "CURRENT"
private const val COM = "COMPLETED"
private const val PAU = "PAUSED"
private const val DRO = "DROPPED"
private const val PLA = "PLANNING"
private const val REP = "REPEATING"

private fun show(id: String, wt: Int, t: Long = NOW) = LocalShow(id, wt, t)
private fun match(mediaId: Int?, base: String? = null) = AniMatch(mediaId = mediaId, base = base, title = null)
private fun rem(status: String, at: Long = NOW) = RemoteEntry(status, at)

/** Applies a plan the way the plugin does, and lets tests replan on the resulting state. */
private class World(
    local: List<LocalShow>,
    matches: Map<String, AniMatch>,
    remote: Map<Int, RemoteEntry>,
    var clock: Long = NOW + 1_000_000,
) {
    val local = LinkedHashMap(local.associateBy { it.showId })
    val matches = LinkedHashMap(matches)
    val remote = LinkedHashMap(remote)

    fun plan() = AniListMerge.plan(local.values.toList(), matches, remote)

    private fun record(bases: Map<String, String>) {
        for ((id, b) in bases) matches[id] = (matches[id] ?: AniMatch()).copy(base = b)
    }

    fun apply(plan: AniListMerge.Plan, pushFails: Boolean = false) {
        record(plan.settled)
        for (a in plan.actions) {
            when (a) {
                is Push -> if (!pushFails) {
                    remote[a.mediaId] = RemoteEntry(a.status, ++clock)
                    record(AniListMerge.basesAfter(a))
                }
                is SetLocal -> {
                    local[a.showId] = LocalShow(a.showId, a.watchType, ++clock)
                    record(AniListMerge.basesAfter(a))
                }
            }
        }
    }
}

class AniListMergeTest {

    // ---------------------------------------------------------------- mapping

    @Test
    fun watchTypeToAniList() {
        assertEquals(CUR, AniListMerge.toAniList(0))
        assertEquals(COM, AniListMerge.toAniList(1))
        assertEquals(PAU, AniListMerge.toAniList(2))
        assertEquals(DRO, AniListMerge.toAniList(3))
        assertEquals(PLA, AniListMerge.toAniList(4))
        assertNull(AniListMerge.toAniList(5))
        assertNull(AniListMerge.toAniList(-1))
        assertNull(AniListMerge.toAniList(99))
    }

    @Test
    fun aniListToWatchType() {
        assertEquals(0, AniListMerge.toWatchType(CUR))
        assertEquals(1, AniListMerge.toWatchType(COM))
        assertEquals(2, AniListMerge.toWatchType(PAU))
        assertEquals(3, AniListMerge.toWatchType(DRO))
        assertEquals(4, AniListMerge.toWatchType(PLA))
        assertEquals(0, AniListMerge.toWatchType(REP))
        assertNull(AniListMerge.toWatchType("current"))
        assertNull(AniListMerge.toWatchType(""))
        assertNull(AniListMerge.toWatchType("WATCHING"))
    }

    @Test
    fun mappingRoundTrips() {
        for (wt in 0..4) assertEquals(wt, AniListMerge.toWatchType(AniListMerge.toAniList(wt)!!))
    }

    @Test
    fun normalizeTreatsRepeatingAsCurrent() {
        assertEquals(CUR, AniListMerge.normalize(REP))
        assertEquals(COM, AniListMerge.normalize(COM))
        assertNull(AniListMerge.normalize(null))
    }

    // ---------------------------------------------------------------- single-show branches

    private fun single(wt: Int, base: String?, remote: RemoteEntry?, t: Long = NOW) =
        AniListMerge.plan(
            listOf(show("s", wt, t)),
            mapOf("s" to match(10, base)),
            if (remote == null) emptyMap() else mapOf(10 to remote),
        )

    @Test
    fun agreementIsSettled() {
        val p = single(COMPLETED_WT, null, rem(COM))
        assertTrue(p.actions.isEmpty())
        assertEquals(mapOf("s" to COM), p.settled)
        val p2 = single(COMPLETED_WT, COM, rem(COM))
        assertTrue(p2.actions.isEmpty())
        assertEquals(mapOf("s" to COM), p2.settled)
        // Agreement wins even when both moved away from an old base the same way.
        val p3 = single(DROPPED_WT, CUR, rem(DRO))
        assertTrue(p3.actions.isEmpty())
        assertEquals(mapOf("s" to DRO), p3.settled)
    }

    @Test
    fun watchingAgreesWithRepeating() {
        for (base in listOf(null, CUR, COM)) {
            val p = single(WATCHING, base, rem(REP, NOW + 99))
            assertTrue(p.actions.isEmpty(), "base=$base")
            assertEquals(mapOf("s" to CUR), p.settled)
        }
    }

    @Test
    fun localChangeOverRepeatingWithBaseCurrentIsPushed() {
        val p = single(COMPLETED_WT, CUR, rem(REP, NOW + 1_000), t = NOW)
        assertEquals(listOf(Push(10, COM, listOf("s"))), p.actions)
    }

    @Test
    fun aniListMovingToRepeatingIsSetLocally() {
        val p = single(COMPLETED_WT, COM, rem(REP))
        assertEquals(listOf(SetLocal("s", WATCHING, 10, REP)), p.actions)
        assertEquals(mapOf("s" to CUR), AniListMerge.basesAfter(p.actions[0]))
    }

    @Test
    fun onlyLocalChangedIsPushed() {
        val p = single(COMPLETED_WT, CUR, rem(CUR, NOW + 10_000), t = NOW)
        assertEquals(listOf(Push(10, COM, listOf("s"))), p.actions)
        assertTrue(p.settled.isEmpty())
    }

    @Test
    fun neverOnAniListIsPushed() {
        val p = single(PLAN, null, null, t = Entry.INITIAL_T)
        assertEquals(listOf(Push(10, PLA, listOf("s"))), p.actions)
    }

    @Test
    fun removedOnAniListIsLeftAlone() {
        val p = single(COMPLETED_WT, COM, null)
        assertTrue(p.actions.isEmpty())
        assertTrue(p.settled.isEmpty())
    }

    @Test
    fun localChangeAfterRemovalOnAniListIsPushedAgain() {
        val p = single(ONHOLD, COM, null)
        assertEquals(listOf(Push(10, PAU, listOf("s"))), p.actions)
    }

    @Test
    fun onlyAniListChangedIsSetLocally() {
        val p = single(WATCHING, CUR, rem(COM, at = 5), t = NOW)
        assertEquals(listOf(SetLocal("s", COMPLETED_WT, 10, COM)), p.actions)
    }

    @Test
    fun bothChangedLocalNewerPushes() {
        val p = single(DROPPED_WT, CUR, rem(PAU, at = NOW - 1), t = NOW)
        assertEquals(listOf(Push(10, DRO, listOf("s"))), p.actions)
    }

    @Test
    fun bothChangedAniListNewerSetsLocal() {
        val p = single(DROPPED_WT, CUR, rem(PAU, at = NOW + 1), t = NOW)
        assertEquals(listOf(SetLocal("s", ONHOLD, 10, PAU)), p.actions)
    }

    @Test
    fun bothChangedTieGoesToAniList() {
        val p = single(DROPPED_WT, CUR, rem(PAU, at = NOW), t = NOW)
        assertEquals(listOf(SetLocal("s", ONHOLD, 10, PAU)), p.actions)
    }

    @Test
    fun noBaseAndDifferentUsesTime() {
        assertEquals(listOf(Push(10, COM, listOf("s"))), single(COMPLETED_WT, null, rem(CUR, NOW - 1)).actions)
        assertEquals(listOf(SetLocal("s", WATCHING, 10, CUR)), single(COMPLETED_WT, null, rem(CUR, NOW + 1)).actions)
        assertEquals(listOf(SetLocal("s", WATCHING, 10, CUR)), single(COMPLETED_WT, null, rem(CUR, NOW)).actions)
    }

    @Test
    fun noBaseRepeatingVsLocalCompletedNewerLocalPushes() {
        assertEquals(listOf(Push(10, COM, listOf("s"))), single(COMPLETED_WT, null, rem(REP, NOW - 1)).actions)
        assertEquals(listOf(SetLocal("s", WATCHING, 10, REP)), single(COMPLETED_WT, null, rem(REP, NOW + 1)).actions)
    }

    @Test
    fun firstLinkImportAlwaysLosesToExistingAniListEntry() {
        for (t in listOf(Entry.INITIAL_T, Merge.LINKED_T)) {
            for (at in listOf(0L, 1L, 2L, NOW)) {
                val p = single(COMPLETED_WT, null, rem(PLA, at), t = t)
                assertEquals(listOf(SetLocal("s", PLAN, 10, PLA)), p.actions, "t=$t at=$at")
                val p2 = single(COMPLETED_WT, CUR, rem(PLA, at), t = t)
                assertEquals(listOf(SetLocal("s", PLAN, 10, PLA)), p2.actions, "t=$t at=$at")
            }
        }
    }

    // ---------------------------------------------------------------- ignored inputs

    @Test
    fun unmatchedShowsAreIgnored() {
        val p = AniListMerge.plan(
            listOf(show("a", COMPLETED_WT), show("b", COMPLETED_WT)),
            mapOf("a" to match(null, COM)),
            mapOf(10 to rem(CUR)),
        )
        assertTrue(p.actions.isEmpty())
        assertTrue(p.settled.isEmpty())
    }

    @Test
    fun invalidWatchTypeIsIgnored() {
        val p = AniListMerge.plan(listOf(show("a", 5), show("b", -1)), mapOf("a" to match(10), "b" to match(11)), emptyMap())
        assertTrue(p.actions.isEmpty())
    }

    @Test
    fun unknownAniListStatusLeavesGroupAlone() {
        val p = single(COMPLETED_WT, CUR, rem("SOMETHING_NEW"))
        assertTrue(p.actions.isEmpty())
        assertTrue(p.settled.isEmpty())
    }

    @Test
    fun aniListOnlyEntriesAreNotImported() {
        val p = AniListMerge.plan(emptyList(), mapOf("a" to match(10, COM)), mapOf(10 to rem(CUR), 11 to rem(PLA)))
        assertEquals(AniListMerge.Plan(emptyList(), emptyMap()), p)
    }

    @Test
    fun removedLocalBookmarkIsNeverDeletedFromAniList() {
        // "a" was un-bookmarked locally: it is simply absent from `local`.
        val p = AniListMerge.plan(listOf(show("b", PLAN)), mapOf("a" to match(10, COM), "b" to match(11, PLA)), mapOf(10 to rem(COM), 11 to rem(PLA)))
        assertTrue(p.actions.isEmpty())
        assertEquals(mapOf("b" to PLA), p.settled)
    }

    // ---------------------------------------------------------------- groups

    @Test
    fun groupInAgreementIsSettledForEveryMember() {
        val p = AniListMerge.plan(
            listOf(show("a", COMPLETED_WT), show("b", COMPLETED_WT)),
            mapOf("a" to match(10, COM), "b" to match(10)),
            mapOf(10 to rem(COM)),
        )
        assertTrue(p.actions.isEmpty())
        assertEquals(mapOf("a" to COM, "b" to COM), p.settled)
    }

    @Test
    fun groupLocalWinsWithOnePushAndMakesOthersConsistent() {
        val p = AniListMerge.plan(
            listOf(show("a", WATCHING, NOW - 100), show("b", COMPLETED_WT, NOW), show("c", COMPLETED_WT, NOW - 50)),
            mapOf("a" to match(10, CUR), "b" to match(10, CUR), "c" to match(10, CUR)),
            mapOf(10 to rem(CUR, NOW - 10_000)),
        )
        assertEquals(
            listOf(Push(10, COM, listOf("b", "c")), SetLocal("a", COMPLETED_WT, 10, COM)),
            p.actions,
        )
        assertEquals(1, p.actions.count { it is Push })
    }

    @Test
    fun groupAniListWinsSetsEveryDifferingMember() {
        val p = AniListMerge.plan(
            listOf(show("a", WATCHING), show("b", PLAN), show("c", DROPPED_WT)),
            mapOf("a" to match(10, CUR), "b" to match(10, CUR), "c" to match(10, CUR)),
            mapOf(10 to rem(DRO)),
        )
        // Leader: "b" (b and c changed, equal t, smallest showId wins). PLANNING vs AniList
        // DROPPED with base CURRENT: both changed, equal time -> AniList.
        assertEquals(listOf(SetLocal("a", DROPPED_WT, 10, DRO), SetLocal("b", DROPPED_WT, 10, DRO)), p.actions)
        assertEquals(mapOf("c" to DRO), p.settled)
    }

    @Test
    fun groupOnlyAniListChanged() {
        val p = AniListMerge.plan(
            listOf(show("a", WATCHING), show("b", WATCHING)),
            mapOf("a" to match(10, CUR), "b" to match(10, CUR)),
            mapOf(10 to rem(COM, 1)),
        )
        assertEquals(listOf(SetLocal("a", COMPLETED_WT, 10, COM), SetLocal("b", COMPLETED_WT, 10, COM)), p.actions)
    }

    @Test
    fun groupLeaderAgreesButStaleMemberIsAligned() {
        val p = AniListMerge.plan(
            listOf(show("a", PLAN, NOW - 1000), show("b", COMPLETED_WT, NOW)),
            mapOf("a" to match(10, COM), "b" to match(10, null)),
            mapOf(10 to rem(COM, NOW + 5)),
        )
        // Both differ from their bases; b is the newest change and agrees with AniList.
        assertEquals(listOf(SetLocal("a", COMPLETED_WT, 10, COM)), p.actions)
        assertEquals(mapOf("b" to COM), p.settled)
    }

    @Test
    fun groupLeaderIsTheMostRecentChangeNotTheMostRecentWrite() {
        // "b" was rewritten recently but still holds its base; "a" holds a real change.
        val p = AniListMerge.plan(
            listOf(show("a", COMPLETED_WT, NOW - 1000), show("b", WATCHING, NOW)),
            mapOf("a" to match(10, CUR), "b" to match(10, CUR)),
            mapOf(10 to rem(CUR, NOW - 50_000)),
        )
        assertEquals(listOf(Push(10, COM, listOf("a")), SetLocal("b", COMPLETED_WT, 10, COM)), p.actions)
    }

    @Test
    fun groupNewestChangeWins() {
        val p = AniListMerge.plan(
            listOf(show("a", COMPLETED_WT, NOW - 1000), show("b", DROPPED_WT, NOW)),
            mapOf("a" to match(10, CUR), "b" to match(10, CUR)),
            mapOf(10 to rem(CUR, 5)),
        )
        assertEquals(listOf(Push(10, DRO, listOf("b")), SetLocal("a", DROPPED_WT, 10, DRO)), p.actions)
    }

    @Test
    fun groupTieOnTimeGoesToSmallestShowId() {
        val p = AniListMerge.plan(
            listOf(show("z", DROPPED_WT, NOW), show("m", COMPLETED_WT, NOW)),
            mapOf("z" to match(10, CUR), "m" to match(10, CUR)),
            mapOf(10 to rem(CUR, 5)),
        )
        assertEquals(listOf(Push(10, COM, listOf("m")), SetLocal("z", COMPLETED_WT, 10, COM)), p.actions)
    }

    @Test
    fun groupRemovedOnAniListAndUnchangedLocallyIsLeftAlone() {
        val p = AniListMerge.plan(
            listOf(show("a", COMPLETED_WT), show("b", COMPLETED_WT)),
            mapOf("a" to match(10, COM), "b" to match(10, COM)),
            emptyMap(),
        )
        assertTrue(p.actions.isEmpty())
    }

    @Test
    fun groupRemovedOnAniListButOneMemberChangedIsPushed() {
        val p = AniListMerge.plan(
            listOf(show("a", COMPLETED_WT, NOW), show("b", PLAN, NOW - 5)),
            mapOf("a" to match(10, COM), "b" to match(10, COM)),
            emptyMap(),
        )
        assertEquals(listOf(Push(10, PLA, listOf("b")), SetLocal("a", PLAN, 10, PLA)), p.actions)
    }

    @Test
    fun duplicateShowIdKeepsNewest() {
        val p = AniListMerge.plan(
            listOf(show("a", WATCHING, NOW - 5), show("a", COMPLETED_WT, NOW)),
            mapOf("a" to match(10, CUR)),
            mapOf(10 to rem(CUR, 1)),
        )
        assertEquals(listOf(Push(10, COM, listOf("a"))), p.actions)
    }

    @Test
    fun actionsAreOrderedByMediaId() {
        val p = AniListMerge.plan(
            listOf(show("x", COMPLETED_WT), show("y", COMPLETED_WT)),
            mapOf("x" to match(30), "y" to match(20)),
            emptyMap(),
        )
        assertEquals(listOf(Push(20, COM, listOf("y")), Push(30, COM, listOf("x"))), p.actions)
    }

    // ---------------------------------------------------------------- basesAfter

    @Test
    fun basesAfterPushCoversItsShows() {
        assertEquals(mapOf("a" to COM, "b" to COM), AniListMerge.basesAfter(Push(1, COM, listOf("a", "b"))))
        assertEquals(emptyMap(), AniListMerge.basesAfter(Push(1, COM, emptyList())))
    }

    @Test
    fun basesAfterSetLocalNormalizesRepeating() {
        assertEquals(mapOf("a" to PAU), AniListMerge.basesAfter(SetLocal("a", ONHOLD, 1, PAU)))
        assertEquals(mapOf("a" to CUR), AniListMerge.basesAfter(SetLocal("a", WATCHING, 1, REP)))
    }

    // ---------------------------------------------------------------- fixed point / safety

    private val allStatuses = listOf(CUR, COM, PAU, DRO, PLA, REP)

    private fun randomWorld(rnd: Random): World {
        val shows = (1..6).map { "s$it" }
        val local = ArrayList<LocalShow>()
        val matches = HashMap<String, AniMatch>()
        for (id in shows) {
            when (rnd.nextInt(8)) {
                0 -> {}
                1 -> matches[id] = match(null)
                else -> matches[id] = match(
                    10 + rnd.nextInt(3),
                    if (rnd.nextInt(3) == 0) null else listOf(CUR, COM, PAU, DRO, PLA)[rnd.nextInt(5)],
                )
            }
            if (rnd.nextInt(5) != 0) {
                val t = when (rnd.nextInt(4)) {
                    0 -> Entry.INITIAL_T
                    1 -> Merge.LINKED_T
                    else -> NOW + rnd.nextLong(-10, 10)
                }
                local += show(id, rnd.nextInt(5), t)
            }
        }
        val remote = HashMap<Int, RemoteEntry>()
        for (m in 10..12) {
            if (rnd.nextInt(4) != 0) remote[m] = rem(allStatuses[rnd.nextInt(allStatuses.size)], NOW + rnd.nextLong(-10, 10))
        }
        return World(local, matches, remote)
    }

    @Test
    fun planIsAFixedPoint() {
        val rnd = Random(42)
        repeat(5000) { i ->
            val w = randomWorld(rnd)
            val before = w.local.toMap()
            val p1 = w.plan()
            w.apply(p1)
            val p2 = w.plan()
            assertTrue(p2.actions.isEmpty(), "case $i: $before -> second plan ${p2.actions}")
            // Settled bases must already be recorded.
            for ((id, b) in p2.settled) assertEquals(b, w.matches[id]?.base, "case $i")
        }
    }

    @Test
    fun afterApplyingEveryMatchedGroupAgreesWithAniList() {
        val rnd = Random(7)
        repeat(5000) { i ->
            val w = randomWorld(rnd)
            val remoteBefore = w.remote.toMap()
            w.apply(w.plan())
            for (s in w.local.values) {
                val mediaId = w.matches[s.showId]?.mediaId ?: continue
                val r = w.remote[mediaId] ?: continue
                if (AniListMerge.toAniList(s.watchType) == null) continue
                assertEquals(AniListMerge.normalize(r.status), AniListMerge.toAniList(s.watchType), "case $i show ${s.showId}")
                assertEquals(AniListMerge.normalize(r.status), w.matches[s.showId]?.base, "case $i base ${s.showId}")
            }
            // Nothing ever disappears from AniList, and untouched media ids are unchanged.
            assertTrue(w.remote.keys.containsAll(remoteBefore.keys))
        }
    }

    @Test
    fun planNeverTouchesShowsOrMediaItShouldIgnore() {
        val rnd = Random(9)
        repeat(3000) {
            val w = randomWorld(rnd)
            val p = w.plan()
            val matchedLocal = w.local.values.filter { w.matches[it.showId]?.mediaId != null && it.watchType in 0..4 }
            val ids = matchedLocal.map { it.showId }.toSet()
            val media = matchedLocal.map { w.matches[it.showId]!!.mediaId!! }.toSet()
            for (a in p.actions) when (a) {
                is Push -> {
                    assertTrue(a.mediaId in media)
                    assertTrue(ids.containsAll(a.showIds))
                    assertTrue(a.status in listOf(CUR, COM, PAU, DRO, PLA))
                }
                is SetLocal -> {
                    assertTrue(a.showId in ids)
                    assertEquals(w.matches[a.showId]!!.mediaId, a.mediaId)
                    assertEquals(AniListMerge.toWatchType(a.status), a.watchType)
                }
            }
            assertTrue(ids.containsAll(p.settled.keys))
            assertTrue(p.actions.filterIsInstance<Push>().groupBy { it.mediaId }.all { it.value.size == 1 })
        }
    }

    @Test
    fun failedPushIsRetriedNotReverted() {
        val rnd = Random(11)
        repeat(5000) { i ->
            val w = randomWorld(rnd)
            val p1 = w.plan()
            val pushes = p1.actions.filterIsInstance<Push>()
            if (pushes.isEmpty()) return@repeat
            w.apply(p1, pushFails = true)
            val p2 = w.plan()
            for (push in pushes) {
                val again = p2.actions.filterIsInstance<Push>().singleOrNull { it.mediaId == push.mediaId }
                assertEquals(push.status, again?.status, "case $i: $p1 then $p2")
                assertTrue(p2.actions.none { it is SetLocal && it.mediaId == push.mediaId }, "case $i: $p2")
            }
            w.apply(p2)
            assertTrue(w.plan().actions.isEmpty())
        }
    }

    @Test
    fun laterChangesOnEitherSideAreFollowedOverSeveralRounds() {
        val w = World(
            listOf(show("a", PLAN, Entry.INITIAL_T), show("b", PLAN, Entry.INITIAL_T)),
            mapOf("a" to match(10), "b" to match(10)),
            mapOf(10 to rem(CUR, NOW - 100_000)),
        )
        w.apply(w.plan())
        assertEquals(WATCHING, w.local["a"]!!.watchType)
        assertEquals(WATCHING, w.local["b"]!!.watchType)
        assertEquals(CUR, w.matches["a"]!!.base)

        // User completes it locally on provider "b".
        w.local["b"] = show("b", COMPLETED_WT, ++w.clock)
        w.apply(w.plan())
        assertEquals(COM, w.remote[10]!!.status)
        assertEquals(COMPLETED_WT, w.local["a"]!!.watchType)

        // User starts a rewatch on AniList: local becomes Watching, base CURRENT.
        w.remote[10] = rem(REP, ++w.clock)
        w.apply(w.plan())
        assertEquals(WATCHING, w.local["a"]!!.watchType)
        assertEquals(REP, w.remote[10]!!.status)
        assertTrue(w.plan().actions.isEmpty())

        // User removes it from the AniList list: nothing happens, nothing is re-pushed.
        w.remote.remove(10)
        assertTrue(w.plan().actions.isEmpty())
        // Later local change pushes it again.
        w.local["a"] = show("a", ONHOLD, ++w.clock)
        val p = w.plan()
        assertEquals(listOf(Push(10, PAU, listOf("a")), SetLocal("b", ONHOLD, 10, PAU)), p.actions)
    }
}
