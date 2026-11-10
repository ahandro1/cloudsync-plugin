package com.cloudsync.core

/**
 * Last-writer-wins sync of preference entries between this device and the remote profile file.
 *
 * One sync:
 *   local   = reconcile(journal, scan, pending, now)
 *   merged  = merge(local, remote)
 *   changesToApply(scan, merged) -> written to SharedPreferences (compare-and-set on `expected`)
 *   merged  -> saved as the new journal (and pending cleared), uploaded if it differs from remote.
 *
 * Timestamps:
 *  - [Entry.INITIAL_T] (1): value imported on first link, has never been through [merge] yet. A live
 *    INITIAL_T entry beats a tombstone (first link never deletes what exists on the new device).
 *  - [LINKED_T] (2): what INITIAL_T becomes once it has been merged. Still loses every conflict
 *    against a real timestamp, but no longer beats tombstones, so a later delete on another
 *    device does propagate to the device that was linked.
 *  - anything else: epoch ms of the change. Real changes always get t > [LINKED_T].
 */
object Merge {
    /** t of a first-link import after it has been merged once. See the object doc. */
    const val LINKED_T: Long = Entry.INITIAL_T + 1

    const val DEFAULT_MAX_TOMBSTONE_AGE_MS: Long = 180L * 24 * 3600 * 1000

    /**
     * Turns the current local values into timestamped entries, using the journal (last synced
     * state) to tell unchanged values from local changes and to detect local deletions.
     *
     * @param journal last merged state saved on this device; null = first link of this profile.
     * @param scan    current local values.
     * @param pending change times recorded by the listener (set or removal), keyed by relKey.
     */
    fun reconcile(
        journal: Map<String, Entry>?,
        scan: Map<String, Typed>,
        pending: Map<String, Long>,
        now: Long,
    ): Map<String, Entry> {
        val out = LinkedHashMap<String, Entry>()
        for ((key, value) in scan) {
            val prev = journal?.get(key)
            if (prev != null && !prev.d && prev.ty == value.ty && prev.v == value.v) {
                out[key] = prev
                continue
            }
            val t = if (journal == null) {
                pending[key]?.let { realTime(it) } ?: Entry.INITIAL_T
            } else {
                changeTime(prev, pending[key] ?: now)
            }
            out[key] = Entry(t = t, ty = value.ty, v = value.v, d = false)
        }
        if (journal != null) {
            for ((key, prev) in journal) {
                if (key in scan) continue
                out[key] = if (prev.d || Keys.isHeader(key)) {
                    prev
                } else {
                    Entry(t = changeTime(prev, pending[key] ?: now), ty = prev.ty, v = null, d = true)
                }
            }
            // Added and removed again between two syncs: nobody else should keep an older value
            // of that key alive, so record the removal as a tombstone at its listener time.
            for ((key, p) in pending) {
                if (key in scan || key in journal || Keys.isHeader(key)) continue
                out[key] = Entry(t = realTime(p), ty = "s", v = null, d = true)
            }
        }
        return out
    }

    /**
     * Per key, the entry with the higher t wins; on equal t `remote` wins. Special rules:
     *  - live with t == INITIAL_T vs tombstone: the live entry wins with t = tombstone.t + 1;
     *  - header keys: live always beats a tombstone, a lone header tombstone is dropped;
     *  - a surviving live INITIAL_T entry is promoted to [LINKED_T].
     * Output keys are sorted, so the uploaded JSON is stable.
     */
    fun merge(local: Map<String, Entry>, remote: Map<String, Entry>): Map<String, Entry> {
        val keys = sortedSetOf<String>().apply { addAll(local.keys); addAll(remote.keys) }
        val out = LinkedHashMap<String, Entry>()
        for (key in keys) {
            val winner = resolve(key, local[key], remote[key]) ?: continue
            out[key] = if (!winner.d && winner.t == Entry.INITIAL_T) winner.copy(t = LINKED_T) else winner
        }
        return out
    }

    private fun resolve(key: String, l: Entry?, r: Entry?): Entry? {
        if (Keys.isHeader(key)) {
            val ll = l?.takeIf { !it.d }
            val rl = r?.takeIf { !it.d }
            return if (ll != null && rl != null) newer(ll, rl) else ll ?: rl
        }
        if (l == null) return r
        if (r == null) return l
        if (!l.d && l.t == Entry.INITIAL_T && r.d) return l.copy(t = r.t + 1)
        if (!r.d && r.t == Entry.INITIAL_T && l.d) return r.copy(t = l.t + 1)
        return newer(l, r)
    }

    private fun newer(l: Entry, r: Entry): Entry = if (l.t > r.t) l else r

    /**
     * One local write to perform.
     * @param expected the local value seen by the scan (null = key absent); the caller should only
     *                 apply the change if the key still holds exactly this value.
     * @param entry    the merged entry: a tombstone means "remove the key", otherwise write ty/v.
     */
    data class Change(val relKey: String, val expected: Typed?, val entry: Entry)

    /**
     * Local writes needed to make the scanned state equal to `merged`. Header keys are never
     * removed locally. Live entries without a value (malformed) are skipped.
     */
    fun changesToApply(scan: Map<String, Typed>, merged: Map<String, Entry>): List<Change> {
        val out = ArrayList<Change>()
        for ((key, entry) in merged) {
            val current = scan[key]
            if (entry.d) {
                if (current != null && !Keys.isHeader(key)) out.add(Change(key, current, entry))
            } else {
                val v = entry.v ?: continue
                if (current == null || current.ty != entry.ty || current.v != v) {
                    out.add(Change(key, current, entry))
                }
            }
        }
        return out
    }

    /** Drops tombstones with now - t > maxTombstoneAgeMs. Live entries are always kept. */
    fun prune(
        entries: Map<String, Entry>,
        now: Long,
        maxTombstoneAgeMs: Long = DEFAULT_MAX_TOMBSTONE_AGE_MS,
    ): Map<String, Entry> {
        val out = LinkedHashMap<String, Entry>()
        for ((key, e) in entries) {
            if (!e.d || now - e.t <= maxTombstoneAgeMs) out[key] = e
        }
        return out
    }

    /** A real change time: never confusable with INITIAL_T / LINKED_T. */
    private fun realTime(t: Long): Long = maxOf(t, LINKED_T + 1)

    /** Change time of a key that replaces `prev`: never goes backwards relative to prev. */
    private fun changeTime(prev: Entry?, t: Long): Long {
        val rt = realTime(t)
        return if (prev == null) rt else maxOf(rt, prev.t + 1)
    }
}
