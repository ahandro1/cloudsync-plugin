package com.cloudsync.core

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun live(t: Long, v: String, ty: String = "s") = Entry(t = t, ty = ty, v = v, d = false)
private fun tomb(t: Long, ty: String = "s") = Entry(t = t, ty = ty, v = null, d = true)
private fun typed(v: String, ty: String = "s") = Typed(ty, v)

private const val T0 = 1_700_000_000_000L
private const val K1 = "p/video_pos_dur/1"
private const val K2 = "p/video_pos_dur/2"
private const val K3 = "p/result_watch_state/3"
private const val H1 = "h/1"

/** In-memory stand-in for the remote profile file. Uploads are atomic (sequential test). */
private class Server {
    var entries: Map<String, Entry> = emptyMap()
    var uploads = 0
}

/** One device running the sync algorithm exactly as the plugin is meant to. */
private class Device(
    val name: String,
    initial: Map<String, Typed> = emptyMap(),
    var skew: Long = 0,
) {
    val prefs = LinkedHashMap(initial)
    var journal: Map<String, Entry>? = null
    val pending = LinkedHashMap<String, Long>()

    fun set(key: String, v: String, realNow: Long, ty: String = "s") {
        prefs[key] = Typed(ty, v)
        pending[key] = realNow + skew
    }

    fun del(key: String, realNow: Long) {
        if (prefs.remove(key) != null) pending[key] = realNow + skew
    }

    /** Change that happened while the listener was not running: no pending time. */
    fun setSilently(key: String, v: String) {
        prefs[key] = typed(v)
    }

    fun sync(server: Server, realNow: Long) {
        val scan = prefs.toMap()
        val local = Merge.reconcile(journal, scan, pending.toMap(), realNow + skew)
        val remote = server.entries
        val merged = Merge.merge(local, remote)
        for (c in Merge.changesToApply(scan, merged)) {
            if (prefs[c.relKey] != c.expected) continue
            if (c.entry.d) prefs.remove(c.relKey) else prefs[c.relKey] = Typed(c.entry.ty, c.entry.v!!)
        }
        journal = merged
        pending.clear()
        if (merged != remote) {
            server.entries = merged
            server.uploads++
        }
    }
}

class MergeTest {

    // ---------------------------------------------------------------- reconcile: present keys

    @Test
    fun reconcileKeepsUnchangedJournalEntry() {
        val journal = mapOf(K1 to live(500, "a"))
        val out = Merge.reconcile(journal, mapOf(K1 to typed("a")), emptyMap(), T0)
        assertEquals(mapOf(K1 to live(500, "a")), out)
    }

    @Test
    fun reconcileIgnoresPendingForUnchangedValue() {
        val journal = mapOf(K1 to live(500, "a"))
        val out = Merge.reconcile(journal, mapOf(K1 to typed("a")), mapOf(K1 to T0 - 10), T0)
        assertEquals(live(500, "a"), out[K1])
    }

    @Test
    fun reconcileChangedValueUsesPendingTime() {
        val journal = mapOf(K1 to live(500, "a"))
        val out = Merge.reconcile(journal, mapOf(K1 to typed("b")), mapOf(K1 to T0 - 10), T0)
        assertEquals(live(T0 - 10, "b"), out[K1])
    }

    @Test
    fun reconcileChangedValueWithoutPendingUsesNow() {
        val journal = mapOf(K1 to live(500, "a"))
        val out = Merge.reconcile(journal, mapOf(K1 to typed("b")), emptyMap(), T0)
        assertEquals(live(T0, "b"), out[K1])
    }

    @Test
    fun reconcileTypeChangeIsAChange() {
        val journal = mapOf(K1 to live(500, "1", ty = "i"))
        val out = Merge.reconcile(journal, mapOf(K1 to typed("1", ty = "l")), emptyMap(), T0)
        assertEquals(live(T0, "1", ty = "l"), out[K1])
    }

    @Test
    fun reconcileNewKeyWithJournal() {
        val journal = mapOf(K1 to live(500, "a"))
        val scan = mapOf(K1 to typed("a"), K2 to typed("x"), K3 to typed("y"))
        val out = Merge.reconcile(journal, scan, mapOf(K2 to T0 - 3), T0)
        assertEquals(live(T0 - 3, "x"), out[K2])
        assertEquals(live(T0, "y"), out[K3])
    }

    @Test
    fun reconcileFirstLinkUsesInitialTWithoutPending() {
        val scan = mapOf(K1 to typed("a"), H1 to typed("hdr"))
        val out = Merge.reconcile(null, scan, emptyMap(), T0)
        assertEquals(mapOf(K1 to live(Entry.INITIAL_T, "a"), H1 to live(Entry.INITIAL_T, "hdr")), out)
    }

    @Test
    fun reconcileFirstLinkUsesPendingWhenPresent() {
        val scan = mapOf(K1 to typed("a"), K2 to typed("b"))
        val out = Merge.reconcile(null, scan, mapOf(K1 to T0 - 5), T0)
        assertEquals(live(T0 - 5, "a"), out[K1])
        assertEquals(live(Entry.INITIAL_T, "b"), out[K2])
    }

    @Test
    fun reconcileFirstLinkNeverCreatesTombstones() {
        val out = Merge.reconcile(null, mapOf(K1 to typed("a")), mapOf(K2 to T0 - 5), T0)
        assertEquals(setOf(K1), out.keys)
    }

    @Test
    fun reconcileEmptyFirstLinkIsEmpty() {
        assertEquals(emptyMap(), Merge.reconcile(null, emptyMap(), emptyMap(), T0))
    }

    @Test
    fun reconcileReAddAfterTombstoneIsLive() {
        val journal = mapOf(K1 to tomb(T0 - 100))
        val out = Merge.reconcile(journal, mapOf(K1 to typed("a")), mapOf(K1 to T0 - 50), T0)
        assertEquals(live(T0 - 50, "a"), out[K1])
    }

    // ---------------------------------------------------------------- reconcile: missing keys

    @Test
    fun reconcileLocalDeleteMakesTombstoneAtPendingTime() {
        val journal = mapOf(K1 to live(500, "a", ty = "i"))
        val out = Merge.reconcile(journal, emptyMap(), mapOf(K1 to T0 - 7), T0)
        assertEquals(tomb(T0 - 7, ty = "i"), out[K1])
    }

    @Test
    fun reconcileLocalDeleteWithoutPendingUsesNow() {
        val journal = mapOf(K1 to live(500, "a"))
        val out = Merge.reconcile(journal, emptyMap(), emptyMap(), T0)
        assertEquals(tomb(T0), out[K1])
    }

    @Test
    fun reconcileKeepsExistingTombstoneUnchanged() {
        val journal = mapOf(K1 to tomb(500))
        val out = Merge.reconcile(journal, emptyMap(), mapOf(K1 to T0), T0)
        assertEquals(tomb(500), out[K1])
    }

    @Test
    fun reconcileNeverTombstonesHeaderKeys() {
        val journal = mapOf(H1 to live(500, "hdr"))
        val out = Merge.reconcile(journal, emptyMap(), mapOf(H1 to T0), T0)
        assertEquals(live(500, "hdr"), out[H1])
    }

    @Test
    fun reconcileHeaderAddedAndRemovedBetweenSyncsLeavesNothing() {
        val out = Merge.reconcile(emptyMap(), emptyMap(), mapOf(H1 to T0), T0)
        assertFalse(H1 in out)
    }

    @Test
    fun reconcileAddedAndRemovedBetweenSyncsLeavesTombstone() {
        val out = Merge.reconcile(mapOf(K1 to live(500, "a")), mapOf(K1 to typed("a")), mapOf(K2 to T0 - 9), T0)
        assertEquals(tomb(T0 - 9), out[K2])
        assertEquals(live(500, "a"), out[K1])
    }

    @Test
    fun reconcileAddedAndRemovedWithoutPendingLeavesNothing() {
        val out = Merge.reconcile(emptyMap(), emptyMap(), emptyMap(), T0)
        assertTrue(out.isEmpty())
    }

    @Test
    fun reAddThenDeleteOverJournalTombstoneKeepsOldTombstone() {
        // Documented behaviour from the spec: an existing tombstone is kept as is, even if the key
        // was re-added and removed again between two syncs (pending is ignored here on purpose:
        // the sync's own removals may also show up as pending times).
        val journal = mapOf(K1 to tomb(500))
        val out = Merge.reconcile(journal, emptyMap(), mapOf(K1 to T0), T0)
        assertEquals(tomb(500), out[K1])
    }

    // ---------------------------------------------------------------- reconcile: monotonic t

    @Test
    fun pendingNeverGoesBackwardsOnChange() {
        val journal = mapOf(K1 to live(T0 + 5_000, "a"))
        val out = Merge.reconcile(journal, mapOf(K1 to typed("b")), mapOf(K1 to T0), T0)
        assertEquals(live(T0 + 5_001, "b"), out[K1])
    }

    @Test
    fun pendingNeverGoesBackwardsOnDelete() {
        val journal = mapOf(K1 to live(T0 + 5_000, "a"))
        val out = Merge.reconcile(journal, emptyMap(), mapOf(K1 to T0), T0)
        assertEquals(tomb(T0 + 5_001), out[K1])
    }

    @Test
    fun pendingNeverGoesBackwardsOnReAdd() {
        val journal = mapOf(K1 to tomb(T0 + 5_000))
        val out = Merge.reconcile(journal, mapOf(K1 to typed("a")), mapOf(K1 to T0), T0)
        assertEquals(live(T0 + 5_001, "a"), out[K1])
    }

    @Test
    fun nowBehindJournalStillAdvances() {
        // Journal entry came from a device whose clock is ahead of ours.
        val journal = mapOf(K1 to live(T0 + 3_000, "a"), K2 to live(T0 + 3_000, "x"))
        val out = Merge.reconcile(journal, mapOf(K1 to typed("b")), emptyMap(), T0)
        assertEquals(live(T0 + 3_001, "b"), out[K1])
        assertEquals(tomb(T0 + 3_001), out[K2])
    }

    @Test
    fun realChangeIsNeverConfusedWithInitialT() {
        val journal = mapOf(K1 to live(Entry.INITIAL_T, "a"))
        val out = Merge.reconcile(journal, mapOf(K1 to typed("b"), K2 to typed("c")), mapOf(K1 to 0L, K2 to 1L), 0L)
        assertTrue(out[K1]!!.t > Merge.LINKED_T)
        assertTrue(out[K2]!!.t > Merge.LINKED_T)
        val first = Merge.reconcile(null, mapOf(K1 to typed("a")), mapOf(K1 to Entry.INITIAL_T), T0)
        assertTrue(first[K1]!!.t > Merge.LINKED_T)
    }

    @Test
    fun reconcileChangedLocallyAfterLinkedImportBeatsIt() {
        val journal = mapOf(K1 to live(Merge.LINKED_T, "a"))
        val out = Merge.reconcile(journal, mapOf(K1 to typed("b")), emptyMap(), T0)
        assertEquals(live(T0, "b"), out[K1])
    }

    // ---------------------------------------------------------------- merge: basics

    @Test
    fun mergeHigherTimestampWinsEitherSide() {
        val a = live(200, "a")
        val b = live(100, "b")
        assertEquals(mapOf(K1 to a), Merge.merge(mapOf(K1 to a), mapOf(K1 to b)))
        assertEquals(mapOf(K1 to a), Merge.merge(mapOf(K1 to b), mapOf(K1 to a)))
    }

    @Test
    fun mergeTombstoneVsLiveByTimestamp() {
        assertEquals(tomb(200), Merge.merge(mapOf(K1 to live(100, "a")), mapOf(K1 to tomb(200)))[K1])
        assertEquals(tomb(200), Merge.merge(mapOf(K1 to tomb(200)), mapOf(K1 to live(100, "a")))[K1])
        assertEquals(live(300, "a"), Merge.merge(mapOf(K1 to live(300, "a")), mapOf(K1 to tomb(200)))[K1])
        assertEquals(live(300, "a"), Merge.merge(mapOf(K1 to tomb(200)), mapOf(K1 to live(300, "a")))[K1])
    }

    @Test
    fun mergeTiePrefersRemote() {
        val l = live(100, "local")
        val r = live(100, "remote")
        assertEquals(r, Merge.merge(mapOf(K1 to l), mapOf(K1 to r))[K1])
        assertEquals(l, Merge.merge(mapOf(K1 to r), mapOf(K1 to l))[K1])
        assertEquals(tomb(100), Merge.merge(mapOf(K1 to live(100, "x")), mapOf(K1 to tomb(100)))[K1])
        assertEquals(live(100, "x"), Merge.merge(mapOf(K1 to tomb(100)), mapOf(K1 to live(100, "x")))[K1])
    }

    @Test
    fun mergeKeepsOneSidedKeysIncludingTombstones() {
        val local = mapOf(K1 to live(100, "a"), K2 to tomb(50))
        val remote = mapOf(K3 to live(70, "c"), "p/result_dub/9" to tomb(80))
        val out = Merge.merge(local, remote)
        assertEquals(local + remote, out)
    }

    @Test
    fun mergeOfEmptyMapsIsEmpty() {
        assertTrue(Merge.merge(emptyMap(), emptyMap()).isEmpty())
    }

    @Test
    fun mergeOutputIsSortedByKey() {
        val out = Merge.merge(mapOf("p/z/1" to live(5, "a"), "p/a/1" to live(5, "a")), mapOf("p/m/1" to live(5, "a")))
        assertEquals(listOf("p/a/1", "p/m/1", "p/z/1"), out.keys.toList())
    }

    // ---------------------------------------------------------------- merge: first-link rules

    @Test
    fun initialLiveBeatsTombstoneAndTakesTombstoneTimePlusOne() {
        val out1 = Merge.merge(mapOf(K1 to live(Entry.INITIAL_T, "a")), mapOf(K1 to tomb(T0)))
        assertEquals(live(T0 + 1, "a"), out1[K1])
        val out2 = Merge.merge(mapOf(K1 to tomb(T0)), mapOf(K1 to live(Entry.INITIAL_T, "a")))
        assertEquals(live(T0 + 1, "a"), out2[K1])
    }

    @Test
    fun initialLiveLosesToRealLive() {
        val out = Merge.merge(mapOf(K1 to live(Entry.INITIAL_T, "a")), mapOf(K1 to live(T0, "b")))
        assertEquals(live(T0, "b"), out[K1])
        val out2 = Merge.merge(mapOf(K1 to live(T0, "b")), mapOf(K1 to live(Entry.INITIAL_T, "a")))
        assertEquals(live(T0, "b"), out2[K1])
    }

    @Test
    fun initialVsInitialPrefersRemoteAndIsPromoted() {
        val out = Merge.merge(mapOf(K1 to live(Entry.INITIAL_T, "a")), mapOf(K1 to live(Entry.INITIAL_T, "b")))
        assertEquals(live(Merge.LINKED_T, "b"), out[K1])
    }

    @Test
    fun survivingInitialEntryIsPromotedToLinkedT() {
        val out = Merge.merge(mapOf(K1 to live(Entry.INITIAL_T, "a")), emptyMap())
        assertEquals(live(Merge.LINKED_T, "a"), out[K1])
        val out2 = Merge.merge(emptyMap(), mapOf(K1 to live(Entry.INITIAL_T, "a")))
        assertEquals(live(Merge.LINKED_T, "a"), out2[K1])
    }

    @Test
    fun linkedEntryNoLongerBeatsTombstone() {
        // After the first merge, a delete elsewhere must propagate to the linked device.
        val out = Merge.merge(mapOf(K1 to live(Merge.LINKED_T, "a")), mapOf(K1 to tomb(T0)))
        assertEquals(tomb(T0), out[K1])
    }

    @Test
    fun linkedEntryLosesToRealLive() {
        val out = Merge.merge(mapOf(K1 to live(Merge.LINKED_T, "a")), mapOf(K1 to live(T0, "b")))
        assertEquals(live(T0, "b"), out[K1])
    }

    // ---------------------------------------------------------------- merge: headers

    @Test
    fun headerLiveAlwaysBeatsTombstone() {
        assertEquals(live(100, "h"), Merge.merge(mapOf(H1 to live(100, "h")), mapOf(H1 to tomb(T0)))[H1])
        assertEquals(live(100, "h"), Merge.merge(mapOf(H1 to tomb(T0)), mapOf(H1 to live(100, "h")))[H1])
    }

    @Test
    fun headerLoneTombstoneIsDropped() {
        assertFalse(H1 in Merge.merge(mapOf(H1 to tomb(5)), emptyMap()))
        assertFalse(H1 in Merge.merge(emptyMap(), mapOf(H1 to tomb(5))))
        assertFalse(H1 in Merge.merge(mapOf(H1 to tomb(5)), mapOf(H1 to tomb(9))))
    }

    @Test
    fun headerLiveVsLiveNewerWins() {
        assertEquals(live(200, "new"), Merge.merge(mapOf(H1 to live(200, "new")), mapOf(H1 to live(100, "old")))[H1])
        assertEquals(live(200, "new"), Merge.merge(mapOf(H1 to live(100, "old")), mapOf(H1 to live(200, "new")))[H1])
        assertEquals(live(100, "r"), Merge.merge(mapOf(H1 to live(100, "l")), mapOf(H1 to live(100, "r")))[H1])
    }

    // ---------------------------------------------------------------- merge: algebraic properties

    private fun randomEntries(rnd: Random, keys: List<String>, distinctT: Boolean, tBase: Long): Map<String, Entry> {
        val out = LinkedHashMap<String, Entry>()
        for ((i, k) in keys.withIndex()) {
            if (rnd.nextInt(4) == 0) continue
            val t = when (rnd.nextInt(6)) {
                0 -> Entry.INITIAL_T
                1 -> Merge.LINKED_T
                else -> if (distinctT) tBase + i * 1000 + rnd.nextLong(0, 300) else 100L + rnd.nextInt(4)
            }
            out[k] = if (rnd.nextInt(3) == 0) tomb(t) else live(t, "v" + rnd.nextInt(3))
        }
        return out
    }

    private val propKeys = listOf(K1, K2, K3, "p/result_dub/4", "p/result_season/5", H1, "h/2")

    @Test
    fun mergeIsIdempotent() {
        val rnd = Random(1)
        repeat(3000) {
            val l = randomEntries(rnd, propKeys, distinctT = false, tBase = 0)
            val r = randomEntries(rnd, propKeys, distinctT = false, tBase = 0)
            val m = Merge.merge(l, r)
            assertEquals(m, Merge.merge(m, m), "merge(m, m) for l=$l r=$r")
            assertEquals(m, Merge.merge(m, r), "merge(merge(l, r), r) for l=$l r=$r")
            assertEquals(m, Merge.merge(l, m), "merge(l, merge(l, r)) for l=$l r=$r")
            assertEquals(m, Merge.merge(Merge.merge(l, r), Merge.merge(l, r)))
        }
    }

    @Test
    fun mergeIsCommutativeWithoutTies() {
        val rnd = Random(2)
        repeat(3000) {
            // Real timestamps on the two sides never collide; INITIAL_T / LINKED_T may.
            val l = randomEntries(rnd, propKeys, distinctT = true, tBase = 10_000)
            val r = randomEntries(rnd, propKeys, distinctT = true, tBase = 10_000 + 500)
                .filterValues { it.t > Merge.LINKED_T || it.d }
            val lf = l.filterValues { it.t > Merge.LINKED_T || it.d }
            assertEquals(Merge.merge(lf, r), Merge.merge(r, lf), "l=$lf r=$r")
        }
    }

    @Test
    fun mergeIsAssociativeWithoutTies() {
        val rnd = Random(3)
        repeat(2000) {
            val a = randomEntries(rnd, propKeys, distinctT = true, tBase = 10_000).filterValues { it.t > Merge.LINKED_T }
            val b = randomEntries(rnd, propKeys, distinctT = true, tBase = 10_000 + 666).filterValues { it.t > Merge.LINKED_T }
            val c = randomEntries(rnd, propKeys, distinctT = true, tBase = 10_000 + 333).filterValues { it.t > Merge.LINKED_T }
            assertEquals(Merge.merge(Merge.merge(a, b), c), Merge.merge(a, Merge.merge(b, c)))
        }
    }

    @Test
    fun mergeNeverOutputsInitialTLiveOrHeaderTombstone() {
        val rnd = Random(4)
        repeat(3000) {
            val m = Merge.merge(
                randomEntries(rnd, propKeys, distinctT = false, tBase = 0),
                randomEntries(rnd, propKeys, distinctT = false, tBase = 0),
            )
            for ((k, e) in m) {
                assertFalse(!e.d && e.t == Entry.INITIAL_T, "INITIAL_T survived at $k")
                assertFalse(Keys.isHeader(k) && e.d, "header tombstone at $k")
            }
        }
    }

    @Test
    fun mergeNeverLosesAKeyThatIsLiveOnEitherSide() {
        val rnd = Random(5)
        repeat(3000) {
            val l = randomEntries(rnd, propKeys, distinctT = false, tBase = 0)
            val r = randomEntries(rnd, propKeys, distinctT = false, tBase = 0)
            val m = Merge.merge(l, r)
            for (k in l.keys + r.keys) {
                if (l[k]?.d == false || r[k]?.d == false || !Keys.isHeader(k)) assertTrue(k in m, "lost $k")
            }
        }
    }

    // ---------------------------------------------------------------- changesToApply

    @Test
    fun changesRemoveLocalKeyForTombstone() {
        val scan = mapOf(K1 to typed("a"))
        val merged = mapOf(K1 to tomb(T0))
        assertEquals(listOf(Merge.Change(K1, typed("a"), tomb(T0))), Merge.changesToApply(scan, merged))
    }

    @Test
    fun changesIgnoreTombstoneForAbsentKey() {
        assertTrue(Merge.changesToApply(emptyMap(), mapOf(K1 to tomb(T0))).isEmpty())
    }

    @Test
    fun changesNeverRemoveHeaderKeys() {
        assertTrue(Merge.changesToApply(mapOf(H1 to typed("h")), mapOf(H1 to tomb(T0))).isEmpty())
    }

    @Test
    fun changesWriteMissingLiveKey() {
        val e = live(T0, "a")
        assertEquals(listOf(Merge.Change(K1, null, e)), Merge.changesToApply(emptyMap(), mapOf(K1 to e)))
    }

    @Test
    fun changesWriteMissingHeaderKey() {
        val e = live(T0, "h")
        assertEquals(listOf(Merge.Change(H1, null, e)), Merge.changesToApply(emptyMap(), mapOf(H1 to e)))
    }

    @Test
    fun changesWriteDifferentValueOrType() {
        val e1 = live(T0, "b")
        val e2 = live(T0, "1", ty = "l")
        val scan = mapOf(K1 to typed("a"), K2 to typed("1", ty = "i"))
        assertEquals(
            listOf(Merge.Change(K1, typed("a"), e1), Merge.Change(K2, typed("1", ty = "i"), e2)),
            Merge.changesToApply(scan, mapOf(K1 to e1, K2 to e2)),
        )
    }

    @Test
    fun changesNothingWhenEqual() {
        val scan = mapOf(K1 to typed("a"), K2 to typed("[\"x\"]", ty = "ss"))
        val merged = mapOf(K1 to live(T0, "a"), K2 to live(5, "[\"x\"]", ty = "ss"))
        assertTrue(Merge.changesToApply(scan, merged).isEmpty())
    }

    @Test
    fun changesSkipMalformedLiveEntryWithoutValue() {
        val bad = Entry(t = T0, ty = "s", v = null, d = false)
        assertTrue(Merge.changesToApply(mapOf(K1 to typed("a")), mapOf(K1 to bad)).isEmpty())
        assertTrue(Merge.changesToApply(emptyMap(), mapOf(K1 to bad)).isEmpty())
    }

    @Test
    fun changesIgnoreScanKeysNotInMerged() {
        assertTrue(Merge.changesToApply(mapOf(K1 to typed("a")), emptyMap()).isEmpty())
    }

    // ---------------------------------------------------------------- prune

    @Test
    fun pruneDropsOnlyOldTombstones() {
        val max = Merge.DEFAULT_MAX_TOMBSTONE_AGE_MS
        val entries = mapOf(
            K1 to tomb(T0 - max - 1),
            K2 to tomb(T0 - max),
            K3 to tomb(T0 - 10),
            "p/result_dub/1" to live(1, "ancient"),
            "p/result_dub/2" to live(Entry.INITIAL_T, "x"),
            "p/result_dub/3" to tomb(T0 + 50_000),
        )
        val out = Merge.prune(entries, T0)
        assertEquals(entries - K1, out)
    }

    @Test
    fun pruneUsesDefault180Days() {
        assertEquals(180L * 24 * 3600 * 1000, Merge.DEFAULT_MAX_TOMBSTONE_AGE_MS)
        val day = 24L * 3600 * 1000
        val out = Merge.prune(mapOf(K1 to tomb(T0 - 181 * day), K2 to tomb(T0 - 179 * day)), T0)
        assertEquals(setOf(K2), out.keys)
    }

    @Test
    fun pruneHonoursCustomAge() {
        val out = Merge.prune(mapOf(K1 to tomb(T0 - 11), K2 to tomb(T0 - 10), K3 to live(0, "a")), T0, 10)
        assertEquals(setOf(K2, K3), out.keys)
    }

    // ---------------------------------------------------------------- end-to-end scenarios

    private fun assertConverged(server: Server, vararg devices: Device) {
        val expected = server.entries.filterValues { !it.d && it.v != null }.mapValues { Typed(it.value.ty, it.value.v!!) }
        for (d in devices) {
            assertEquals(expected, d.prefs, "device ${d.name} differs from remote")
            assertEquals(server.entries, d.journal, "device ${d.name} journal differs from remote")
        }
    }

    private fun settle(server: Server, now: Long, vararg devices: Device) {
        repeat(2) { round -> devices.forEachIndexed { i, d -> d.sync(server, now + round * 100 + i) } }
    }

    @Test
    fun twoDevicesPropagateSetChangeAndDelete() {
        val s = Server()
        val a = Device("A")
        val b = Device("B")
        a.sync(s, T0); b.sync(s, T0 + 1)
        a.set(K1, "10", T0 + 1000)
        a.sync(s, T0 + 1100)
        b.sync(s, T0 + 1200)
        assertEquals(typed("10"), b.prefs[K1])
        b.set(K1, "20", T0 + 2000)
        b.sync(s, T0 + 2100); a.sync(s, T0 + 2200)
        assertEquals(typed("20"), a.prefs[K1])
        a.del(K1, T0 + 3000)
        a.sync(s, T0 + 3100); b.sync(s, T0 + 3200)
        assertNull(b.prefs[K1])
        assertTrue(s.entries[K1]!!.d)
        assertConverged(s, a, b)
    }

    @Test
    fun syncWithNothingNewDoesNotUpload() {
        val s = Server()
        val a = Device("A", mapOf(K1 to typed("1")))
        a.sync(s, T0)
        val uploads = s.uploads
        a.sync(s, T0 + 10); a.sync(s, T0 + 20)
        assertEquals(uploads, s.uploads)
    }

    @Test
    fun changesWhileListenerWasOffAreStillSynced() {
        val s = Server()
        val a = Device("A", mapOf(K1 to typed("1")))
        val b = Device("B")
        a.sync(s, T0); b.sync(s, T0 + 1)
        a.setSilently(K1, "2")
        a.sync(s, T0 + 5000); b.sync(s, T0 + 6000)
        assertEquals(typed("2"), b.prefs[K1])
        assertEquals(T0 + 5000, s.entries[K1]!!.t)
    }

    @Test
    fun clockSkewDoesNotLoseAChangeOnTopOfASyncedValue() {
        val s = Server()
        val a = Device("A", skew = +4_000)
        val b = Device("B", skew = -4_000)
        a.sync(s, T0); b.sync(s, T0)
        a.set(K1, "a", T0 + 1000)
        a.sync(s, T0 + 1100)
        b.sync(s, T0 + 1200)
        // B edits after it has seen A's value; its clock says T0-2000, far behind A's T0+5000.
        b.set(K1, "b", T0 + 2000)
        b.sync(s, T0 + 2100)
        a.sync(s, T0 + 2200)
        assertEquals(typed("b"), a.prefs[K1])
        assertEquals(T0 + 5001, s.entries[K1]!!.t)
        // And deletion on top of it as well.
        b.del(K1, T0 + 3000)
        b.sync(s, T0 + 3100); a.sync(s, T0 + 3200)
        assertNull(a.prefs[K1])
        assertConverged(s, a, b)
    }

    @Test
    fun clockSkewOnConcurrentEditsStillConverges() {
        // Concurrent edits (neither device saw the other's value): plain LWW by device clocks.
        val s = Server()
        val a = Device("A", skew = +3_000)
        val b = Device("B", skew = -3_000)
        a.sync(s, T0); b.sync(s, T0)
        a.set(K1, "a", T0 + 1000)  // stamped T0+4000
        b.set(K1, "b", T0 + 2000)  // stamped T0-1000: really later, but loses
        b.sync(s, T0 + 2100); a.sync(s, T0 + 2200); b.sync(s, T0 + 2300)
        assertEquals(typed("a"), b.prefs[K1])
        assertConverged(s, a, b)
    }

    @Test
    fun deleteThenReAddOnSameDeviceBeforeSync() {
        val s = Server()
        val a = Device("A", mapOf(K1 to typed("1")))
        val b = Device("B")
        a.sync(s, T0); b.sync(s, T0 + 1)
        a.del(K1, T0 + 1000)
        a.set(K1, "2", T0 + 2000)
        a.sync(s, T0 + 3000); b.sync(s, T0 + 3100)
        assertEquals(typed("2"), b.prefs[K1])
        assertConverged(s, a, b)
    }

    @Test
    fun deleteSyncThenReAdd() {
        val s = Server()
        val a = Device("A", mapOf(K1 to typed("1")))
        val b = Device("B")
        a.sync(s, T0); b.sync(s, T0 + 1)
        a.del(K1, T0 + 1000); a.sync(s, T0 + 1100); b.sync(s, T0 + 1200)
        assertNull(b.prefs[K1])
        b.set(K1, "1", T0 + 2000); b.sync(s, T0 + 2100); a.sync(s, T0 + 2200)
        assertEquals(typed("1"), a.prefs[K1])
        assertConverged(s, a, b)
    }

    @Test
    fun addThenDeleteBetweenSyncsDeletesOlderRemoteValue() {
        val s = Server()
        val a = Device("A")
        val b = Device("B")
        a.sync(s, T0); b.sync(s, T0 + 1)
        b.set(K1, "fromB", T0 + 1000); b.sync(s, T0 + 1100)
        a.set(K1, "fromA", T0 + 2000) // A has not seen B's value yet
        a.del(K1, T0 + 3000)
        a.sync(s, T0 + 4000); b.sync(s, T0 + 4100)
        assertNull(a.prefs[K1]); assertNull(b.prefs[K1])
        assertConverged(s, a, b)
    }

    private fun reAddVsDelete(reAddLater: Boolean, reAdderSyncsFirst: Boolean) {
        val s = Server()
        val a = Device("A", mapOf(K1 to typed("1")))
        val b = Device("B")
        a.sync(s, T0); b.sync(s, T0 + 1)
        assertEquals(typed("1"), b.prefs[K1])
        val tDel = T0 + 1000
        val tSet = if (reAddLater) T0 + 2000 else T0 + 500
        a.del(K1, tDel)
        b.set(K1, "2", tSet)
        if (reAdderSyncsFirst) { b.sync(s, T0 + 3000); a.sync(s, T0 + 3100) }
        else { a.sync(s, T0 + 3000); b.sync(s, T0 + 3100) }
        settle(s, T0 + 4000, a, b)
        if (reAddLater) {
            assertEquals(typed("2"), a.prefs[K1])
        } else {
            assertNull(a.prefs[K1])
            assertNull(b.prefs[K1])
        }
        assertConverged(s, a, b)
    }

    @Test fun reAddAfterOtherDeviceDeletesWinsSetterFirst() = reAddVsDelete(reAddLater = true, reAdderSyncsFirst = true)
    @Test fun reAddAfterOtherDeviceDeletesWinsDeleterFirst() = reAddVsDelete(reAddLater = true, reAdderSyncsFirst = false)
    @Test fun deleteAfterOtherDeviceEditsWinsSetterFirst() = reAddVsDelete(reAddLater = false, reAdderSyncsFirst = true)
    @Test fun deleteAfterOtherDeviceEditsWinsDeleterFirst() = reAddVsDelete(reAddLater = false, reAdderSyncsFirst = false)

    private fun bothOffline(aFirst: Boolean) {
        val s = Server()
        val a = Device("A", mapOf(K1 to typed("1"), K2 to typed("x"), K3 to typed("q")))
        val b = Device("B")
        a.sync(s, T0); b.sync(s, T0 + 1)
        // Offline period
        a.set(K1, "a-early", T0 + 1000)
        b.set(K1, "b-late", T0 + 2000)
        b.set(K2, "b-early", T0 + 1500)
        a.set(K2, "a-late", T0 + 2500)
        a.del(K3, T0 + 1200)
        b.set("p/result_dub/7", "new", T0 + 1300)
        if (aFirst) { a.sync(s, T0 + 5000); b.sync(s, T0 + 5100); a.sync(s, T0 + 5200) }
        else { b.sync(s, T0 + 5000); a.sync(s, T0 + 5100); b.sync(s, T0 + 5200) }
        for (d in listOf(a, b)) {
            assertEquals(typed("b-late"), d.prefs[K1])
            assertEquals(typed("a-late"), d.prefs[K2])
            assertNull(d.prefs[K3])
            assertEquals(typed("new"), d.prefs["p/result_dub/7"])
        }
        assertConverged(s, a, b)
    }

    @Test fun bothOfflineThenASyncsFirst() = bothOffline(aFirst = true)
    @Test fun bothOfflineThenBSyncsFirst() = bothOffline(aFirst = false)

    @Test
    fun firstLinkAgainstEmptyRemoteUploadsEverything() {
        val s = Server()
        val a = Device("A", mapOf(K1 to typed("1"), H1 to typed("hdr")))
        a.sync(s, T0)
        assertEquals(mapOf(H1 to live(Merge.LINKED_T, "hdr"), K1 to live(Merge.LINKED_T, "1")), s.entries)
        assertEquals(mapOf(K1 to typed("1"), H1 to typed("hdr")), a.prefs)
    }

    @Test
    fun firstLinkAgainstPopulatedRemoteIsAUnion() {
        val s = Server()
        val a = Device("A", mapOf(K1 to typed("a1"), K2 to typed("a2"), K3 to typed("gone")))
        a.sync(s, T0)
        a.set(K1, "a1-edited", T0 + 1000)
        a.del(K3, T0 + 1100)
        a.sync(s, T0 + 1200)
        assertTrue(s.entries[K3]!!.d)

        // B already has data of its own, including the key A deleted.
        val b = Device("B", mapOf(K1 to typed("b1"), K3 to typed("b3"), "p/result_dub/9" to typed("b9")))
        b.sync(s, T0 + 2000)
        // Conflict on K1: A's real change beats B's import.
        assertEquals(typed("a1-edited"), b.prefs[K1])
        // Remote-only key arrives.
        assertEquals(typed("a2"), b.prefs[K2])
        // Linking never deletes what exists on the new device, even against a tombstone.
        assertEquals(typed("b3"), b.prefs[K3])
        assertEquals(typed("b9"), b.prefs["p/result_dub/9"])
        a.sync(s, T0 + 2100)
        assertEquals(typed("b3"), a.prefs[K3])
        assertEquals(typed("b9"), a.prefs["p/result_dub/9"])
        assertConverged(s, a, b)
    }

    @Test
    fun firstLinkTwoImportsOfSameKeyConvergeOnTheFirstDevicesValue() {
        val s = Server()
        val a = Device("A", mapOf(K1 to typed("a")))
        val b = Device("B", mapOf(K1 to typed("b")))
        a.sync(s, T0); b.sync(s, T0 + 10); a.sync(s, T0 + 20)
        assertEquals(typed("a"), b.prefs[K1])
        assertConverged(s, a, b)
    }

    @Test
    fun deleteOnOtherDevicePropagatesToALinkedDevice() {
        // Regression: a first-link import must not keep beating tombstones after it was synced.
        val s = Server()
        val b = Device("B", mapOf(K1 to typed("1")))
        val a = Device("A")
        b.sync(s, T0)       // first link, K1 imported
        a.sync(s, T0 + 10)  // A receives K1
        assertEquals(typed("1"), a.prefs[K1])
        a.del(K1, T0 + 1000)
        a.sync(s, T0 + 1100)
        b.sync(s, T0 + 1200)
        assertNull(b.prefs[K1])
        assertConverged(s, a, b)
    }

    @Test
    fun linkedImportLosesToAnyLaterRealEdit() {
        val s = Server()
        val b = Device("B", mapOf(K1 to typed("1")))
        val a = Device("A")
        b.sync(s, T0); a.sync(s, T0 + 10)
        a.set(K1, "2", T0 + 1000)
        a.sync(s, T0 + 1100); b.sync(s, T0 + 1200)
        assertEquals(typed("2"), b.prefs[K1])
    }

    @Test
    fun relinkingADeviceWithExistingDataUnionsWithRemote() {
        // Journal lost (reinstall): the device links again with whatever it still has.
        val s = Server()
        val a = Device("A", mapOf(K1 to typed("1"), K2 to typed("2")))
        a.sync(s, T0)
        a.set(K1, "1b", T0 + 1000); a.sync(s, T0 + 1100)
        a.journal = null
        a.prefs[K3] = typed("3")
        a.sync(s, T0 + 2000)
        assertEquals(mapOf(K1 to typed("1b"), K2 to typed("2"), K3 to typed("3")), a.prefs)
        assertEquals(T0 + 1000, s.entries[K1]!!.t)
    }

    @Test
    fun threeDevices() {
        val s = Server()
        val a = Device("A", mapOf(K1 to typed("a1")))
        val b = Device("B", mapOf(K2 to typed("b2")))
        val c = Device("C")
        a.sync(s, T0); b.sync(s, T0 + 1); c.sync(s, T0 + 2); a.sync(s, T0 + 3)
        assertConverged(s, a, b, c)

        a.set(K1, "a1'", T0 + 1000)
        b.set(K1, "b1", T0 + 1500)
        c.del(K2, T0 + 1200)
        b.set(K2, "b2'", T0 + 1100) // before C's delete
        c.set(K3, "c3", T0 + 1300)
        a.del(K3, T0 + 100)         // nothing to delete on A
        c.sync(s, T0 + 2000); a.sync(s, T0 + 2100); b.sync(s, T0 + 2200)
        settle(s, T0 + 3000, a, b, c)
        for (d in listOf(a, b, c)) {
            assertEquals(typed("b1"), d.prefs[K1])
            assertNull(d.prefs[K2])
            assertEquals(typed("c3"), d.prefs[K3])
        }
        assertConverged(s, a, b, c)
    }

    @Test
    fun headerKeysAreAddUpdateOnly() {
        val s = Server()
        val a = Device("A", mapOf(H1 to typed("v1")))
        val b = Device("B")
        a.sync(s, T0); b.sync(s, T0 + 1)
        assertEquals(typed("v1"), b.prefs[H1])
        b.set(H1, "v2", T0 + 1000)
        b.sync(s, T0 + 1100); a.sync(s, T0 + 1200)
        assertEquals(typed("v2"), a.prefs[H1])
        // Removing a header locally never propagates as a delete; the value is restored.
        a.del(H1, T0 + 2000)
        a.sync(s, T0 + 2100)
        assertEquals(typed("v2"), a.prefs[H1])
        assertFalse(s.entries[H1]!!.d)
        b.sync(s, T0 + 2200)
        assertEquals(typed("v2"), b.prefs[H1])
    }

    @Test
    fun compareAndSetProtectsAValueChangedDuringSync() {
        val s = Server()
        val a = Device("A", mapOf(K1 to typed("1")))
        val b = Device("B")
        a.sync(s, T0); b.sync(s, T0 + 1)
        a.set(K1, "2", T0 + 1000); a.sync(s, T0 + 1100)

        // B scans, then the user changes K1 before the change is applied.
        val scan = b.prefs.toMap()
        val merged = Merge.merge(Merge.reconcile(b.journal, scan, b.pending, T0 + 2000), s.entries)
        b.set(K1, "user", T0 + 2001)
        for (c in Merge.changesToApply(scan, merged)) {
            if (b.prefs[c.relKey] != c.expected) continue
            if (c.entry.d) b.prefs.remove(c.relKey) else b.prefs[c.relKey] = Typed(c.entry.ty, c.entry.v!!)
        }
        b.journal = merged
        // pending is NOT cleared for a key whose write was skipped; next sync pushes the user's value.
        assertEquals(typed("user"), b.prefs[K1])
        b.sync(s, T0 + 3000); a.sync(s, T0 + 3100)
        assertEquals(typed("user"), a.prefs[K1])
    }

    @Test
    fun prunedTombstoneDoesNotResurrectOnUpToDateDevices() {
        val s = Server()
        val a = Device("A", mapOf(K1 to typed("1")))
        val b = Device("B")
        a.sync(s, T0); b.sync(s, T0 + 1)
        a.del(K1, T0 + 1000); a.sync(s, T0 + 1100); b.sync(s, T0 + 1200)
        val later = T0 + Merge.DEFAULT_MAX_TOMBSTONE_AGE_MS + 10_000
        s.entries = Merge.prune(s.entries, later)
        assertFalse(K1 in s.entries)
        a.sync(s, later); b.sync(s, later + 1)
        // Journals still carry the tombstone, which re-uploads it; that is harmless.
        assertNull(a.prefs[K1]); assertNull(b.prefs[K1])
    }

    // ---------------------------------------------------------------- randomized simulations

    /**
     * Random sets / deletes on several devices with a perfect clock and globally unique values,
     * syncing at random moments. Afterwards every device must hold, for every key, the result of
     * the last operation on that key (last writer wins), and all devices must agree.
     */
    @Test
    fun randomizedSimulationLastWriterWins() {
        val keys = listOf(K1, K2, K3, "p/result_dub/4", "p/result_season/5", "p/result_episode/6")
        for (seed in 0 until 300) {
            val rnd = Random(seed)
            val s = Server()
            val devices = List(2 + rnd.nextInt(3)) { Device("D$it") }
            val oracle = HashMap<String, String?>()
            var now = T0
            var counter = 0
            devices.forEach { it.sync(s, now++) }
            repeat(60) {
                now += 1 + rnd.nextLong(0, 5_000)
                val d = devices[rnd.nextInt(devices.size)]
                when (rnd.nextInt(10)) {
                    in 0..4 -> {
                        val k = keys[rnd.nextInt(keys.size)]
                        val v = "v${counter++}"
                        d.set(k, v, now)
                        oracle[k] = v
                    }
                    in 5..6 -> {
                        val k = keys[rnd.nextInt(keys.size)]
                        // Known limitation (see reAddThenDeleteOverJournalTombstone): a delete
                        // on top of a journal tombstone keeps the old tombstone time.
                        if (k in d.prefs && d.journal?.get(k)?.d != true) {
                            d.del(k, now)
                            oracle[k] = null
                        }
                    }
                    else -> d.sync(s, now)
                }
            }
            now += 10_000
            settle(s, now, *devices.toTypedArray())
            val expected = oracle.filterValues { it != null }.mapValues { typed(it.value!!) }
            for (d in devices) assertEquals(expected, d.prefs, "seed $seed device ${d.name}")
            assertConverged(s, *devices.toTypedArray())
        }
    }

    /**
     * Same with clock skew, devices that link late with their own data, silent changes and
     * header keys: no oracle, but all devices must converge, and the remote must be stable.
     */
    @Test
    fun randomizedSimulationConvergesWithSkewAndLateLinks() {
        val keys = listOf(K1, K2, K3, "p/result_dub/4", H1, "h/2")
        for (seed in 0 until 300) {
            val rnd = Random(1000 + seed)
            val s = Server()
            val devices = List(2 + rnd.nextInt(3)) { i ->
                val initial = keys.filter { rnd.nextInt(3) == 0 }.associateWith { typed("init$i-$it") }
                Device("D$i", initial, skew = rnd.nextLong(-5_000, 5_000))
            }
            var now = T0
            var counter = 0
            repeat(80) {
                now += 1 + rnd.nextLong(0, 3_000)
                val d = devices[rnd.nextInt(devices.size)]
                val k = keys[rnd.nextInt(keys.size)]
                when (rnd.nextInt(10)) {
                    in 0..3 -> d.set(k, "v${counter++}", now)
                    4 -> d.setSilently(k, "s${counter++}")
                    in 5..6 -> d.del(k, now)
                    else -> d.sync(s, now)
                }
            }
            now += 10_000
            settle(s, now, *devices.toTypedArray())
            assertConverged(s, *devices.toTypedArray())
            val uploads = s.uploads
            settle(s, now + 10_000, *devices.toTypedArray())
            assertEquals(uploads, s.uploads, "seed $seed: remote not stable")
        }
    }

    /** Values that exist on a device when it links, and are never touched again, survive. */
    @Test
    fun randomizedFirstLinksNeverDropUntouchedData() {
        val keys = (1..8).map { "p/video_pos_dur/$it" }
        for (seed in 0 until 300) {
            val rnd = Random(5000 + seed)
            val s = Server()
            val devices = List(3) { i ->
                Device("D$i", keys.filter { rnd.nextBoolean() }.associateWith { typed("d$i") })
            }
            val existing = devices.flatMap { it.prefs.keys }.toSet()
            // Devices link in random order; some keys get deleted by an already linked device.
            var now = T0
            val linked = mutableListOf<Device>()
            val touched = HashSet<String>()
            for (d in devices.shuffled(rnd)) {
                now += 1000
                if (linked.isNotEmpty() && rnd.nextBoolean()) {
                    val x = linked[rnd.nextInt(linked.size)]
                    val k = x.prefs.keys.randomOrNull(rnd)
                    if (k != null) { x.del(k, now); x.sync(s, now + 1); touched += k }
                }
                d.sync(s, now + 2)
                linked += d
            }
            settle(s, now + 10_000, *devices.toTypedArray())
            assertConverged(s, *devices.toTypedArray())
            for (k in existing - touched) assertTrue(k in devices[0].prefs, "seed $seed lost $k")
        }
    }
}
