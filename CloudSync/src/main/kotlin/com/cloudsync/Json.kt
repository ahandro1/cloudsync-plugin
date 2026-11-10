package com.cloudsync

import com.cloudsync.core.AniMatch
import com.cloudsync.core.Entry
import com.cloudsync.core.IndexFile
import com.cloudsync.core.ProfileFile
import com.cloudsync.core.ProfileRef
import org.json.JSONObject

/**
 * JSON for the two files in the sync repo. Encoding is deterministic (sorted keys, one entry per
 * line) so git diffs stay small; decoding is tolerant (missing fields -> defaults, unknown ignored).
 * A text that is not a JSON object at all makes the decode functions throw org.json.JSONException.
 */
object Json {

    fun encodeIndex(index: IndexFile): String {
        val sb = StringBuilder()
        sb.append("{\n")
        sb.append("  \"version\": ").append(index.version).append(",\n")
        sb.append("  \"anilistPaused\": ").append(index.anilistPaused).append(",\n")
        if (index.profiles.isEmpty()) {
            sb.append("  \"profiles\": []\n")
        } else {
            sb.append("  \"profiles\": [\n")
            index.profiles.forEachIndexed { i, p ->
                sb.append("    {\"id\": ").append(JSONObject.quote(p.id))
                    .append(", \"name\": ").append(JSONObject.quote(p.name)).append("}")
                sb.append(if (i < index.profiles.size - 1) ",\n" else "\n")
            }
            sb.append("  ]\n")
        }
        sb.append("}\n")
        return sb.toString()
    }

    fun decodeIndex(text: String): IndexFile {
        if (text.isBlank()) return IndexFile()
        val o = JSONObject(text)
        val profiles = ArrayList<ProfileRef>()
        val arr = o.optJSONArray("profiles")
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val p = arr.optJSONObject(i) ?: continue
                val id = optStr(p, "id") ?: continue
                profiles.add(ProfileRef(id = id, name = optStr(p, "name") ?: ""))
            }
        }
        return IndexFile(
            version = o.optInt("version", 1),
            profiles = profiles,
            anilistPaused = o.optBoolean("anilistPaused", false),
        )
    }

    fun encodeProfile(profile: ProfileFile): String {
        val sb = StringBuilder()
        sb.append("{\n")
        sb.append("  \"version\": ").append(profile.version).append(",\n")
        sb.append("  \"id\": ").append(JSONObject.quote(profile.id)).append(",\n")
        sb.append("  \"name\": ").append(JSONObject.quote(profile.name)).append(",\n")

        val keys = profile.entries.keys.sorted()
        if (keys.isEmpty()) {
            sb.append("  \"entries\": {},\n")
        } else {
            sb.append("  \"entries\": {\n")
            keys.forEachIndexed { i, k ->
                val e = profile.entries.getValue(k)
                sb.append("    ").append(JSONObject.quote(k)).append(": {\"t\":").append(e.t)
                    .append(",\"ty\":").append(JSONObject.quote(e.ty))
                if (e.v != null) sb.append(",\"v\":").append(JSONObject.quote(e.v))
                if (e.d) sb.append(",\"d\":true")
                sb.append("}")
                sb.append(if (i < keys.size - 1) ",\n" else "\n")
            }
            sb.append("  },\n")
        }

        val aniKeys = profile.anilist.keys.sorted()
        if (aniKeys.isEmpty()) {
            sb.append("  \"anilist\": {}\n")
        } else {
            sb.append("  \"anilist\": {\n")
            aniKeys.forEachIndexed { i, k ->
                val m = profile.anilist.getValue(k)
                sb.append("    ").append(JSONObject.quote(k)).append(": {")
                var first = true
                fun sep() {
                    if (!first) sb.append(",")
                    first = false
                }
                if (m.mediaId != null) {
                    sep(); sb.append("\"mediaId\":").append(m.mediaId)
                }
                if (m.base != null) {
                    sep(); sb.append("\"base\":").append(JSONObject.quote(m.base))
                }
                if (m.title != null) {
                    sep(); sb.append("\"title\":").append(JSONObject.quote(m.title))
                }
                sb.append("}")
                sb.append(if (i < aniKeys.size - 1) ",\n" else "\n")
            }
            sb.append("  }\n")
        }
        sb.append("}\n")
        return sb.toString()
    }

    fun decodeProfile(text: String): ProfileFile {
        if (text.isBlank()) return ProfileFile()
        val o = JSONObject(text)

        val entries = LinkedHashMap<String, Entry>()
        o.optJSONObject("entries")?.let { eo ->
            val it = eo.keys()
            while (it.hasNext()) {
                val k = it.next()
                val e = eo.optJSONObject(k) ?: continue
                entries[k] = Entry(
                    t = e.optLong("t", 0),
                    ty = optStr(e, "ty") ?: "s",
                    v = optStr(e, "v"),
                    d = e.optBoolean("d", false),
                )
            }
        }

        val anilist = LinkedHashMap<String, AniMatch>()
        o.optJSONObject("anilist")?.let { ao ->
            val it = ao.keys()
            while (it.hasNext()) {
                val k = it.next()
                val m = ao.optJSONObject(k) ?: continue
                anilist[k] = AniMatch(
                    mediaId = if (m.has("mediaId") && !m.isNull("mediaId")) {
                        m.optInt("mediaId", 0).takeIf { id -> id > 0 }
                    } else {
                        null
                    },
                    base = optStr(m, "base"),
                    title = optStr(m, "title"),
                )
            }
        }

        return ProfileFile(
            version = o.optInt("version", 1),
            id = optStr(o, "id") ?: "",
            name = optStr(o, "name") ?: "",
            entries = entries,
            anilist = anilist,
        )
    }

    /** Null for missing or JSON null (org.json's optString would return the text "null"). */
    private fun optStr(o: JSONObject, key: String): String? {
        if (!o.has(key) || o.isNull(key)) return null
        return o.optString(key)
    }
}
