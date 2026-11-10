package com.cloudsync

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.cloudsync.core.Entry
import com.cloudsync.core.Keys
import com.cloudsync.core.Merge
import com.cloudsync.core.Typed
import com.lagradost.cloudstream3.MainActivity
import com.lagradost.cloudstream3.syncproviders.AccountManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

data class CsProfile(val slot: Int, val name: String)

/**
 * The only class that touches CloudStream's local data (its "rebuild_preference" SharedPreferences),
 * the on-disk journals and the change listener.
 */
class LocalStore(context: Context, private val settings: Settings) {
    private val ctx: Context = context.applicationContext ?: context
    private val prefs: SharedPreferences =
        ctx.getSharedPreferences(Config.CS_PREFS, Context.MODE_PRIVATE)
    private val pendingPrefs: SharedPreferences =
        ctx.getSharedPreferences(PENDING_PREFS, Context.MODE_PRIVATE)
    private val mainHandler = Handler(Looper.getMainLooper())

    /** Local keys written by [apply] whose change callback must be ignored. */
    // Not ConcurrentHashMap.newKeySet(): that needs API 24 and CloudStream supports API 21.
    private val muted: MutableSet<String> =
        java.util.Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
    private val pendingLock = Any()
    private val applyLock = Any()

    @Volatile
    private var listener: SharedPreferences.OnSharedPreferenceChangeListener? = null

    // --- Profiles -----------------------------------------------------------------------------

    fun profiles(): List<CsProfile> {
        val result = LinkedHashMap<Int, String>()
        result[0] = "Default"
        try {
            val raw = prefs.getString("data_store_helper/account", null)
            if (!raw.isNullOrBlank()) {
                val arr = JSONArray(raw)
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    if (!o.has("keyIndex")) continue
                    val idx = o.optInt("keyIndex", -1)
                    if (idx < 0) continue
                    val name = o.optString("name", "").ifBlank { if (idx == 0) "Default" else "Profile $idx" }
                    result[idx] = name
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "profiles() failed", t)
            return listOf(CsProfile(0, result[0] ?: "Default"))
        }
        return result.entries.sortedBy { it.key }.map { CsProfile(it.key, it.value) }
    }

    // --- Scan ---------------------------------------------------------------------------------

    fun scan(slot: Int): Map<String, Typed> {
        val out = LinkedHashMap<String, Typed>()
        try {
            val all: Map<String, *> = prefs.all
            for ((k, v) in all) {
                val rel = Keys.toRel(k, slot) ?: continue
                val typed = toTyped(v) ?: continue
                out[rel] = typed
            }
            val resumePrefix = "p/${Keys.RESUME}/"
            val ids = out.keys.filter { it.startsWith(resumePrefix) }.map { it.removePrefix(resumePrefix) }
            for (id in ids) {
                if (id.isEmpty()) continue
                val hk = "${Keys.HEADER_CACHE}/$id"
                if (all.containsKey(hk)) {
                    val typed = toTyped(all[hk]) ?: continue
                    out["h/$id"] = typed
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "scan($slot) failed", t)
        }
        return out
    }

    // --- Apply --------------------------------------------------------------------------------

    fun apply(slot: Int, changes: List<Merge.Change>): Set<String> {
        val applied = LinkedHashSet<String>()
        if (changes.isEmpty()) return applied
        synchronized(applyLock) {
            try {
                val all: Map<String, *> = prefs.all
                val editor = prefs.edit()
                val touched = ArrayList<String>()
                for (c in changes) {
                    val rel = c.relKey
                    val local = Keys.toLocal(rel, slot) ?: continue
                    val current = toTyped(all[local])
                    if (current != c.expected) continue
                    if (c.entry.d) {
                        if (Keys.isHeader(rel)) continue
                        if (!all.containsKey(local)) {
                            // Already absent (expected was null or unsupported type); nothing to do.
                            if (current == null) applied.add(rel)
                            continue
                        }
                        editor.remove(local)
                    } else {
                        if (!write(editor, local, c.entry)) continue
                    }
                    touched.add(local)
                    applied.add(rel)
                }
                if (touched.isNotEmpty()) {
                    muted.addAll(touched)
                    val ok = editor.commit()
                    if (!ok) Log.e(TAG, "apply: commit() returned false")
                    // Fallback: callbacks for unchanged/absent keys never arrive.
                    val toUnmute = touched.toList()
                    mainHandler.postDelayed({ muted.removeAll(toUnmute.toSet()) }, MUTE_FALLBACK_MS)
                }
            } catch (t: Throwable) {
                Log.e(TAG, "apply($slot) failed", t)
            }
        }
        return applied
    }

    private fun write(editor: SharedPreferences.Editor, key: String, e: Entry): Boolean {
        val v = e.v ?: return false
        try {
            when (e.ty) {
                "s" -> editor.putString(key, v)
                "i" -> editor.putInt(key, v.toIntOrNull() ?: return false)
                "b" -> {
                    if (v != "true" && v != "false") return false
                    editor.putBoolean(key, v == "true")
                }
                "f" -> editor.putFloat(key, v.toFloatOrNull() ?: return false)
                "l" -> editor.putLong(key, v.toLongOrNull() ?: return false)
                "ss" -> {
                    val arr = JSONArray(v)
                    val set = HashSet<String>()
                    for (i in 0 until arr.length()) set.add(arr.getString(i))
                    editor.putStringSet(key, set)
                }
                else -> return false
            }
        } catch (t: Throwable) {
            Log.e(TAG, "write conversion failed for $key", t)
            return false
        }
        return true
    }

    fun setWatchState(slot: Int, showId: String, watchType: Int) {
        try {
            prefs.edit().putString("$slot/${Keys.WATCH_STATE}/$showId", watchType.toString()).apply()
        } catch (t: Throwable) {
            Log.e(TAG, "setWatchState failed", t)
        }
    }

    private fun toTyped(v: Any?): Typed? = when (v) {
        null -> null
        is String -> Typed("s", v)
        is Int -> Typed("i", v.toString())
        is Boolean -> Typed("b", v.toString())
        is Float -> Typed("f", v.toString())
        is Long -> Typed("l", v.toString())
        is Set<*> -> {
            val arr = JSONArray()
            v.filterIsInstance<String>().sorted().forEach { arr.put(it) }
            Typed("ss", arr.toString())
        }
        else -> null
    }

    // --- Journal ------------------------------------------------------------------------------

    private fun journalFile(profileId: String, prefix: String = "journal_"): File {
        val safe = profileId.replace(Regex("[^A-Za-z0-9_.-]"), "_")
        return File(File(ctx.filesDir, "cloudsync"), "$prefix$safe.json")
    }

    fun loadJournal(profileId: String): Map<String, Entry>? = readEntries(journalFile(profileId), profileId)

    fun saveJournal(profileId: String, entries: Map<String, Entry>) =
        writeEntries(journalFile(profileId), profileId, entries)

    fun deleteJournal(profileId: String) {
        try {
            journalFile(profileId).delete()
            journalFile(profileId, INTENT_PREFIX).delete()
        } catch (t: Throwable) {
            Log.e(TAG, "deleteJournal($profileId) failed", t)
        }
    }

    /**
     * The merged state a sync is about to [apply], saved before the local writes and deleted after
     * the journal has been saved. If the app dies in between, the next sync uses it to recognise
     * the values it wrote itself (see SyncEngine), instead of taking them for new local changes.
     */
    fun loadIntent(profileId: String): Map<String, Entry>? =
        readEntries(journalFile(profileId, INTENT_PREFIX), profileId)

    fun saveIntent(profileId: String, entries: Map<String, Entry>) =
        writeEntries(journalFile(profileId, INTENT_PREFIX), profileId, entries)

    fun deleteIntent(profileId: String) {
        try {
            journalFile(profileId, INTENT_PREFIX).delete()
        } catch (t: Throwable) {
            Log.e(TAG, "deleteIntent($profileId) failed", t)
        }
    }

    private fun readEntries(f: File, profileId: String): Map<String, Entry>? {
        if (!f.exists()) return null
        return try {
            val root = JSONObject(f.readText(Charsets.UTF_8))
            val out = LinkedHashMap<String, Entry>()
            val it = root.keys()
            while (it.hasNext()) {
                val k = it.next()
                val o = root.getJSONObject(k)
                out[k] = Entry(
                    t = o.optLong("t", 0),
                    ty = o.optString("ty", "s"),
                    v = if (o.has("v") && !o.isNull("v")) o.getString("v") else null,
                    d = o.optBoolean("d", false),
                )
            }
            out
        } catch (t: Throwable) {
            Log.e(TAG, "${f.name} for $profileId unreadable, ignoring it", t)
            null
        }
    }

    private fun writeEntries(f: File, profileId: String, entries: Map<String, Entry>) {
        try {
            val root = JSONObject()
            for ((k, e) in entries) {
                val o = JSONObject()
                o.put("t", e.t)
                o.put("ty", e.ty)
                if (e.v != null) o.put("v", e.v)
                o.put("d", e.d)
                root.put(k, o)
            }
            f.parentFile?.mkdirs()
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(root.toString(), Charsets.UTF_8)
            if (!tmp.renameTo(f)) {
                f.delete()
                if (!tmp.renameTo(f)) Log.e(TAG, "write ${f.name}: rename failed for $profileId")
            }
        } catch (t: Throwable) {
            Log.e(TAG, "write ${f.name} ($profileId) failed", t)
        }
    }

    // --- Change listener ----------------------------------------------------------------------

    fun start(onChange: (slot: Int) -> Unit) {
        stop()
        val l = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            try {
                if (key == null) return@OnSharedPreferenceChangeListener
                if (muted.remove(key)) return@OnSharedPreferenceChangeListener
                if (key.startsWith("${Keys.HEADER_CACHE}/")) return@OnSharedPreferenceChangeListener
                val slot = Keys.slotOf(key) ?: return@OnSharedPreferenceChangeListener
                if (!settings.links().containsKey(slot)) return@OnSharedPreferenceChangeListener
                synchronized(pendingLock) {
                    pendingPrefs.edit().putLong(key, System.currentTimeMillis()).apply()
                }
                onChange(slot)
            } catch (t: Throwable) {
                Log.e(TAG, "change listener failed", t)
            }
        }
        listener = l
        prefs.registerOnSharedPreferenceChangeListener(l)
    }

    fun stop() {
        val l = listener ?: return
        try {
            prefs.unregisterOnSharedPreferenceChangeListener(l)
        } catch (t: Throwable) {
            Log.e(TAG, "stop failed", t)
        }
        listener = null
    }

    fun pendingTimes(slot: Int): Map<String, Long> {
        val out = LinkedHashMap<String, Long>()
        try {
            synchronized(pendingLock) {
                for ((k, v) in pendingPrefs.all) {
                    if (v !is Long) continue
                    val rel = Keys.toRel(k, slot) ?: continue
                    out[rel] = v
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "pendingTimes failed", t)
        }
        return out
    }

    fun clearPending(slot: Int, consumed: Map<String, Long>) {
        if (consumed.isEmpty()) return
        try {
            synchronized(pendingLock) {
                val editor = pendingPrefs.edit()
                var any = false
                for ((rel, time) in consumed) {
                    val local = Keys.toLocal(rel, slot) ?: continue
                    if (pendingPrefs.contains(local) && pendingPrefs.getLong(local, -1L) == time) {
                        editor.remove(local)
                        any = true
                    }
                }
                if (any) editor.commit()
            }
        } catch (t: Throwable) {
            Log.e(TAG, "clearPending failed", t)
        }
    }

    // --- UI refresh ---------------------------------------------------------------------------

    fun notifyUi() {
        try {
            AccountManager.localListApi.requireLibraryRefresh = true
        } catch (t: Throwable) {
            Log.e(TAG, "requireLibraryRefresh failed", t)
        }
        mainHandler.post {
            try {
                MainActivity.bookmarksUpdatedEvent.invoke(true)
            } catch (t: Throwable) {
                Log.e(TAG, "bookmarksUpdatedEvent failed", t)
            }
            // Not reloadHomeEvent: that re-downloads the provider's whole home page. The
            // bookmarksUpdatedEvent observer (HomeViewModel.reloadStored) already reloads resume
            // watching and the bookmark rows from local data.
            try {
                MainActivity.reloadLibraryEvent.invoke(true)
            } catch (t: Throwable) {
                Log.e(TAG, "reloadLibraryEvent failed", t)
            }
        }
    }

    private companion object {
        const val TAG = "CloudSync"
        const val PENDING_PREFS = "cloudsync_pending"
        const val MUTE_FALLBACK_MS = 5000L
        const val INTENT_PREFIX = "intent_"
    }
}
