package com.school.pendrivesync

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        if (!Access.ok(c)) return
        val s = Intent(c, SyncService::class.java)
        if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(s) else c.startService(s)
    }
}
