package com.school.pendrivesync

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

class SyncService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private val busy = AtomicBoolean(false)
    private val seen = HashSet<String>()
    private lateinit var store: Store

    private val poll = object : Runnable {
        override fun run() { scan(); handler.postDelayed(this, 8000) }
    }
    private val rx = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) { scan() }
    }

    override fun onCreate() {
        super.onCreate()
        store = Store(this)
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(NotificationChannel("sync", "Pendrive Sync", NotificationManager.IMPORTANCE_LOW))
        }
        startForeground(1, notif("Waiting for a pendrive"))
        registerReceiver(rx, IntentFilter(Intent.ACTION_MEDIA_MOUNTED).apply { addDataScheme("file") })
        handler.post(poll)
    }

    override fun onStartCommand(i: Intent?, f: Int, id: Int) = START_STICKY
    override fun onBind(i: Intent?): IBinder? = null
    override fun onDestroy() { handler.removeCallbacks(poll); try { unregisterReceiver(rx) } catch (_: Exception) {}; super.onDestroy() }

    private fun notif(text: String): Notification {
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, "sync") else Notification.Builder(this)
        return b.setContentTitle("Pendrive Sync").setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_save).build()
    }
    private fun note(t: String) = getSystemService(NotificationManager::class.java).notify(1, notif(t))

    private fun scan() {
        if (!Access.ok(this)) return
        val sm = getSystemService(StorageManager::class.java)
        val vols = sm.storageVolumes.filter { it.isRemovable && it.state == Environment.MEDIA_MOUNTED && it.uuid != null }
        seen.retainAll(vols.map { it.uuid!! }.toSet())   // unplugged drives are forgotten -> re-insert triggers again
        for (v in vols) {
            val u = v.uuid!!
            if (u in seen) continue
            if (!busy.compareAndSet(false, true)) return
            seen.add(u)
            Thread {
                try { handle(v) } catch (e: Exception) { AppLog.add("Error: ${e.message}") }
                finally { busy.set(false) }
            }.start()
            return
        }
    }

    private fun handle(v: StorageVolume) {
        val u = v.uuid ?: return
        val label = v.getDescription(this)
        val dir: File? = if (Build.VERSION.SDK_INT >= 30) v.directory else File("/storage/$u")
        if (dir == null || !dir.canRead()) { AppLog.add("Cannot read drive $label ($u)"); return }

        if (store.masters.isEmpty()) {
            store.masters = setOf(u)
            AppLog.add("First drive '$label' ($u) registered as MASTER")
        } else if (store.armAlt && u !in store.masters) {
            store.masters = store.masters + u
            store.armAlt = false
            AppLog.add("Drive '$label' ($u) registered as ALT master")
        }

        if (u in store.masters) {
            note("Master drive: exporting...")
            AppLog.add("Master drive detected, exporting")
            val n = Syncer.exportTo(dir)
            AppLog.add(if (n < 0) "Export FAILED: not enough free space on master" else "Export done: $n file(s) copied")
        } else {
            note("Copying from $label...")
            val n = Syncer.importFrom(dir, u, label, store)
            AppLog.add("Imported $n new file(s) from '$label' ($u)")
        }
        note("Done - safe to remove drive")
        AppLog.add("Safe to remove drive")
    }
}
