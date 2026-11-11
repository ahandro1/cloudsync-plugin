package com.cloudsync

import android.content.Context
import android.content.SharedPreferences

/** Public client IDs. Neither is a secret; both flows used here work without a client secret. */
object Config {
    /** Client ID of the GitHub App installed on the private sync repo. */
    const val GITHUB_CLIENT_ID = "Iv23ctvuOkOa0R3buvHw"

    /** Client ID of the AniList API client (redirect URL https://anilist.co/api/v2/oauth/pin). */
    const val ANILIST_CLIENT_ID = "56609"

    /** CloudStream's own data store. */
    const val CS_PREFS = "rebuild_preference"

    /** This plugin's private preferences file. */
    const val PLUGIN_PREFS = "cloudsync_plugin"
}

/**
 * This plugin's own per-device settings and secrets. Nothing in here is ever uploaded.
 * All access goes through this object so the key names live in one place.
 */
class Settings(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(Config.PLUGIN_PREFS, Context.MODE_PRIVATE)

    // --- GitHub -------------------------------------------------------------------------------
    var githubToken: String?
        get() = prefs.getString("gh_token", null)
        set(v) = put("gh_token", v)
    var githubRefreshToken: String?
        get() = prefs.getString("gh_refresh", null)
        set(v) = put("gh_refresh", v)
    /** Epoch ms when [githubToken] expires; 0 = does not expire. */
    var githubTokenExpiry: Long
        get() = prefs.getLong("gh_expiry", 0)
        set(v) = prefs.edit().putLong("gh_expiry", v).apply()
    var githubUser: String?
        get() = prefs.getString("gh_user", null)
        set(v) = put("gh_user", v)
    /** "owner/name" of the private sync repo, discovered after sign-in. */
    var repo: String?
        get() = prefs.getString("gh_repo", null)
        set(v) = put("gh_repo", v)

    // --- Device sync --------------------------------------------------------------------------
    /** Per-device "Pause device sync" switch. */
    var devicePaused: Boolean
        get() = prefs.getBoolean("device_paused", false)
        set(v) = prefs.edit().putBoolean("device_paused", v).apply()

    /** True once the first-run default link (slot 0 -> "Default") has been attempted. */
    var defaultLinkDone: Boolean
        get() = prefs.getBoolean("default_link_done", false)
        set(v) = prefs.edit().putBoolean("default_link_done", v).apply()

    /** Shared profile id linked to a local CloudStream profile slot, or null = don't sync. */
    fun linkOf(slot: Int): String? = prefs.getString("link_$slot", null)

    fun setLink(slot: Int, profileId: String?) = put("link_$slot", profileId)

    /** All links on this device: slot -> shared profile id. */
    fun links(): Map<Int, String> = prefs.all.mapNotNull { (k, v) ->
        val slot = k.removePrefix("link_").toIntOrNull()
        if (k.startsWith("link_") && slot != null && v is String) slot to v else null
    }.toMap()

    // --- AniList (one account per shared profile, stored per device) ---------------------------
    fun anilistToken(profileId: String): String? = prefs.getString("al_token_$profileId", null)
    fun setAnilistToken(profileId: String, token: String?) = put("al_token_$profileId", token)
    fun anilistUserName(profileId: String): String? = prefs.getString("al_user_$profileId", null)
    fun setAnilistUserName(profileId: String, name: String?) = put("al_user_$profileId", name)
    fun anilistUserId(profileId: String): Int = prefs.getInt("al_uid_$profileId", 0)
    fun setAnilistUserId(profileId: String, id: Int) =
        prefs.edit().putInt("al_uid_$profileId", id).apply()

    // --- Status lines -------------------------------------------------------------------------
    var lastDeviceSync: Long
        get() = prefs.getLong("last_device_sync", 0)
        set(v) = prefs.edit().putLong("last_device_sync", v).apply()
    var lastAnilistSync: Long
        get() = prefs.getLong("last_anilist_sync", 0)
        set(v) = prefs.edit().putLong("last_anilist_sync", v).apply()
    var lastError: String?
        get() = prefs.getString("last_error", null)
        set(v) = put("last_error", v)

    private fun put(key: String, value: String?) {
        prefs.edit().apply { if (value == null) remove(key) else putString(key, value) }.apply()
    }
}
