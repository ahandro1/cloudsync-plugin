package com.cloudsync.core

import com.fasterxml.jackson.annotation.JsonProperty

// Everything in com.cloudsync.core is pure Kotlin: no Android, no CloudStream, no JSON parsing.
// It is compiled a second time by the standalone build in /core-test so it can be unit tested
// with only a JDK. Keep it that way.

/**
 * One synced preference value, or a tombstone for a deleted one.
 *
 * @param t  last-modified time, epoch ms. [INITIAL_T] means "imported when the device was first
 *           linked", which loses every conflict against a real timestamp.
 * @param ty value type as stored in SharedPreferences: "s" String, "i" Int, "b" Boolean,
 *           "f" Float, "l" Long, "ss" Set<String> (v is then a JSON array of strings).
 * @param v  the value rendered as a string; null when [d] is true.
 * @param d  true = deleted (tombstone).
 */
data class Entry(
    @JsonProperty("t") val t: Long = 0,
    @JsonProperty("ty") val ty: String = "s",
    @JsonProperty("v") val v: String? = null,
    @JsonProperty("d") val d: Boolean = false,
) {
    companion object {
        const val INITIAL_T = 1L
    }
}

/** A raw local value read from SharedPreferences, before it has a timestamp. */
data class Typed(val ty: String, val v: String)

/**
 * Link between one CloudStream show id and one AniList media id, stored in the shared profile.
 *
 * @param mediaId AniList media id; null = no confident match was found (shown as "unmatched").
 * @param base    AniList status string ("CURRENT", "COMPLETED", "PAUSED", "DROPPED", "PLANNING")
 *                both sides agreed on at the last successful reconcile; null = never reconciled.
 * @param title   show name, only for display in the unmatched list.
 */
data class AniMatch(
    @JsonProperty("mediaId") val mediaId: Int? = null,
    @JsonProperty("base") val base: String? = null,
    @JsonProperty("title") val title: String? = null,
)

/** Contents of profiles/<id>.json in the private sync repo. */
data class ProfileFile(
    @JsonProperty("version") val version: Int = 1,
    @JsonProperty("id") val id: String = "",
    @JsonProperty("name") val name: String = "",
    /** key = relative key, see [Keys]. */
    @JsonProperty("entries") val entries: Map<String, Entry> = emptyMap(),
    /** key = CloudStream show id (the number at the end of a result_watch_state key). */
    @JsonProperty("anilist") val anilist: Map<String, AniMatch> = emptyMap(),
)

data class ProfileRef(
    @JsonProperty("id") val id: String = "",
    @JsonProperty("name") val name: String = "",
)

/** Contents of index.json in the private sync repo. */
data class IndexFile(
    @JsonProperty("version") val version: Int = 1,
    @JsonProperty("profiles") val profiles: List<ProfileRef> = emptyList(),
    /** Shared "Pause AniList sync" switch; applies to every profile on every device. */
    @JsonProperty("anilistPaused") val anilistPaused: Boolean = false,
)

/**
 * Translation between CloudStream's local preference keys and the slot-independent keys
 * stored in the repo.
 *
 * Local:    "<slot>/<folder>/<id>"          e.g. "2/video_pos_dur/123456"
 * Relative: "p/<folder>/<id>"               e.g. "p/video_pos_dur/123456"
 *
 * Local:    "download_header_cache/<id>"    (not per-profile in CloudStream)
 * Relative: "h/<id>"
 *
 * "h/" keys are add/update only: they are never tombstoned and never deleted locally, because
 * the same local key can also belong to a real download.
 */
object Keys {
    const val WATCH_STATE = "result_watch_state"
    const val WATCH_STATE_DATA = "result_watch_state_data"
    const val RESUME = "result_resume_watching_2"
    const val HEADER_CACHE = "download_header_cache"

    val FOLDERS: List<String> = listOf(
        WATCH_STATE,
        WATCH_STATE_DATA,
        "result_favorites_state_data",
        "result_subscribed_state_data",
        RESUME,
        "video_pos_dur",
        "video_watch_state",
        "result_episode",
        "result_season",
        "result_dub",
    )

    private val localRegex = Regex("^(\\d+)/([a-z0-9_]+)/(.+)$")

    /** "2/video_pos_dur/55" with slot 2 -> "p/video_pos_dur/55". Null if out of scope or another slot. */
    fun toRel(localKey: String, slot: Int): String? {
        val m = localRegex.matchEntire(localKey) ?: return null
        if (m.groupValues[1].toIntOrNull() != slot) return null
        if (m.groupValues[2] !in FOLDERS) return null
        return "p/${m.groupValues[2]}/${m.groupValues[3]}"
    }

    /** "download_header_cache/55" -> "h/55". Null for anything else. */
    fun headerToRel(localKey: String): String? {
        val prefix = "$HEADER_CACHE/"
        if (!localKey.startsWith(prefix)) return null
        val id = localKey.removePrefix(prefix)
        return if (id.isEmpty()) null else "h/$id"
    }

    /** Inverse of [toRel] and [headerToRel]. Null for a malformed relative key. */
    fun toLocal(relKey: String, slot: Int): String? = when {
        relKey.startsWith("p/") && relKey.length > 2 -> "$slot/${relKey.removePrefix("p/")}"
        relKey.startsWith("h/") && relKey.length > 2 -> "$HEADER_CACHE/${relKey.removePrefix("h/")}"
        else -> null
    }

    fun isHeader(relKey: String): Boolean = relKey.startsWith("h/")

    /** Slot number of an in-scope per-profile local key, else null. */
    fun slotOf(localKey: String): Int? {
        val m = localRegex.matchEntire(localKey) ?: return null
        if (m.groupValues[2] !in FOLDERS) return null
        return m.groupValues[1].toIntOrNull()
    }

    /** "p/result_watch_state/55" -> "55" when the key is in [folder], else null. */
    fun idIn(relKey: String, folder: String): String? {
        val prefix = "p/$folder/"
        return if (relKey.startsWith(prefix)) relKey.removePrefix(prefix).ifEmpty { null } else null
    }
}
