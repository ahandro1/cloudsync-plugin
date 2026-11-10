package com.cloudsync

import android.util.Log
import com.cloudsync.core.AniListMerge
import com.cloudsync.core.AniMatch
import com.cloudsync.core.Entry
import com.cloudsync.core.Keys
import com.cloudsync.core.TitleMatch
import kotlinx.coroutines.CancellationException
import org.json.JSONObject

/**
 * Syncs the watch-status categories of one shared profile with its AniList account.
 * Stateless apart from [Settings.lastAnilistSync]; the caller owns the matches map.
 */
class AniListSync(private val settings: Settings, private val store: LocalStore) {

    data class Result(
        val anilist: Map<String, AniMatch>,
        val pushed: Int,
        val pulled: Int,
        val unmatched: Int,
    )

    /** What the bookmark data of a show says about its AniList id. */
    private class BookmarkInfo(val name: String, val year: Int?, val type: String?, val aniListId: Int?)

    /**
     * @param entries the merged entries of the profile.
     * @param anilist the stored matches (showId -> AniMatch); never shrinks.
     * @throws AniListAuthException when the token is rejected (the caller keeps its old map).
     */
    suspend fun run(
        slot: Int,
        profileId: String,
        entries: Map<String, Entry>,
        anilist: Map<String, AniMatch>,
        retryUnmatched: Boolean,
    ): Result {
        val token = settings.anilistToken(profileId)
        val matches = LinkedHashMap(anilist)

        // 1. Bookmarked shows (watch state 0..4).
        val shows = ArrayList<AniListMerge.LocalShow>()
        for ((key, e) in entries) {
            if (e.d) continue
            val showId = Keys.idIn(key, Keys.WATCH_STATE) ?: continue
            val wt = e.v?.trim()?.toIntOrNull() ?: continue
            if (wt !in 0..4) continue
            shows.add(AniListMerge.LocalShow(showId, wt, e.t))
        }
        shows.sortBy { it.showId }

        if (token == null) {
            return Result(matches, 0, 0, countUnmatched(shows, matches))
        }

        // 2. Matching.
        var searches = 0
        for (show in shows) {
            val existing = matches[show.showId]
            val needs = existing == null || (retryUnmatched && existing.mediaId == null)
            if (!needs) continue
            val info = readBookmark(entries, show.showId) ?: continue

            if (info.aniListId != null) {
                matches[show.showId] = AniMatch(mediaId = info.aniListId, base = existing?.base, title = info.name)
                continue
            }
            if (info.type !in ANIME_TYPES) continue // not anime and no id: no match at all
            if (searches >= MAX_SEARCHES_PER_RUN) continue
            searches++
            val results = try {
                AniListClient.search(info.name, token)
            } catch (e: CancellationException) {
                throw e
            } catch (e: AniListAuthException) {
                throw e
            } catch (e: Throwable) {
                Log.w(TAG, "AniList search failed for '${info.name}'", e)
                continue
            }
            val candidates = results.map { TitleMatch.Candidate(it.id, it.titles, it.year, it.format) }
            val picked = TitleMatch.pick(info.name, info.year, candidates)
            matches[show.showId] = AniMatch(mediaId = picked, base = existing?.base, title = info.name)
        }

        // 3. AniList's list.
        var userId = settings.anilistUserId(profileId)
        if (userId == 0) {
            userId = AniListClient.viewer(token).id
            if (userId != 0) settings.setAnilistUserId(profileId, userId)
        }
        val remote = AniListClient.list(token, userId)
            .mapValues { (_, v) -> AniListMerge.RemoteEntry(v.status, v.updatedAtMs) }

        // 4. Plan and execute.
        val plan = AniListMerge.plan(shows, matches, remote)
        for ((showId, base) in plan.settled) {
            val m = matches[showId] ?: continue
            if (m.base != base) matches[showId] = m.copy(base = base)
        }

        var pushed = 0
        var pulled = 0
        for (action in plan.actions) {
            try {
                when (action) {
                    is AniListMerge.Action.Push -> {
                        AniListClient.saveStatus(token, action.mediaId, action.status)
                        pushed++
                    }
                    is AniListMerge.Action.SetLocal -> {
                        store.setWatchState(slot, action.showId, action.watchType)
                        pulled++
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: AniListAuthException) {
                throw e
            } catch (e: Throwable) {
                Log.w(TAG, "AniList action failed: $action", e)
                continue
            }
            for ((showId, base) in AniListMerge.basesAfter(action)) {
                val m = matches[showId] ?: continue
                matches[showId] = m.copy(base = base)
            }
        }

        // 5. Done.
        settings.lastAnilistSync = System.currentTimeMillis()
        return Result(matches, pushed, pulled, countUnmatched(shows, matches))
    }

    private fun countUnmatched(shows: List<AniListMerge.LocalShow>, matches: Map<String, AniMatch>): Int =
        shows.count { s -> matches[s.showId]?.let { it.mediaId == null } == true }

    /** Parses CloudStream's BookmarkedData JSON; null if missing or unusable. */
    private fun readBookmark(entries: Map<String, Entry>, showId: String): BookmarkInfo? {
        val e = entries["p/${Keys.WATCH_STATE_DATA}/$showId"] ?: return null
        if (e.d) return null
        val raw = e.v ?: return null
        return try {
            val o = JSONObject(raw)
            val name = strOrNull(o, "name") ?: return null
            val year = if (o.has("year") && !o.isNull("year")) o.optInt("year", 0).takeIf { it > 0 } else null
            val type = strOrNull(o, "type")
            var aniId: Int? = null
            val sd = o.optJSONObject("syncData")
            if (sd != null && sd.has(SYNC_KEY) && !sd.isNull(SYNC_KEY)) {
                aniId = sd.optString(SYNC_KEY).trim().toIntOrNull()?.takeIf { it > 0 }
            }
            BookmarkInfo(name, year, type, aniId)
        } catch (t: Throwable) {
            Log.w(TAG, "bookmark data for show $showId unreadable", t)
            null
        }
    }

    private fun strOrNull(o: JSONObject, key: String): String? {
        if (!o.has(key) || o.isNull(key)) return null
        return o.optString(key).trim().ifEmpty { null }
    }

    private companion object {
        const val TAG = "CloudSync"
        const val MAX_SEARCHES_PER_RUN = 15

        /** AniListApi.idPrefix in CloudStream; MainAPI.aniListIdPrefix is set to it, and addAniListId stores under it. */
        const val SYNC_KEY = "anilist"

        val ANIME_TYPES = setOf("Anime", "AnimeMovie", "OVA")
    }
}
