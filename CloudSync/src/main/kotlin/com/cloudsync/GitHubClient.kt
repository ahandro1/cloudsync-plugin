package com.cloudsync

import android.util.Base64
import com.lagradost.cloudstream3.app
import com.lagradost.nicehttp.NiceResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject
import java.net.URLEncoder

class GitHubException(message: String, val code: Int = 0) : Exception(message)

/** 401, or not signed in: the UI should ask the user to sign in again. */
class GitHubAuthException(message: String) : Exception(message)

/** A repository the GitHub App installation can see. */
data class RepoInfo(val fullName: String, val isPrivate: Boolean)

/** Outcome of looking for the sync repository. Only private repositories are ever selected. */
sealed class RepoDiscovery {
    /** [repo] is stored in the settings and ready to use. */
    data class Selected(val repo: String) : RepoDiscovery()

    /** The app is installed on no repository at all. */
    object None : RepoDiscovery()

    /** The app can only see public repositories. */
    data class OnlyPublic(val repos: List<String>) : RepoDiscovery()

    /** The app can see several private repositories; the user has to choose. */
    data class Multiple(val repos: List<String>) : RepoDiscovery()
}

class GitHubClient(private val settings: Settings) {

    data class DeviceCode(
        val deviceCode: String,
        val userCode: String,
        val verificationUri: String,
        val expiresInSec: Int,
        val intervalSec: Int
    )

    data class RemoteFile(val text: String, val sha: String, val etag: String?)

    sealed class GetResult {
        object NotModified : GetResult()
        object NotFound : GetResult()
        data class Found(val file: RemoteFile) : GetResult()
    }

    sealed class PutResult {
        data class Ok(val sha: String) : PutResult()
        object Conflict : PutResult()
    }

    val isSignedIn: Boolean get() = settings.githubToken != null

    private val refreshMutex = Mutex()

    /** Repo confirmed private in this process (see [verifyPrivate]). */
    @Volatile
    private var verifiedRepo: String? = null

    private companion object {
        const val API = "https://api.github.com"
        const val DEVICE_CODE_URL = "https://github.com/login/device/code"
        const val TOKEN_URL = "https://github.com/login/oauth/access_token"
        const val USER_AGENT = "CloudSync-Plugin"
        const val REFRESH_MARGIN_MS = 5 * 60 * 1000L
        const val AUTH_MESSAGE = "GitHub sign-in needed. Open the plugin settings and sign in again."
    }

    // --- Device flow -------------------------------------------------------------------------

    // Every public suspend function switches to Dispatchers.IO: NiceHttp reads the response body
    // on the calling thread, and the settings dialog calls these from the main thread.

    suspend fun requestDeviceCode(): DeviceCode = withContext(Dispatchers.IO) { requestDeviceCodeIo() }

    suspend fun awaitDeviceToken(code: DeviceCode): Boolean = withContext(Dispatchers.IO) { awaitDeviceTokenIo(code) }

    suspend fun discoverRepo(): RepoDiscovery = withContext(Dispatchers.IO) { discoverRepoIo() }

    /** Makes [fullName] the sync repository. It must be a private repository the app can see. */
    suspend fun selectRepo(fullName: String) = withContext(Dispatchers.IO) { selectRepoIo(fullName) }

    suspend fun getFile(path: String, etag: String? = null): GetResult =
        withContext(Dispatchers.IO) { getFileIo(path, etag) }

    /** sha = null creates the file. */
    suspend fun putFile(path: String, text: String, sha: String?, message: String): PutResult =
        withContext(Dispatchers.IO) { putFileIo(path, text, sha, message) }

    private suspend fun requestDeviceCodeIo(): DeviceCode {
        val resp = app.post(
            DEVICE_CODE_URL,
            headers = mapOf("Accept" to "application/json", "User-Agent" to USER_AGENT),
            data = form("client_id" to Config.GITHUB_CLIENT_ID),
            cacheTime = 0
        )
        val json = parseObject(resp)
        val err = str(json, "error")
        if (err != null) {
            throw GitHubException(
                "GitHub sign-in failed: ${str(json, "error_description") ?: err}", resp.code
            )
        }
        if (!resp.isSuccessful) throw GitHubException("GitHub sign-in failed (HTTP ${resp.code})", resp.code)
        val deviceCode = str(json, "device_code")
        val userCode = str(json, "user_code")
        val uri = str(json, "verification_uri")
        if (deviceCode == null || userCode == null || uri == null) {
            throw GitHubException("Unexpected response from GitHub device flow", resp.code)
        }
        return DeviceCode(
            deviceCode, userCode, uri,
            json.optInt("expires_in", 900),
            json.optInt("interval", 5)
        )
    }

    private suspend fun awaitDeviceTokenIo(code: DeviceCode): Boolean {
        var interval = maxOf(code.intervalSec, 1)
        val deadline = System.currentTimeMillis() + code.expiresInSec * 1000L
        while (true) {
            delay(interval * 1000L)
            if (System.currentTimeMillis() > deadline) return false
            val resp = app.post(
                TOKEN_URL,
                headers = mapOf("Accept" to "application/json", "User-Agent" to USER_AGENT),
                data = form(
                    "client_id" to Config.GITHUB_CLIENT_ID,
                    "device_code" to code.deviceCode,
                    "grant_type" to "urn:ietf:params:oauth:grant-type:device_code"
                ),
                cacheTime = 0
            )
            val json = parseObject(resp)
            val token = str(json, "access_token")
            if (token != null) {
                val previousUser = settings.githubUser
                storeTokens(json, token)
                settings.githubSignInExpired = false
                if (settings.lastError == AUTH_MESSAGE) settings.lastError = null
                val user = fetchLogin()
                if (user != null) {
                    // A different account cannot use the previous account's repository.
                    if (previousUser != null && !previousUser.equals(user, ignoreCase = true)) {
                        settings.repo = null
                    }
                    settings.githubUser = user
                }
                discoverRepoIo() // selects the repo when there is exactly one private candidate
                return true
            }
            when (val err = str(json, "error")) {
                "authorization_pending" -> Unit
                "slow_down" -> {
                    interval += 5
                }
                "expired_token", "access_denied" -> return false
                else -> throw GitHubException(
                    "GitHub sign-in failed: ${str(json, "error_description") ?: err ?: "HTTP ${resp.code}"}",
                    resp.code
                )
            }
        }
    }

    private fun storeTokens(json: JSONObject, token: String) {
        settings.githubToken = token
        settings.githubRefreshToken = str(json, "refresh_token")
        val expiresIn = if (json.has("expires_in") && !json.isNull("expires_in")) json.optLong("expires_in", 0) else 0L
        settings.githubTokenExpiry = if (expiresIn > 0) System.currentTimeMillis() + expiresIn * 1000L else 0L
    }

    private suspend fun fetchLogin(): String? {
        val resp = api("GET", "$API/user")
        if (!resp.isSuccessful) throw failure(resp)
        return str(parseObject(resp), "login")
    }

    /** Every repository of every installation of the app (first 100 per installation). */
    private suspend fun listReposIo(): List<RepoInfo> {
        val instResp = api("GET", "$API/user/installations?per_page=100")
        if (!instResp.isSuccessful) throw failure(instResp)
        val installations = parseObject(instResp).optJSONArray("installations") ?: return emptyList()
        val out = ArrayList<RepoInfo>()
        for (i in 0 until installations.length()) {
            val id = installations.optJSONObject(i)?.optLong("id", 0L) ?: 0L
            if (id == 0L) continue
            val repoResp = api("GET", "$API/user/installations/$id/repositories?per_page=100")
            if (!repoResp.isSuccessful) throw failure(repoResp)
            val repos = parseObject(repoResp).optJSONArray("repositories") ?: continue
            for (j in 0 until repos.length()) {
                val r = repos.optJSONObject(j) ?: continue
                val name = str(r, "full_name") ?: continue
                if (out.any { it.fullName.equals(name, ignoreCase = true) }) continue
                out.add(RepoInfo(name, r.optBoolean("private", false)))
            }
        }
        return out
    }

    private suspend fun discoverRepoIo(): RepoDiscovery {
        val all = listReposIo()
        if (all.isEmpty()) return RepoDiscovery.None
        val privates = all.filter { it.isPrivate }.map { it.fullName }.sortedBy { it.lowercase() }
        if (privates.isEmpty()) {
            return RepoDiscovery.OnlyPublic(all.map { it.fullName }.sortedBy { it.lowercase() })
        }
        val current = settings.repo
        if (current != null) {
            val kept = privates.firstOrNull { it.equals(current, ignoreCase = true) }
            if (kept != null) return RepoDiscovery.Selected(current)
        }
        if (privates.size == 1) {
            val only = privates[0]
            settings.repo = only
            return RepoDiscovery.Selected(only)
        }
        return RepoDiscovery.Multiple(privates)
    }

    private suspend fun selectRepoIo(fullName: String) {
        val match = listReposIo().firstOrNull { it.isPrivate && it.fullName.equals(fullName, ignoreCase = true) }
            ?: throw GitHubException(
                "$fullName is not a private repository the CloudSync GitHub App can access. Check the app's installation on GitHub."
            )
        settings.repo = match.fullName
    }

    fun signOut() {
        settings.githubToken = null
        settings.githubRefreshToken = null
        settings.githubTokenExpiry = 0L
        settings.githubUser = null
        settings.repo = null
        settings.githubSignInExpired = false
        verifiedRepo = null
    }

    /**
     * GitHub definitively rejected the sign-in: forget the tokens but keep user, repo and links so
     * that signing in again with the same account continues seamlessly. [rejected] is the token that
     * was refused; if a newer sign-in replaced it meanwhile, nothing is touched.
     */
    private fun markSignInExpired(rejected: String?) {
        if (settings.githubToken != rejected) return
        settings.githubToken = null
        settings.githubRefreshToken = null
        settings.githubTokenExpiry = 0L
        settings.githubSignInExpired = true
        settings.lastError = AUTH_MESSAGE
    }

    // --- Files -------------------------------------------------------------------------------

    private suspend fun getFileIo(path: String, etag: String?): GetResult {
        val repo = requireRepo()
        val resp = api("GET", "$API/repos/$repo/contents/${encodePath(path)}", etag = etag)
        when {
            resp.code == 304 -> {
                close(resp)
                return GetResult.NotModified
            }
            resp.code == 404 -> {
                close(resp)
                return GetResult.NotFound
            }
            !resp.isSuccessful -> throw failure(resp)
        }
        val responseEtag = resp.headers["ETag"]
        val json = parseObject(resp)
        val sha = str(json, "sha") ?: throw GitHubException("GitHub response had no sha for $path", resp.code)
        var content = str(json, "content") ?: ""
        if (content.isBlank() && json.optLong("size", 0L) > 0L) {
            // Files over 1 MB are not inlined by the Contents API; use the blob endpoint.
            val blobResp = api("GET", "$API/repos/$repo/git/blobs/$sha")
            if (!blobResp.isSuccessful) throw failure(blobResp)
            content = str(parseObject(blobResp), "content") ?: ""
        }
        val text = String(Base64.decode(content, Base64.DEFAULT), Charsets.UTF_8)
        return GetResult.Found(RemoteFile(text, sha, responseEtag))
    }

    private suspend fun putFileIo(path: String, text: String, sha: String?, message: String): PutResult {
        val repo = requireRepo()
        val body = JSONObject()
        body.put("message", message)
        body.put("content", Base64.encodeToString(text.toByteArray(Charsets.UTF_8), Base64.NO_WRAP))
        if (sha != null) body.put("sha", sha)
        val resp = api("PUT", "$API/repos/$repo/contents/${encodePath(path)}", body = body)
        if (resp.code == 409) {
            close(resp)
            return PutResult.Conflict
        }
        if (resp.code == 422) {
            // 422 is a sha problem ("sha" wasn't supplied / does not match) only when the message
            // says so; anything else is a real validation error that retrying cannot fix.
            val msg = try {
                str(JSONObject(resp.text), "message")
            } catch (e: JSONException) {
                null
            }
            if (msg != null && msg.contains("sha", ignoreCase = true)) return PutResult.Conflict
            throw GitHubException(
                if (msg != null) "GitHub rejected the upload of $path: $msg" else "GitHub rejected the upload of $path (HTTP 422)",
                422
            )
        }
        if (!resp.isSuccessful) throw failure(resp)
        val newSha = parseObject(resp).optJSONObject("content")?.let { str(it, "sha") }
            ?: throw GitHubException("GitHub response had no sha for $path", resp.code)
        return PutResult.Ok(newSha)
    }

    /**
     * The selected sync repository. Never discovers one here: the repo may only change through the
     * settings dialog, so a sync in flight can never continue in a different repository.
     */
    private suspend fun requireRepo(): String {
        val repo = settings.repo ?: throw GitHubException(
            "No sync repository is selected. Open the CloudSync settings to choose one."
        )
        if (verifiedRepo != repo) verifyPrivate(repo)
        return repo
    }

    /**
     * Refuses a repository that is public (e.g. one chosen by version 1, or made public since) or
     * no longer accessible. Checked once per process and repo.
     */
    private suspend fun verifyPrivate(repo: String) {
        val resp = api("GET", "$API/repos/$repo")
        if (resp.code == 404) {
            close(resp)
            throw GitHubException(
                "The sync repository $repo cannot be accessed. Check the CloudSync GitHub App's installation on GitHub.",
                404
            )
        }
        if (!resp.isSuccessful) throw failure(resp)
        val json = parseObject(resp)
        if (json.has("private") && !json.optBoolean("private", true)) {
            if (settings.repo == repo) settings.repo = null
            throw GitHubException(
                "The sync repository $repo is public, so CloudSync stopped using it. Open the CloudSync settings to choose a private repository."
            )
        }
        verifiedRepo = repo
    }

    // --- HTTP plumbing -----------------------------------------------------------------------

    /** Authenticated api.github.com call with proactive refresh and one refresh+retry on 401. */
    private suspend fun api(
        method: String,
        url: String,
        etag: String? = null,
        body: JSONObject? = null
    ): NiceResponse {
        var refreshedOnce = false
        while (true) {
            ensureFresh()
            val token = settings.githubToken
                ?: throw GitHubAuthException("Not signed in to GitHub")
            val headers = HashMap<String, String>()
            headers["Accept"] = "application/vnd.github+json"
            headers["X-GitHub-Api-Version"] = "2022-11-28"
            headers["Authorization"] = "Bearer $token"
            headers["User-Agent"] = USER_AGENT
            if (etag != null) headers["If-None-Match"] = etag
            // CloudStream's OkHttp client has a disk Cache and NiceHttp's network interceptor marks
            // every response as cacheable forever. NiceHttp overwrites any Cache-Control request
            // header with its own (max-age=cacheTime), so use Pragma: no-cache, which OkHttp's
            // CacheControl.parse also honours: the cache is then never consulted, an unconditional
            // GET always goes to GitHub, and our own If-None-Match is sent as-is (-> real 304).
            headers["Pragma"] = "no-cache"
            val resp = if (method == "PUT") {
                app.put(url, headers = headers, json = body, cacheTime = 0)
            } else {
                app.get(url, headers = headers, cacheTime = 0)
            }
            if (resp.code == 401) {
                close(resp)
                if (!refreshedOnce && settings.githubRefreshToken != null) {
                    refreshedOnce = true
                    refreshToken(token, force = true)
                    continue
                }
                markSignInExpired(token)
                throw GitHubAuthException("GitHub rejected the saved sign-in (401). Please sign in again.")
            }
            return resp
        }
    }

    private suspend fun ensureFresh() {
        if (settings.githubToken == null) throw GitHubAuthException("Not signed in to GitHub")
        val expiry = settings.githubTokenExpiry
        if (expiry != 0L && expiry - System.currentTimeMillis() < REFRESH_MARGIN_MS &&
            settings.githubRefreshToken != null
        ) {
            refreshToken(settings.githubToken, force = false)
        }
    }

    /** Refreshes the access token. [usedToken] is the token that was just rejected (or about to expire). */
    private suspend fun refreshToken(usedToken: String?, force: Boolean) {
        refreshMutex.withLock {
            val refresh = settings.githubRefreshToken ?: return
            // Another caller already refreshed while we waited for the lock.
            if (settings.githubToken != usedToken) return
            if (!force) {
                val expiry = settings.githubTokenExpiry
                if (expiry == 0L || expiry - System.currentTimeMillis() >= REFRESH_MARGIN_MS) return
            }
            val resp = app.post(
                TOKEN_URL,
                headers = mapOf("Accept" to "application/json", "User-Agent" to USER_AGENT),
                data = form(
                    "client_id" to Config.GITHUB_CLIENT_ID,
                    "grant_type" to "refresh_token",
                    "refresh_token" to refresh
                ),
                cacheTime = 0
            )
            val json = try {
                JSONObject(resp.text)
            } catch (e: JSONException) {
                throw GitHubException("GitHub token refresh failed (HTTP ${resp.code})", resp.code)
            }
            val token = str(json, "access_token")
            if (token == null) {
                if (resp.code >= 500) throw GitHubException("GitHub token refresh failed (HTTP ${resp.code})", resp.code)
                markSignInExpired(usedToken)
                throw GitHubAuthException(
                    "GitHub sign-in expired (${str(json, "error_description") ?: str(json, "error") ?: "refresh failed"}). Please sign in again."
                )
            }
            storeTokens(json, token)
        }
    }

    // --- Helpers -----------------------------------------------------------------------------

    private fun failure(resp: NiceResponse): GitHubException {
        val code = resp.code
        val limited = (code == 403 || code == 429) &&
                (resp.headers["x-ratelimit-remaining"] == "0" || resp.headers["retry-after"] != null)
        if (limited) return GitHubException("GitHub rate limit reached (HTTP $code). Try again later.", code)
        val msg = try {
            str(JSONObject(resp.text), "message")
        } catch (e: JSONException) {
            null
        }
        return GitHubException(
            if (msg != null) "GitHub error $code: $msg" else "GitHub error $code",
            code
        )
    }

    /** Releases the connection of a response whose body is not read. */
    private fun close(resp: NiceResponse) {
        try {
            resp.okhttpResponse.close()
        } catch (_: Throwable) {
        }
    }

    private fun parseObject(resp: NiceResponse): JSONObject = try {
        JSONObject(resp.text)
    } catch (e: JSONException) {
        throw GitHubException("Unexpected response from GitHub (HTTP ${resp.code})", resp.code)
    }

    /** Null for missing, JSON null or empty strings (org.json would return "null"). */
    private fun str(o: JSONObject, key: String): String? {
        if (!o.has(key) || o.isNull(key)) return null
        return o.optString(key, "").ifEmpty { null }
    }

    /** NiceHttp's FormBody uses addEncoded, so values must already be URL-encoded. */
    private fun form(vararg pairs: Pair<String, String>): Map<String, String> =
        pairs.associate { (k, v) -> URLEncoder.encode(k, "UTF-8") to URLEncoder.encode(v, "UTF-8") }

    private fun encodePath(path: String): String =
        path.split("/").joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
}
