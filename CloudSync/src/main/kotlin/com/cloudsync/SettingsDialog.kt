package com.cloudsync

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.Log
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import com.cloudsync.core.IndexFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Single settings screen: one scrolling column, fully operable with a D-pad. */
object SettingsDialog {
    fun show(context: Context, engine: SyncEngine) {
        try {
            Ui(context, engine).open()
        } catch (t: Throwable) {
            Log.e("CloudSync", "Could not open settings", t)
        }
    }

    private const val BG = 0xFF1E1E1E.toInt()
    private const val FG = 0xFFEEEEEE.toInt()
    private const val DIM = 0xFFB0B0B0.toInt()
    private const val WARN = 0xFFFF8A80.toInt()
    private const val ACCENT = 0xFF64B5F6.toInt()
    private const val BTN = 0xFF3A3A3A.toInt()
    private const val BTN_PRESSED = 0xFF1565C0.toInt()
    private const val BTN_FOCUSED = 0xFF1E88E5.toInt()

    private class Ui(private val ctx: Context, private val engine: SyncEngine) {
        private val settings: Settings get() = engine.settings
        private val github: GitHubClient get() = engine.github
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        private val handler = Handler(Looper.getMainLooper())
        private val root = LinearLayout(ctx)

        private var showing = false
        private var busy = false
        private var message: String? = null
        private var messageError = false
        private var focusTag: String? = null

        // GitHub sign-in
        private var loginJob: Job? = null
        private var deviceCode: GitHubClient.DeviceCode? = null

        // Shared profile index
        private var index: IndexFile? = null
        private var indexLoading = false
        private var indexError: String? = null

        fun open() {
            root.orientation = LinearLayout.VERTICAL
            showing = true
            engine.onStatusChanged = { handler.post { if (showing) render() } }
            render()
            popup(root, softInput = false) {
                showing = false
                engine.onStatusChanged = null
                scope.cancel()
            }
        }

        // ---- rendering ------------------------------------------------------------------------

        private fun render() {
            if (!showing) return
            (root.findFocus()?.tag as? String)?.let { focusTag = it }
            root.removeAllViews()

            statusBlock()
            githubSection()
            if (github.isSignedIn && settings.repo != null) {
                deviceSection()
                profilesSection()
                anilistSection()
            }
            add(button("Close", "close") { closeAll() }, 20)

            val t = focusTag
            var restored = false
            if (t != null) {
                val v = root.findViewWithTag<View>(t)
                if (v != null && v.isEnabled) restored = v.requestFocus()
            }
            // The focused view was just removed; with a D-pad nothing would be focused at all.
            if (!restored) root.requestFocus()
        }

        private fun closeAll() {
            // Dismissing the dialog runs the cleanup in the dismiss listener.
            mainDialog?.dismiss()
        }

        private fun statusBlock() {
            add(heading("CloudSync"), 0)
            add(text("Last device sync: " + relative(settings.lastDeviceSync)), 6)
            add(text("Last AniList sync: " + relative(settings.lastAnilistSync)), 2)
            settings.lastError?.let { add(text(it, WARN), 6) }
            message?.let { add(text(it, if (messageError) WARN else ACCENT), 8) }
        }

        private fun githubSection() {
            add(heading("GitHub"), 18)
            if (!github.isSignedIn) {
                if (loginJob != null) {
                    val code = deviceCode
                    if (code == null) {
                        add(text("Contacting GitHub…"), 6)
                    } else {
                        add(text("On your phone or computer open"), 6)
                        add(bigText(code.verificationUri.removePrefix("https://").removePrefix("http://")), 2)
                        add(text("and enter the code"), 6)
                        add(bigText(code.userCode), 2)
                        add(text("Waiting for approval…", DIM), 8)
                    }
                    add(button("Cancel", "login_cancel") { cancelLogin() }.also { it.isEnabled = true; it.alpha = 1f }, 8)
                } else {
                    add(button("Sign in to GitHub", "gh_signin") { startLogin() }, 6)
                }
                return
            }
            if (settings.repo == null) {
                add(
                    text(
                        "Signed in as ${settings.githubUser ?: "?"}, but the CloudSync GitHub App is not " +
                            "installed on any repository yet. Install it on your private sync repo, then press Check again."
                    ), 6
                )
                add(button("Check again", "gh_check") {
                    op {
                        val r = github.discoverRepo()
                        if (r == null) throw IllegalStateException("Still no repository found.") else "Found repository $r"
                    }
                }, 8)
                add(button("Sign out", "gh_signout") { confirmSignOut() }, 6)
            } else {
                add(text("GitHub: ${settings.githubUser ?: "?"} · ${settings.repo}"), 6)
                add(button("Sign out of GitHub", "gh_signout") { confirmSignOut() }, 8)
            }
        }

        private fun deviceSection() {
            add(heading("Device sync"), 18)
            add(switch("Pause device sync on this device", "sw_device", settings.devicePaused) {
                settings.devicePaused = it
            }, 6)
            add(button("Sync devices now", "sync_devices") { op { engine.syncDevicesNow() } }, 8)
        }

        private fun profilesSection() {
            add(heading("Profiles on this device"), 18)
            val idx = index
            if (idx == null) {
                val err = indexError
                if (err != null) {
                    add(text(err, WARN), 6)
                    add(button("Retry", "idx_retry") { indexError = null; render() }, 6)
                } else {
                    add(text("Loading…", DIM), 6)
                    if (!indexLoading) loadIndexAsync()
                }
                return
            }
            val profiles = engine.store.profiles()
            for (p in profiles) {
                val linkedId = settings.linkOf(p.slot)
                val linkedName = when {
                    linkedId == null -> "Not synced"
                    else -> idx.profiles.firstOrNull { it.id == linkedId }?.name ?: "Shared profile"
                }
                add(text("${p.name}  →  $linkedName", FG, bold = true), 14)
                add(button("Change", "chg_${p.slot}") { changeDialog(p.slot, p.name, linkedId, idx) }, 4)
                if (linkedId != null) {
                    if (settings.anilistToken(linkedId) == null) {
                        add(button("Sign in to AniList", "al_in_${p.slot}") { anilistSignIn(linkedId) }, 4)
                    } else {
                        add(text("AniList: ${settings.anilistUserName(linkedId) ?: "signed in"}"), 4)
                        add(button("Sign out of AniList", "al_out_${p.slot}") {
                            try {
                                engine.signOutAniList(linkedId)
                            } catch (e: Throwable) {
                                message = e.message ?: e.javaClass.simpleName
                                messageError = true
                            }
                            render()
                        }, 4)
                    }
                    val unmatched = try {
                        engine.unmatchedTitles(linkedId)
                    } catch (e: Throwable) {
                        emptyList()
                    }
                    if (unmatched.isNotEmpty()) {
                        add(text("${unmatched.size} shows could not be matched to AniList", DIM), 4)
                        add(button("Show", "al_un_${p.slot}") { unmatchedDialog(unmatched) }, 4)
                    }
                }
            }
        }

        private fun anilistSection() {
            add(heading("AniList"), 18)
            val idx = index
            if (idx != null) {
                add(switch("Pause AniList sync (all devices)", "sw_anilist", idx.anilistPaused) { v ->
                    op(refresh = true) {
                        engine.setAnilistPaused(v)
                        null
                    }
                }, 6)
            }
            add(button("Sync AniList now", "sync_anilist") { op { engine.syncAniListNow() } }, 8)
        }

        // ---- actions --------------------------------------------------------------------------

        /** Runs [block] with a busy state; its returned text (or the error) is shown in the dialog. */
        private fun op(refresh: Boolean = false, block: suspend () -> String?) {
            if (busy) return
            busy = true
            message = "Working…"
            messageError = false
            render()
            scope.launch {
                try {
                    message = block()
                    messageError = false
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    message = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
                    messageError = true
                }
                if (refresh) refreshIndex()
                busy = false
                render()
            }
        }

        private suspend fun refreshIndex() {
            try {
                index = engine.loadIndex()
                indexError = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                indexError = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
            }
        }

        private fun loadIndexAsync() {
            indexLoading = true
            scope.launch {
                refreshIndex()
                indexLoading = false
                render()
            }
        }

        private fun startLogin() {
            if (loginJob != null) return
            message = null
            focusTag = "login_cancel"
            loginJob = scope.launch {
                try {
                    val code = github.requestDeviceCode()
                    deviceCode = code
                    render()
                    val ok = github.awaitDeviceToken(code)
                    deviceCode = null
                    loginJob = null
                    if (ok) {
                        message = null
                        messageError = false
                        index = null
                        indexError = null
                        try {
                            engine.start()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Throwable) {
                        }
                    } else {
                        message = "The code expired or was denied. Try again."
                        messageError = true
                    }
                    focusTag = null
                    render()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    deviceCode = null
                    loginJob = null
                    message = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
                    messageError = true
                    focusTag = null
                    render()
                }
            }
            render()
        }

        private fun cancelLogin() {
            loginJob?.cancel()
            loginJob = null
            deviceCode = null
            message = null
            focusTag = "gh_signin"
            render()
        }

        private fun confirmSignOut() {
            confirm("Sign out of GitHub on this device?", "Sign out") {
                github.signOut()
                index = null
                indexError = null
                message = null
                focusTag = "gh_signin"
                render()
            }
        }

        private fun anilistSignIn(profileId: String) {
            try {
                AniListLogin.show(ctx) { token ->
                    op {
                        val name = engine.signInAniList(profileId, token)
                        "Signed in to AniList as $name"
                    }
                }
            } catch (e: Throwable) {
                message = e.message ?: e.javaClass.simpleName
                messageError = true
                render()
            }
        }

        // ---- sub dialogs ----------------------------------------------------------------------

        private fun changeDialog(slot: Int, csName: String, currentId: String?, idx: IndexFile) {
            val shared = idx.profiles
            val labels = ArrayList<String>()
            labels.add("Don't sync")
            shared.forEach { labels.add(it.name) }
            labels.add("New shared profile…")
            val selected = if (currentId == null) 0 else {
                val i = shared.indexOfFirst { it.id == currentId }
                if (i >= 0) i + 1 else 0
            }
            val content = column()
            var dlg: AlertDialog? = null
            content.addView(heading("Sync \"$csName\" with"))
            labels.forEachIndexed { i, label ->
                val b = button((if (i == selected) "●  " else "○  ") + label, "pick_$i") {
                    dlg?.dismiss()
                    when {
                        i == 0 -> op(refresh = true) {
                            engine.link(slot, null)
                            "\"$csName\" is no longer synced."
                        }
                        i == labels.size - 1 -> newProfileDialog(slot, csName)
                        else -> {
                            val ref = shared[i - 1]
                            op(refresh = true) {
                                engine.link(slot, ref.id)
                                "\"$csName\" is now synced with \"${ref.name}\"."
                            }
                        }
                    }
                }
                b.isEnabled = true
                b.alpha = 1f
                content.addView(b, lp(6))
            }
            val cancel = button("Cancel", "pick_cancel") { dlg?.dismiss() }
            cancel.isEnabled = true
            cancel.alpha = 1f
            content.addView(cancel, lp(14))
            dlg = popup(content, softInput = false)
            content.findViewWithTag<View>("pick_$selected")?.requestFocus()
        }

        private fun newProfileDialog(slot: Int, csName: String) {
            val content = column()
            var dlg: AlertDialog? = null
            content.addView(heading("New shared profile"))
            val edit = EditText(ctx).apply {
                setText(csName)
                setSelection(text.length)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                setTextColor(FG)
                setHintTextColor(DIM)
                hint = "Profile name"
                setSingleLine(true)
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
                imeOptions = EditorInfo.IME_ACTION_DONE
                tag = "np_name"
                isFocusable = true
                isFocusableInTouchMode = true
            }
            content.addView(edit, lp(10))
            fun submit() {
                val name = edit.text.toString().trim()
                if (name.isEmpty()) {
                    edit.error = "Enter a name"
                    return
                }
                dlg?.dismiss()
                op(refresh = true) {
                    val ref = engine.createProfile(name)
                    engine.link(slot, ref.id)
                    "Created \"${ref.name}\" and synced \"$csName\" with it."
                }
            }
            edit.setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_DONE) {
                    submit()
                    true
                } else false
            }
            val create = button("Create", "np_ok") { submit() }
            create.isEnabled = true
            create.alpha = 1f
            val cancel = button("Cancel", "np_cancel") { dlg?.dismiss() }
            cancel.isEnabled = true
            cancel.alpha = 1f
            content.addView(create, lp(10))
            content.addView(cancel, lp(6))
            dlg = popup(content, softInput = true)
            edit.requestFocus()
        }

        private fun unmatchedDialog(titles: List<String>) {
            val content = column()
            var dlg: AlertDialog? = null
            content.addView(heading("Not matched to AniList"))
            content.addView(text(titles.joinToString("\n") { "•  $it" }), lp(8))
            val close = button("Close", "um_close") { dlg?.dismiss() }
            close.isEnabled = true
            close.alpha = 1f
            content.addView(close, lp(12))
            dlg = popup(content, softInput = false)
            close.requestFocus()
        }

        private fun confirm(question: String, yes: String, onYes: () -> Unit) {
            val content = column()
            var dlg: AlertDialog? = null
            content.addView(text(question, FG, bold = true))
            val ok = button(yes, "cf_yes") {
                dlg?.dismiss()
                onYes()
            }
            ok.isEnabled = true
            ok.alpha = 1f
            val no = button("Cancel", "cf_no") { dlg?.dismiss() }
            no.isEnabled = true
            no.alpha = 1f
            content.addView(ok, lp(12))
            content.addView(no, lp(6))
            dlg = popup(content, softInput = false)
            no.requestFocus()
        }

        // ---- widgets --------------------------------------------------------------------------

        private var mainDialog: AlertDialog? = null

        private fun popup(content: LinearLayout, softInput: Boolean, onDismiss: (() -> Unit)? = null): AlertDialog {
            val dlg = AlertDialog.Builder(ctx).create()
            val scroll = ScrollView(ctx).apply {
                setBackgroundColor(BG)
                isFillViewport = false
                addView(
                    content,
                    ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                )
            }
            dlg.setView(scroll)
            val isMain = content === root
            if (isMain) mainDialog = dlg
            dlg.setOnDismissListener {
                if (isMain) mainDialog = null
                onDismiss?.invoke()
            }
            dlg.show()
            dlg.window?.let { w ->
                w.setBackgroundDrawable(ColorDrawable(BG))
                // Allow text input (keyboard) inside the dialog.
                w.clearFlags(
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM
                )
                w.setSoftInputMode(
                    (if (softInput) WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE
                    else WindowManager.LayoutParams.SOFT_INPUT_STATE_UNSPECIFIED) or
                        WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                )
                val screenW = ctx.resources.displayMetrics.widthPixels
                val width = minOf((screenW * 0.9f).toInt(), dp(720))
                w.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
            }
            return dlg
        }

        private fun column(): LinearLayout = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
            setPadding(dp(20), dp(20), dp(20), dp(20))
        }

        private fun lp(topDp: Int) = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(topDp) }

        /** Adds [v] to the main column; the root has no padding of its own so it gets it here. */
        private fun add(v: View, topDp: Int) {
            root.setPadding(dp(20), dp(20), dp(20), dp(20))
            root.setBackgroundColor(BG)
            root.addView(v, lp(topDp))
        }

        private fun heading(s: String) = TextView(ctx).apply {
            text = s
            setTextColor(ACCENT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            setTypeface(typeface, Typeface.BOLD)
        }

        private fun text(s: String, color: Int = FG, bold: Boolean = false) = TextView(ctx).apply {
            text = s
            setTextColor(color)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }

        private fun bigText(s: String) = TextView(ctx).apply {
            text = s
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 28f)
            setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
            setTextIsSelectable(true)
        }

        private fun rounded(color: Int) = GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(6).toFloat()
        }

        private fun button(label: String, tag: String, onClick: () -> Unit) = Button(ctx).apply {
            text = label
            this.tag = tag
            isAllCaps = false
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            setTextColor(Color.WHITE)
            minimumHeight = dp(48)
            setPadding(dp(16), dp(8), dp(16), dp(8))
            background = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_pressed), rounded(BTN_PRESSED))
                addState(intArrayOf(android.R.attr.state_focused), rounded(BTN_FOCUSED))
                addState(intArrayOf(), rounded(BTN))
            }
            isFocusable = true
            isEnabled = !busy
            alpha = if (busy) 0.5f else 1f
            setOnClickListener { onClick() }
        }

        private fun switch(label: String, tag: String, checked: Boolean, onChange: (Boolean) -> Unit) =
            Switch(ctx).apply {
                text = label
                this.tag = tag
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
                setTextColor(FG)
                minimumHeight = dp(48)
                setPadding(dp(8), dp(8), dp(8), dp(8))
                background = StateListDrawable().apply {
                    addState(intArrayOf(android.R.attr.state_focused), rounded(0xFF2C3E55.toInt()))
                    addState(intArrayOf(), ColorDrawable(Color.TRANSPARENT))
                }
                isFocusable = true
                isEnabled = !busy
                // Set the state before attaching the listener so render() never fires it.
                setOnCheckedChangeListener(null)
                isChecked = checked
                setOnCheckedChangeListener { _, value -> onChange(value) }
            }

        private fun dp(v: Int): Int = (v * ctx.resources.displayMetrics.density + 0.5f).toInt()

        private fun relative(t: Long): String {
            if (t <= 0L) return "never"
            val diff = System.currentTimeMillis() - t
            val min = diff / 60_000L
            return when {
                diff < 60_000L -> "just now"
                min < 60 -> "$min min ago"
                min < 60 * 24 -> "${min / 60} h ago"
                else -> {
                    val days = min / (60 * 24)
                    if (days == 1L) "1 day ago" else "$days days ago"
                }
            }
        }
    }
}
