package com.cloudsync.core

/**
 * Three-way merge of CloudStream watch-status bookmarks with the user's AniList list.
 *
 * Per AniList media id (all local shows matched to it form one group):
 *   L = group's local status in AniList terms (see the leader rule in [plan]),
 *   A = AniList status (null = not on the list), B = base recorded at the last agreement.
 * REPEATING on AniList is treated as CURRENT everywhere (it agrees with local Watching and is
 * recorded as base CURRENT). Nothing is ever deleted on AniList or locally.
 */
object AniListMerge {
    const val CURRENT = "CURRENT"
    const val COMPLETED = "COMPLETED"
    const val PAUSED = "PAUSED"
    const val DROPPED = "DROPPED"
    const val PLANNING = "PLANNING"
    const val REPEATING = "REPEATING"

    /** Index = CloudStream watch type (0 Watching .. 4 Plan to Watch). */
    private val BY_WATCH_TYPE = listOf(CURRENT, COMPLETED, PAUSED, DROPPED, PLANNING)

    /** CloudStream watch type -> AniList status; null for 5 (none) or anything unknown. */
    fun toAniList(watchType: Int): String? = BY_WATCH_TYPE.getOrNull(watchType)

    /** AniList status -> CloudStream watch type; REPEATING -> 0 (Watching); null if unknown. */
    fun toWatchType(status: String): Int? =
        if (status == REPEATING) 0 else BY_WATCH_TYPE.indexOf(status).takeIf { it >= 0 }

    /** REPEATING -> CURRENT, everything else unchanged. This is the form stored as a base. */
    fun normalize(status: String?): String? = if (status == REPEATING) CURRENT else status

    /** @param t Entry.t of the show's result_watch_state key (epoch ms, or INITIAL_T / LINKED_T). */
    data class LocalShow(val showId: String, val watchType: Int, val t: Long)

    data class RemoteEntry(val status: String, val updatedAtMs: Long)

    sealed class Action {
        /** Write [status] to AniList for [mediaId]; on success record it as base of every [showIds]. */
        data class Push(val mediaId: Int, val status: String, val showIds: List<String>) : Action()

        /**
         * Write [watchType] to CloudStream for [showId]; on success record [status] (REPEATING
         * recorded as CURRENT) as its base. [status] is the AniList status it corresponds to.
         */
        data class SetLocal(val showId: String, val watchType: Int, val mediaId: Int, val status: String) : Action()
    }

    /**
     * @param actions in a stable order (by mediaId, then Push before SetLocal, then showId).
     * @param settled showId -> base for every matched show that already agrees with AniList
     *                (may equal the stored base already).
     */
    data class Plan(val actions: List<Action>, val settled: Map<String, String>)

    fun plan(local: List<LocalShow>, matches: Map<String, AniMatch>, remote: Map<Int, RemoteEntry>): Plan {
        val groups = sortedMapOf<Int, MutableMap<String, LocalShow>>()
        for (show in local) {
            val mediaId = matches[show.showId]?.mediaId ?: continue
            if (toAniList(show.watchType) == null) continue
            val members = groups.getOrPut(mediaId) { sortedMapOf() }
            val dup = members[show.showId]
            if (dup == null || show.t > dup.t) members[show.showId] = show
        }

        val actions = ArrayList<Action>()
        val settled = sortedMapOf<String, String>()
        for ((mediaId, byId) in groups) {
            val members = byId.values.toList() // sorted by showId
            // The group's local status is the most recent local *change*: the member with the
            // highest t among those whose status differs from their own base; if none differs,
            // the member with the highest t. Ties go to the smallest showId (maxBy keeps the first).
            val changed = members.filter { toAniList(it.watchType) != normalize(matches[it.showId]?.base) }
            val leader = changed.ifEmpty { members }.maxBy { it.t }
            val l = toAniList(leader.watchType)!!
            val b = normalize(matches[leader.showId]?.base)
            val rem = remote[mediaId]
            val a = rem?.status
            if (a != null && toWatchType(a) == null) continue // unknown AniList status: leave alone
            val na = normalize(a)

            val outcome = when {
                rem != null && na == l -> Outcome.AGREE
                rem == null && b != null && l == b -> Outcome.NOTHING // removed on AniList on purpose
                rem == null -> Outcome.LOCAL // never on AniList, or changed locally since removal
                na == b -> Outcome.LOCAL
                l == b -> Outcome.REMOTE
                else -> if (localTime(leader.t) > rem.updatedAtMs) Outcome.LOCAL else Outcome.REMOTE
            }

            when (outcome) {
                Outcome.NOTHING -> {}
                Outcome.LOCAL -> {
                    // Push records the base only for members already at L; the others get theirs
                    // from their own SetLocal. If the Push fails but those SetLocals succeed, the
                    // leader still differs from its base and the next plan pushes again.
                    val (same, other) = members.partition { it.watchType == leader.watchType }
                    actions.add(Action.Push(mediaId, l, same.map { it.showId }))
                    for (m in other) actions.add(Action.SetLocal(m.showId, leader.watchType, mediaId, l))
                }
                Outcome.AGREE, Outcome.REMOTE -> {
                    val status = a!!
                    val wt = toWatchType(status)!!
                    for (m in members) {
                        if (m.watchType == wt) settled[m.showId] = normalize(status)!!
                        else actions.add(Action.SetLocal(m.showId, wt, mediaId, status))
                    }
                }
            }
        }
        return Plan(actions, settled)
    }

    /** showId -> base to record once [action] has succeeded. */
    fun basesAfter(action: Action): Map<String, String> = when (action) {
        is Action.Push -> action.showIds.associateWith { normalize(action.status)!! }
        is Action.SetLocal -> mapOf(action.showId to normalize(action.status)!!)
    }

    private enum class Outcome { AGREE, NOTHING, LOCAL, REMOTE }

    /** First-link imports (t <= LINKED_T) carry no real time and lose to any AniList entry. */
    private fun localTime(t: Long): Long = if (t <= Merge.LINKED_T) Long.MIN_VALUE else t
}
