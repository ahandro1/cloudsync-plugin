package com.cloudsync

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.lagradost.cloudstream3.app
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject
import java.net.URLEncoder

/** Any AniList / network failure. The message is short and safe to show to the user. */
class AniListException(message: String) : Exception(message)

/** Invalid or expired token: the UI should tell the user to sign in again. */
class AniListAuthException(message: String) : Exception(message)

object AniListClient {
    private const val ENDPOINT = "https://graphql.anilist.co"
    private const val MIN_GAP_MS = 2200L
    private const val DEFAULT_RETRY_AFTER_S = 60L

    data class Viewer(val id: Int, val name: String)

    /** [updatedAtMs] is AniList's updatedAt (epoch seconds) converted to milliseconds. */
    data class ListEntry(val mediaId: Int, val status: String, val updatedAtMs: Long)

    /** [titles]: romaji, english, native, then synonyms; blanks removed. [year] = startDate.year ?: seasonYear. */
    data class SearchResult(val id: Int, val titles: List<String>, val year: Int?, val format: String?)

    private val gate = Mutex()
    private var lastRequestAt = 0L // SystemClock.elapsedRealtime(); guarded by [gate]

    fun authorizeUrl(): String =
        "https://anilist.co/api/v2/oauth/authorize?client_id=" +
            URLEncoder.encode(Config.ANILIST_CLIENT_ID, "UTF-8") + "&response_type=token"

    /** Extracts access_token from a redirect URL's fragment (also accepted in the query string). */
    fun extractToken(url: String): String? {
        val hash = url.indexOf('#')
        val fragment = if (hash >= 0) url.substring(hash + 1) else ""
        val beforeHash = if (hash >= 0) url.substring(0, hash) else url
        val q = beforeHash.indexOf('?')
        val query = if (q >= 0) beforeHash.substring(q + 1) else ""
        for (part in listOf(fragment, query)) {
            if (part.isEmpty()) continue
            for (pair in part.split('&')) {
                val eq = pair.indexOf('=')
                if (eq > 0 && pair.substring(0, eq) == "access_token") {
                    val v = pair.substring(eq + 1).trim()
                    if (v.isNotEmpty()) return v
                }
            }
        }
        return null
    }

    suspend fun viewer(token: String): Viewer {
        val data = call("query { Viewer { id name } }", JSONObject(), token)
        val v = data.optJSONObject("Viewer") ?: throw AniListException("AniList returned no user")
        return Viewer(v.optInt("id"), v.str("name") ?: "")
    }

    suspend fun list(token: String, userId: Int): Map<Int, ListEntry> {
        val vars = JSONObject().put("userId", userId)
        val data = call(
            "query (\$userId: Int) { MediaListCollection(userId: \$userId, type: ANIME) " +
                "{ lists { isCustomList entries { mediaId status updatedAt } } } }",
            vars, token
        )
        val result = LinkedHashMap<Int, ListEntry>()
        val lists = data.optJSONObject("MediaListCollection")?.optJSONArray("lists") ?: return result
        for (i in 0 until lists.length()) {
            val entries = lists.optJSONObject(i)?.optJSONArray("entries") ?: continue
            for (j in 0 until entries.length()) {
                val e = entries.optJSONObject(j) ?: continue
                val mediaId = e.optInt("mediaId", 0)
                val status = e.str("status") ?: continue
                if (mediaId == 0) continue
                val entry = ListEntry(mediaId, status, e.optLong("updatedAt", 0L) * 1000L)
                val prev = result[mediaId]
                if (prev == null || entry.updatedAtMs > prev.updatedAtMs) result[mediaId] = entry
            }
        }
        return result
    }

    suspend fun saveStatus(token: String, mediaId: Int, status: String) {
        val vars = JSONObject().put("mediaId", mediaId).put("status", status)
        call(
            "mutation (\$mediaId: Int, \$status: MediaListStatus) " +
                "{ SaveMediaListEntry(mediaId: \$mediaId, status: \$status) { id status updatedAt } }",
            vars, token
        )
    }

    suspend fun search(title: String, token: String? = null): List<SearchResult> {
        val vars = JSONObject().put("search", title)
        val data = call(
            "query (\$search: String) { Page(page: 1, perPage: 8) { media(search: \$search, type: ANIME) " +
                "{ id format seasonYear startDate { year } title { romaji english native } synonyms } } }",
            vars, token
        )
        val media = data.optJSONObject("Page")?.optJSONArray("media") ?: return emptyList()
        val out = ArrayList<SearchResult>()
        for (i in 0 until media.length()) {
            val m = media.optJSONObject(i) ?: continue
            val id = m.optInt("id", 0)
            if (id == 0) continue
            val titles = ArrayList<String>()
            m.optJSONObject("title")?.let { t ->
                t.str("romaji")?.let(titles::add)
                t.str("english")?.let(titles::add)
                t.str("native")?.let(titles::add)
            }
            m.optJSONArray("synonyms")?.let { s ->
                for (k in 0 until s.length()) {
                    if (!s.isNull(k)) s.optString(k).trim().takeIf { it.isNotEmpty() }?.let(titles::add)
                }
            }
            val startYear = m.optJSONObject("startDate")?.let { if (it.isNull("year")) null else it.optInt("year") }
            val year = startYear ?: if (m.isNull("seasonYear")) null else m.optInt("seasonYear")
            out.add(SearchResult(id, titles.distinct(), year, m.str("format")))
        }
        return out
    }

    // --- internals ----------------------------------------------------------------------------

    private fun JSONObject.str(key: String): String? =
        if (isNull(key)) null else optString(key).trim().takeIf { it.isNotEmpty() }

    /**
     * Sends one GraphQL request (rate limited, one retry on 429) and returns the "data" object.
     * Runs on Dispatchers.IO: NiceHttp reads the body on the calling thread and the settings dialog
     * calls [viewer] from the main thread.
     */
    private suspend fun call(query: String, variables: JSONObject, token: String?): JSONObject =
        withContext(Dispatchers.IO) { callIo(query, variables, token) }

    private suspend fun callIo(query: String, variables: JSONObject, token: String?): JSONObject {
        val body = JSONObject().put("query", query).put("variables", variables)
        val headers = HashMap<String, String>()
        headers["Content-Type"] = "application/json"
        headers["Accept"] = "application/json"
        if (token != null) headers["Authorization"] = "Bearer $token"

        var code = 0
        var text = ""
        gate.withLock {
            var retried = false
            var done = false
            while (!done) {
                val wait = lastRequestAt + MIN_GAP_MS - SystemClock.elapsedRealtime()
                if (wait > 0) delay(wait)
                val resp = try {
                    app.post(ENDPOINT, headers = headers, json = body)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    lastRequestAt = SystemClock.elapsedRealtime()
                    throw AniListException("Could not reach AniList (${e.javaClass.simpleName})")
                }
                lastRequestAt = SystemClock.elapsedRealtime()
                if (resp.code == 429 && !retried) {
                    retried = true
                    val secs = (resp.headers["Retry-After"]?.trim()?.toLongOrNull() ?: DEFAULT_RETRY_AFTER_S)
                        .coerceIn(1L, 300L)
                    runCatching { resp.text } // drain/close the body
                    delay(secs * 1000L)
                    continue
                }
                code = resp.code
                text = try {
                    resp.text
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    ""
                }
                lastRequestAt = SystemClock.elapsedRealtime()
                done = true
            }
        }
        return parseResponse(code, text)
    }

    private fun parseResponse(code: Int, text: String): JSONObject {
        val json = try {
            if (text.isBlank()) null else JSONObject(text)
        } catch (e: JSONException) {
            null
        }
        val errors = json?.optJSONArray("errors")
        if (errors != null && errors.length() > 0) {
            val first = errors.optJSONObject(0)
            val message = first?.str("message") ?: "AniList error"
            val status = first?.optInt("status", code) ?: code
            val tokenProblem = message.contains("token", ignoreCase = true)
            if (status == 401 || code == 401 || ((status == 400 || code == 400) && tokenProblem)) {
                throw AniListAuthException("AniList token is invalid or expired")
            }
            throw AniListException(message.take(200))
        }
        if (code == 401) throw AniListAuthException("AniList token is invalid or expired")
        if (code == 429) throw AniListException("AniList rate limit reached, try again in a minute")
        if (code !in 200..299) throw AniListException("AniList returned HTTP $code")
        val data = json?.optJSONObject("data") ?: throw AniListException("Unexpected response from AniList")
        return data
    }
}

object AniListLogin {
    /**
     * Shows the sign-in UI. Calls [onToken] exactly once with the access token on success; calls
     * nothing if the user cancels. Must be called on the main thread with an Activity context.
     */
    @SuppressLint("SetJavaScriptEnabled")
    fun show(context: Context, onToken: (String) -> Unit) {
        val url = AniListClient.authorizeUrl()
        // Fire and forget: make sure a previous AniList account in this WebView is not reused.
        clearCookies()

        var delivered = false
        var dialog: AlertDialog? = null
        var webView: WebView? = null

        fun deliver(token: String) {
            if (delivered) return
            delivered = true
            dialog?.dismiss()
            onToken(token)
        }

        fun tryUrl(u: String?): Boolean {
            if (u.isNullOrEmpty() || delivered) return false
            val t = AniListClient.extractToken(u) ?: return false
            deliver(t)
            return true
        }

        val pasteRow = buildPasteRow(context) { deliver(it) }

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        val wv: WebView? = try {
            WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                isFocusable = true
                isFocusableInTouchMode = true
                webViewClient = object : WebViewClient() {
                    @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
                    override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                        return tryUrl(url)
                    }

                    override fun shouldOverrideUrlLoading(
                        view: WebView?, request: WebResourceRequest?
                    ): Boolean {
                        return tryUrl(request?.url?.toString())
                    }

                    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                        tryUrl(url)
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        if (tryUrl(url)) return
                        view?.evaluateJavascript("window.location.href") { result ->
                            if (result != null) tryUrl(unescapeJsResult(result))
                        }
                    }

                    override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                        tryUrl(url)
                    }
                }
            }
        } catch (e: Throwable) {
            null // some TV devices ship without a WebView
        }
        webView = wv

        val builder = AlertDialog.Builder(context)
        if (wv != null) {
            builder.setTitle("Sign in to AniList")
            // AlertDialog's custom panel is wrap_content, so a 0dp/weight WebView could collapse
            // to nothing; give it an explicit share of the screen instead.
            val webHeight = (context.resources.displayMetrics.heightPixels * 0.6f).toInt()
            root.addView(wv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, webHeight))
            val hint = TextView(context).apply {
                text = "Hard to use with a remote? Open $url on another device and paste the token below."
                setPadding(dp(context, 16), dp(context, 4), dp(context, 16), 0)
            }
            root.addView(
                hint,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            )
            root.addView(
                pasteRow,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            )
        } else {
            builder.setTitle("Sign in to AniList")
            val info = TextView(context).apply {
                text = "Open this address on another device, sign in, then type the token shown: $url"
                setTextIsSelectable(true)
                setPadding(dp(context, 16), dp(context, 8), dp(context, 16), dp(context, 8))
            }
            root.addView(
                info,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            )
            root.addView(
                pasteRow,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            )
        }
        builder.setView(root)
        builder.setNegativeButton("Cancel", null)
        val d = builder.create()
        dialog = d

        d.setOnDismissListener {
            clearCookies()
            val w = webView
            webView = null
            if (w != null) {
                try {
                    w.stopLoading()
                    w.webViewClient = WebViewClient()
                    (w.parent as? ViewGroup)?.removeView(w)
                } catch (_: Throwable) {
                }
                // Destroy after the current callback has returned.
                Handler(Looper.getMainLooper()).post {
                    try {
                        w.destroy()
                    } catch (_: Throwable) {
                    }
                }
            }
        }

        d.show()
        d.window?.apply {
            // Let the EditText show the keyboard inside a dialog that hosts a WebView.
            clearFlags(
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM
            )
            setSoftInputMode(
                WindowManager.LayoutParams.SOFT_INPUT_STATE_UNSPECIFIED or
                    WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
            )
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        d.getButton(AlertDialog.BUTTON_NEGATIVE)?.isFocusable = true

        if (wv != null) {
            wv.loadUrl(url)
            wv.requestFocus()
        }
    }

    /**
     * Expires only AniList's cookies. removeAllCookies() would also wipe the cookies CloudStream
     * and its providers keep in the shared WebView cookie store (Cloudflare clearance, logins).
     */
    private fun clearCookies() {
        try {
            val cm = CookieManager.getInstance()
            for (url in listOf("https://anilist.co", "https://www.anilist.co")) {
                val raw = cm.getCookie(url) ?: continue
                for (part in raw.split(';')) {
                    val name = part.substringBefore('=').trim()
                    if (name.isEmpty()) continue
                    val expired = "$name=; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Path=/"
                    cm.setCookie(url, expired)
                    cm.setCookie(url, "$expired; Domain=.anilist.co")
                }
            }
            cm.flush()
        } catch (_: Throwable) {
        }
    }

    private fun dp(context: Context, v: Int): Int =
        (v * context.resources.displayMetrics.density + 0.5f).toInt()

    /** Accepts either the raw token or a full redirect URL containing it. */
    private fun tokenFromInput(input: String): String? {
        val s = input.trim()
        if (s.isEmpty()) return null
        AniListClient.extractToken(s)?.let { return it }
        if (s.length >= 20 && s.none { it.isWhitespace() } && !s.startsWith("http")) return s
        return null
    }

    private fun buildPasteRow(context: Context, onUse: (String) -> Unit): LinearLayout {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(context, 8), dp(context, 4), dp(context, 8), dp(context, 4))
        }
        val edit = EditText(context).apply {
            hint = "…or paste the token here"
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = EditorInfo.IME_ACTION_DONE
            isFocusable = true
            isFocusableInTouchMode = true
        }
        val button = Button(context).apply {
            text = "Use token"
            isFocusable = true
        }
        val submit: () -> Unit = {
            val t = tokenFromInput(edit.text.toString())
            if (t != null) {
                onUse(t)
            } else {
                edit.error = "Paste the token (or the full address) shown by AniList"
            }
        }
        button.setOnClickListener { submit() }
        edit.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                submit()
                true
            } else false
        }
        row.addView(edit, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(
            button,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )
        return row
    }

    /** evaluateJavascript returns a JSON-quoted string; strip the quotes and unescape common sequences. */
    private fun unescapeJsResult(raw: String): String {
        var s = raw.trim()
        if (s.length >= 2 && s.startsWith("\"") && s.endsWith("\"")) s = s.substring(1, s.length - 1)
        return s.replace("\\u0026", "&")
            .replace("\\u003D", "=")
            .replace("\\u003d", "=")
            .replace("\\u0023", "#")
            .replace("\\/", "/")
            .replace("\\\"", "\"")
    }
}
