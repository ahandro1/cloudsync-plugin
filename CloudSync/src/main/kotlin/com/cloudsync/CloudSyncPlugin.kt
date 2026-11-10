package com.cloudsync

import android.content.Context
import android.util.Log
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class CloudSyncPlugin : Plugin() {

    override fun load(context: Context) {
        try {
            val appContext = context.applicationContext ?: context
            // A plugin reload creates a new plugin instance but the old engine must not keep running.
            synchronized(lock) {
                try {
                    engine?.stop()
                } catch (t: Throwable) {
                    Log.e(TAG, "Stopping previous engine failed", t)
                }
                engine = null
                val e = SyncEngine(appContext)
                engine = e
                e.start()
            }
            openSettings = { ctx ->
                try {
                    val e = synchronized(lock) { engine }
                    if (e != null) SettingsDialog.show(ctx, e)
                } catch (t: Throwable) {
                    Log.e(TAG, "Opening settings failed", t)
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "CloudSync failed to load", t)
        }
    }

    override fun beforeUnload() {
        try {
            synchronized(lock) {
                engine?.stop()
                engine = null
            }
        } catch (t: Throwable) {
            Log.e(TAG, "CloudSync failed to unload cleanly", t)
        }
    }

    companion object {
        private const val TAG = "CloudSync"
        private val lock = Any()
        private var engine: SyncEngine? = null
    }
}
