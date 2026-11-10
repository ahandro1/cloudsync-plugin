package com.cloudsync

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.cloudsync.core.AniMatch
import com.cloudsync.core.Entry
import com.cloudsync.core.IndexFile
import com.cloudsync.core.Keys
import com.cloudsync.core.Merge
import com.cloudsync.core.ProfileFile
import com.cloudsync.core.ProfileRef
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Orchestrates device sync (CloudStream data <-> JSON files in the private GitHub repo) and the
 * AniList sync. One [Mutex] serialises everything that reads or writes the repo or applies data.
 */
class SyncEngine(context: Context) {
    private val ctx: Context = context.applicationContext ?: context

    val settings: Settings = Settings(ctx)
    val store: LocalStore = LocalStore(ctx, settings)
    val github: GitHubClient = GitHubClient(settings)

    private val aniSync = AniListSync(settings, store)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()

    private val lifecycleLock = Any()
    private var started = false
    private var loopJob: Job? = null
    private var debounceJob: Job? = null

    /** Last downloaded copy of each profile file. */
    private class Cached(val profile: ProfileFile, val sha: String?, val etag: String?)

    private val cache = ConcurrentHashMap<String, Cached>()

    /** profileId -> SystemClock.elapsedRealtime() of its last AniList attempt. Absent = not since start. */
    private val lastAniRun = ConcurrentHashMap<String, Long>()

    private class IndexState(val index: IndexFile, val sha: String?, val etag: String?, val fetchedAt: Long)

    private var indexState: IndexState? = null // guarded by [mutex]

    /** Called (on any thread) after each sync attempt finishes. */
    @Volatile
    var onStatusChanged: (() -> Unit)? = null

    private class Totals {
        var profiles = 0
        var received = 0
        var sent = 0
        var aniProfiles = 0
        var pushed = 0
        var pulled = 0
        var unmatched = 0
        val deviceErrors = ArrayList<String>()
        val aniErrors = ArrayList<String>()
    }

    // --- Lifecycle ----------------------------------------------------------------------------

    fun start() {
        synchronized(lifecycleLock) {
            if (started) return
            started = true
            store.start { _ -> scheduleDebounced() }
            loopJob = scope.launch {
                while (isActive) {
                    autoSync()
                    delay(LOOP_INTERVAL_MS)
                }
            }
        }
    }

    fun stop() {
        synchronized(lifecycleLock) {
            started = false
            loopJob?.cancel()
            loopJob = null
            debounceJob?.cancel()
            debounceJob = null
            store.stop()
            scope.coroutineContext.cancelChildren()
        }
    }

    private fun scheduleDebounced() {
        synchronized(lifecycleLock) {
            if (!started) return
            debounceJob?.cancel()
            // Only the wait is cancelled by a newer change; the sync itself runs in its own job.
            debounceJob = scope.launch {
                delay(DEBOUNCE_MS)
                scope.launch { autoSync() }
            }
        }
    }

    private suspend fun autoSync() {
        if (!github.isSignedIn || settings.devicePaused) return
        try {
            runAll(forceDevices = false, aniForced = false, only = null)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Log.e(TAG, "automatic sync failed", t)
        }
    }

    // --- Public: forced syncs -----------------------------------------------------------------

    suspend fun syncDevicesNow(): String {
        if (!github.isSignedIn) throw IllegalStateException(AUTH_MESSAGE)
        val t = runAll(forceDevices = true, aniForced = false, only = null)
        if (t.deviceErrors.isNotEmpty()) throw IllegalStateException(joinErrors(t.deviceErrors))
        val sb = StringBuilder()
        sb.append(plural(t.profiles, "profile")).append(" synced, ")
            .append(plural(t.received, "change")).append(" received, ")
            .append(t.sent).append(" sent")
        if (t.aniProfiles > 0) {
            sb.append("; AniList: ").append(t.pushed).append(" pushed, ").append(t.pulled).append(" pulled")
        }
        if (t.aniErrors.isNotEmpty()) sb.append("; ").append(joinErrors(t.aniErrors))
        return sb.toString()
    }

    suspend fun syncAniListNow(): String {
        val withToken = settings.links().values.filter { settings.anilistToken(it) != null }.toSet()
        if (withToken.isEmpty()) {
            return "No linked profile is signed in to AniList on this device."
        }
        if (!github.isSignedIn) throw IllegalStateException(AUTH_MESSAGE)
        val t = runAll(forceDevices = true, aniForced = true, only = { it in withToken })
        if (t.deviceErrors.isNotEmpty()) throw IllegalStateException(joinErrors(t.deviceErrors))
        val sb = StringBuilder()
        sb.append("AniList synced for ").append(plural(t.aniProfiles, "profile")).append(": ")
            .append(t.pushed).append(" pushed, ").append(t.pulled).append(" pulled")
        if (t.unmatched > 0) sb.append(", ").append(t.unmatched).append(" unmatched")
        if (t.aniErrors.isNotEmpty()) sb.append("; ").append(joinErrors(t.aniErrors))
        return sb.toString()
    }

    // --- Public: index and profiles -----------------------------------------------------------

    suspend fun loadIndex(): IndexFile = mutex.withLock { fetchIndex(useEtag = true).index }

    suspend fun setAnilistPaused(paused: Boolean) {
        mutex.withLock {
            updateIndex { it.copy(anilistPaused = paused) }
        }
        notifyStatus()
    }

    suspend fun createProfile(name: String): ProfileRef = mutex.withLock { createProfileLocked(name) }

    suspend fun link(slot: Int, profileId: String?) {
        // Off the main thread: deletes journal files and commits SharedPreferences.
        withContext(Dispatchers.IO) { linkLocked(slot, profileId) }
        notifyStatus()
        if (profileId != null) scope.launch { autoSync() }
    }

    private suspend fun linkLocked(slot: Int, profileId: String?) {
        mutex.withLock {
            if (profileId != null) {
                val other = settings.links().entries.firstOrNull { it.value == profileId && it.key != slot }
                if (other != null) {
                    val label = store.profiles().firstOrNull { it.slot == other.key }?.name
                        ?: "slot ${other.key}"
                    throw IllegalStateException(
                        "That shared profile is already linked to the local profile \"$label\" on this device."
                    )
                }
            }
            val prev = settings.linkOf(slot)
            if (prev != profileId) {
                if (prev != null) {
                    store.deleteJournal(prev)
                    cache.remove(prev)
                    lastAniRun.remove(prev)
                }
                // Stale change records from an earlier link must not become timestamps of the new one.
                store.clearPending(slot, store.pendingTimes(slot))
                settings.setLink(slot, profileId)
            }
        }
    }

    // --- Public: AniList accounts -------------------------------------------------------------

    suspend fun signInAniList(profileId: String, token: String): String {
        val viewer = AniListClient.viewer(token.trim())
        if (viewer.id == 0) throw IllegalStateException("AniList did not return a user for this token.")
        settings.setAnilistToken(profileId, token.trim())
        settings.setAnilistUserName(profileId, viewer.name)
        settings.setAnilistUserId(profileId, viewer.id)
        lastAniRun.remove(profileId) // due again on the next sync
        notifyStatus()
        scope.launch { autoSync() }
        return viewer.name
    }

    fun signOutAniList(profileId: String) {
        settings.setAnilistToken(profileId, null)
        settings.setAnilistUserName(profileId, null)
        settings.setAnilistUserId(profileId, 0)
        lastAniRun.remove(profileId)
        notifyStatus()
    }

    fun unmatchedTitles(profileId: String): List<String> {
        val profile = cache[profileId]?.profile ?: return emptyList()
        val bookmarked = HashSet<String>()
        for ((key, e) in profile.entries) {
            if (e.d) continue
            val id = Keys.idIn(key, Keys.WATCH_STATE) ?: continue
            if (e.v?.trim()?.toIntOrNull() in 0..4) bookmarked.add(id)
        }
        return profile.anilist.entries
            .filter { (id, m) -> m.mediaId == null && id in bookmarked && !m.title.isNullOrBlank() }
            .mapNotNull { it.value.title }
            .distinct()
            .sorted()
    }

    // --- Run everything -----------------------------------------------------------------------

    /** Never throws except for cancellation; problems are collected in the returned [Totals]. */
    private suspend fun runAll(forceDevices: Boolean, aniForced: Boolean, only: ((String) -> Boolean)?): Totals {
        val t = Totals()
        // Callers include the settings dialog on the main thread; scan/apply/journal are disk work.
        withContext(Dispatchers.IO) { mutex.withLock {
            try {
                var index = refreshIndex(force = forceDevices || aniForced)
                index = firstRunLocked(index)
                for ((slot, id) in settings.links().toSortedMap()) {
                    if (only != null && !only(id)) continue
                    val label = index.profiles.firstOrNull { it.id == id }?.name ?: id
                    try {
                        syncPair(slot, id, index, aniForced, t)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: GitHubAuthException) {
                        t.deviceErrors.add(AUTH_MESSAGE)
                        break
                    } catch (e: Throwable) {
                        Log.e(TAG, "sync of profile $label failed", e)
                        t.deviceErrors.add("$label: ${describe(e)}")
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: GitHubAuthException) {
                t.deviceErrors.add(AUTH_MESSAGE)
            } catch (e: Throwable) {
                Log.e(TAG, "sync failed", e)
                t.deviceErrors.add(describe(e))
            }
        } }
        finish(t)
        return t
    }

    private fun finish(t: Totals) {
        try {
            if (t.deviceErrors.isEmpty()) settings.lastDeviceSync = System.currentTimeMillis()
            val errors = t.deviceErrors + t.aniErrors
            settings.lastError = if (errors.isEmpty()) null else joinErrors(errors)
        } catch (e: Throwable) {
            Log.e(TAG, "could not record sync status", e)
        }
        notifyStatus()
    }

    private fun notifyStatus() {
        try {
            onStatusChanged?.invoke()
        } catch (e: Throwable) {
            Log.e(TAG, "onStatusChanged failed", e)
        }
    }

    // --- First run ----------------------------------------------------------------------------

    private suspend fun firstRunLocked(index: IndexFile): IndexFile {
        if (settings.defaultLinkDone || !github.isSignedIn || settings.repo == null) return index
        val fresh = fetchIndex(useEtag = true).index
        val ref = fresh.profiles.firstOrNull { it.name.equals(DEFAULT_PROFILE_NAME, ignoreCase = true) }
            ?: createProfileLocked(DEFAULT_PROFILE_NAME)
        if (settings.linkOf(0) == null && settings.links().values.none { it == ref.id }) {
            settings.setLink(0, ref.id)
        }
        settings.defaultLinkDone = true
        return indexState?.index ?: fresh
    }

    // --- Index --------------------------------------------------------------------------------

    private suspend fun refreshIndex(force: Boolean): IndexFile {
        val st = indexState
        if (!force && st != null && SystemClock.elapsedRealtime() - st.fetchedAt < INDEX_MAX_AGE_MS) {
            return st.index
        }
        return fetchIndex(useEtag = true).index
    }

    /** Downloads index.json (creating it if missing) and caches it. Caller holds [mutex]. */
    private suspend fun fetchIndex(useEtag: Boolean): IndexState {
        repeat(MAX_ATTEMPTS) {
            val prev = indexState
            val res = github.getFile(INDEX_PATH, if (useEtag) prev?.etag else null)
            when (res) {
                is GitHubClient.GetResult.NotModified -> {
                    if (prev != null) {
                        val st = IndexState(prev.index, prev.sha, prev.etag, SystemClock.elapsedRealtime())
                        indexState = st
                        return st
                    }
                    indexState = null
                }
                is GitHubClient.GetResult.Found -> {
                    val index = try {
                        Json.decodeIndex(res.file.text)
                    } catch (e: Exception) {
                        throw IllegalStateException("index.json in the sync repository is not valid (${e.message})")
                    }
                    val st = IndexState(index, res.file.sha, res.file.etag, SystemClock.elapsedRealtime())
                    indexState = st
                    return st
                }
                is GitHubClient.GetResult.NotFound -> {
                    val fresh = IndexFile()
                    when (val put = github.putFile(INDEX_PATH, Json.encodeIndex(fresh), null, "create index from ${Build.MODEL}")) {
                        is GitHubClient.PutResult.Ok -> {
                            val st = IndexState(fresh, put.sha, null, SystemClock.elapsedRealtime())
                            indexState = st
                            return st
                        }
                        is GitHubClient.PutResult.Conflict -> indexState = null // someone created it meanwhile
                    }
                }
            }
        }
        throw IllegalStateException("Could not read index.json from the sync repository. Try again.")
    }

    /** Read-modify-write of index.json with conflict retry. Caller holds [mutex]. */
    private suspend fun updateIndex(modify: (IndexFile) -> IndexFile): IndexFile {
        repeat(MAX_ATTEMPTS) {
            val st = fetchIndex(useEtag = false)
            val next = modify(st.index)
            if (next == st.index) return st.index
            when (val put = github.putFile(INDEX_PATH, Json.encodeIndex(next), st.sha, "update index from ${Build.MODEL}")) {
                is GitHubClient.PutResult.Ok -> {
                    indexState = IndexState(next, put.sha, null, SystemClock.elapsedRealtime())
                    return next
                }
                is GitHubClient.PutResult.Conflict -> indexState = null
            }
        }
        throw IllegalStateException("Could not update index.json: the repository kept changing. Try again.")
    }

    private suspend fun createProfileLocked(name: String): ProfileRef {
        val clean = name.trim()
        if (clean.isEmpty()) throw IllegalArgumentException("Profile name cannot be empty.")
        val id = UUID.randomUUID().toString().replace("-", "").take(12)
        val ref = ProfileRef(id = id, name = clean)
        val file = ProfileFile(id = id, name = clean)
        // File first, so the index never points at a missing file.
        val put = github.putFile("profiles/$id.json", Json.encodeProfile(file), null, "create profile $clean from ${Build.MODEL}")
        if (put is GitHubClient.PutResult.Conflict) {
            throw IllegalStateException("Could not create the profile file. Try again.")
        }
        updateIndex { it.copy(profiles = it.profiles + ref) }
        cache[id] = Cached(file, (put as GitHubClient.PutResult.Ok).sha, null)
        return ref
    }

    // --- One linked (slot, profile) pair ------------------------------------------------------

    private suspend fun syncPair(slot: Int, id: String, index: IndexFile, aniForced: Boolean, t: Totals) {
        val indexName = index.profiles.firstOrNull { it.id == id }?.name
        val label = indexName ?: id
        val path = "profiles/$id.json"
        val aniEnabled = settings.anilistToken(id) != null && (aniForced || !index.anilistPaused)

        fun aniDue(watchStateInvolved: Boolean): Boolean {
            if (!aniEnabled) return false
            if (aniForced || watchStateInvolved) return true
            val last = lastAniRun[id] ?: return true
            return SystemClock.elapsedRealtime() - last >= ANILIST_INTERVAL_MS
        }

        for (attempt in 1..MAX_ATTEMPTS) {
            val now = System.currentTimeMillis()
            // Pending BEFORE scan: a change landing in between is then newer than anything we clear.
            val pending = store.pendingTimes(slot)
            val scan = store.scan(slot)
            val intent = store.loadIntent(id)
            val journal = recoverJournal(store.loadJournal(id), intent, scan)
            val local = Merge.reconcile(journal, scan, pending, now)

            // Download (or reuse) the remote profile.
            val cached = cache[id]
            var notModified = false
            val remote: Cached = when (val res = github.getFile(path, cached?.etag)) {
                is GitHubClient.GetResult.NotModified -> {
                    notModified = true
                    cached ?: throw IllegalStateException("Unexpected empty response for $path")
                }
                is GitHubClient.GetResult.Found -> {
                    val profile = try {
                        Json.decodeProfile(res.file.text)
                    } catch (e: Exception) {
                        throw IllegalStateException("$path is not valid JSON (${e.message})")
                    }
                    Cached(profile, res.file.sha, res.file.etag).also { cache[id] = it }
                }
                is GitHubClient.GetResult.NotFound -> {
                    cache.remove(id)
                    Cached(ProfileFile(id = id, name = indexName ?: ""), null, null)
                }
            }
            val remoteEntries = remote.profile.entries

            val watchStateChanged = local.any { (k, e) ->
                Keys.idIn(k, Keys.WATCH_STATE) != null && journal?.get(k) != e
            }

            // Fast path. remoteEntries == journal also guards against a journal that was saved
            // but whose upload failed.
            if (notModified && intent == null && journal != null && local == journal && remoteEntries == journal &&
                !aniDue(false)
            ) {
                store.clearPending(slot, pending)
                t.profiles++
                return
            }

            val merged = Merge.prune(Merge.merge(local, remoteEntries), now)
            val changes = Merge.changesToApply(scan, merged)
            // Written before the local writes, removed once the journal is saved: if the app is
            // killed in between, the next sync can tell our own writes from user changes.
            if (changes.isNotEmpty()) store.saveIntent(id, merged)
            val applied = store.apply(slot, changes)
            val skipped = HashSet<String>()
            for (c in changes) if (c.relKey !in applied) skipped.add(c.relKey)

            val toSave: Map<String, Entry> = if (skipped.isEmpty()) {
                merged
            } else {
                LinkedHashMap(merged).also { m ->
                    for (k in skipped) {
                        val prev = journal?.get(k)
                        if (prev != null) m[k] = prev else m.remove(k)
                    }
                }
            }
            store.saveJournal(id, toSave)
            store.deleteIntent(id)
            store.clearPending(slot, if (skipped.isEmpty()) pending else pending.filterKeys { it !in skipped })
            t.received += applied.size
            if (applied.isNotEmpty()) store.notifyUi()

            // AniList.
            var aniMap: Map<String, AniMatch> = remote.profile.anilist
            val watchStateInvolved = watchStateChanged || applied.any { Keys.idIn(it, Keys.WATCH_STATE) != null }
            var aniRan = false
            if (aniDue(watchStateInvolved)) {
                lastAniRun[id] = SystemClock.elapsedRealtime()
                aniRan = true
                try {
                    val r = aniSync.run(slot, id, merged, aniMap, retryUnmatched = aniForced)
                    aniMap = r.anilist
                    t.aniProfiles++
                    t.pushed += r.pushed
                    t.pulled += r.pulled
                    t.unmatched += r.unmatched
                    if (r.pulled > 0) store.notifyUi()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: AniListAuthException) {
                    t.aniErrors.add("AniList sign-in expired for profile $label. Sign in again in the plugin settings.")
                } catch (e: Throwable) {
                    Log.e(TAG, "AniList sync of $label failed", e)
                    t.aniErrors.add("AniList sync failed for $label: ${describe(e)}")
                }
            }

            // Upload.
            val changed = merged != remoteEntries || aniMap != remote.profile.anilist
            if (!changed) {
                t.profiles++
                return
            }
            val profile = remote.profile.copy(
                id = id,
                name = remote.profile.name.ifBlank { indexName ?: "" },
                entries = merged,
                anilist = aniMap,
            )
            when (val put = github.putFile(path, Json.encodeProfile(profile), remote.sha, "sync $label from ${Build.MODEL}")) {
                is GitHubClient.PutResult.Ok -> {
                    cache[id] = Cached(profile, put.sha, null)
                    t.sent += merged.count { (k, e) -> remoteEntries[k] != e }
                    t.profiles++
                    return
                }
                is GitHubClient.PutResult.Conflict -> {
                    cache.remove(id)
                    if (aniRan) lastAniRun.remove(id) // its result was lost; run it again on the retry
                    Log.i(TAG, "conflict uploading $path (attempt $attempt)")
                }
            }
        }
        throw IllegalStateException("Could not upload \"$label\": the repository kept changing. It will retry automatically.")
    }

    // --- Helpers ------------------------------------------------------------------------------

    /**
     * Journal to reconcile against. Normally just [journal]; when an [intent] file survived (the
     * app died after applying remote values but before saving the journal), every key whose local
     * value is exactly what the intent wanted to write takes the intent's entry, so those values
     * keep their merged timestamps instead of being stamped as fresh local changes. Keys that do
     * not match (never written, or changed by the user since) keep their old journal entry.
     * Without a journal (first link) the intent is ignored: INITIAL_T imports already lose to the
     * remote values that were written, and first-link merges never delete anything locally.
     */
    private fun recoverJournal(
        journal: Map<String, Entry>?,
        intent: Map<String, Entry>?,
        scan: Map<String, com.cloudsync.core.Typed>,
    ): Map<String, Entry>? {
        if (intent == null || journal == null) return journal
        val out = LinkedHashMap<String, Entry>(journal)
        for ((k, e) in intent) {
            val s = scan[k]
            val same = if (e.d) s == null else (s != null && s.ty == e.ty && s.v == e.v)
            if (same) out[k] = e
        }
        return out
    }

    private fun describe(e: Throwable): String = when (e) {
        is GitHubAuthException -> AUTH_MESSAGE
        is GitHubException -> e.message ?: "GitHub error"
        is AniListException -> e.message ?: "AniList error"
        is IOException -> "Network problem (${e.javaClass.simpleName}). Will retry."
        else -> e.message ?: e.javaClass.simpleName
    }

    private fun joinErrors(errors: List<String>): String {
        val distinct = errors.distinct()
        val first = distinct.first()
        return if (distinct.size == 1) first else "$first (+${distinct.size - 1} more)"
    }

    private fun plural(n: Int, word: String): String = "$n $word${if (n == 1) "" else "s"}"

    private companion object {
        const val TAG = "CloudSync"
        const val INDEX_PATH = "index.json"
        const val DEFAULT_PROFILE_NAME = "Default"
        const val DEBOUNCE_MS = 20_000L
        const val LOOP_INTERVAL_MS = 60_000L
        const val INDEX_MAX_AGE_MS = 50_000L
        const val ANILIST_INTERVAL_MS = 15 * 60 * 1000L
        const val MAX_ATTEMPTS = 3
        const val AUTH_MESSAGE = "GitHub sign-in needed. Open the plugin settings and sign in again."
    }
}
