package com.school.pendrivesync

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.storage.StorageManager
import android.provider.Settings
import android.view.ViewGroup
import android.webkit.MimeTypeMap
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import androidx.core.content.FileProvider
import java.io.File

class MainActivity : Activity() {
    private lateinit var store: Store
    private lateinit var status: TextView
    private lateinit var list: ListView
    private var cur: File = Paths.collected
    private var items: List<File> = emptyList()

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        store = Store(this)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 24, 24, 24) }
        status = TextView(this)
        val alt = Button(this).apply {
            text = "Arm: next inserted drive = ALT master"
            setOnClickListener { store.armAlt = true; AppLog.add("Next inserted drive will become an ALT master") }
        }
        val reset = Button(this).apply {
            text = "Reset masters (next drive becomes master)"
            setOnClickListener { store.masters = emptySet(); store.armAlt = false; AppLog.add("Masters reset") }
        }
        val show = Button(this).apply {
            text = "Show detected drives"
            setOnClickListener { showDrives() }
        }
        list = ListView(this)
        col.addView(status); col.addView(show); col.addView(alt); col.addView(reset)
        col.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(col)
        list.setOnItemClickListener { _, _, pos, _ ->
            val f = items[pos]
            if (f.isDirectory) { cur = f; refresh() } else open(f)
        }
        AppLog.listener = { runOnUiThread { refresh() } }
    }

    override fun onResume() {
        super.onResume()
        if (!Access.ok(this)) { askAccess(); } else {
            Paths.collected.mkdirs()
            val s = Intent(this, SyncService::class.java)
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(s) else startService(s)
        }
        refresh()
    }

    private fun showDrives() {
        val sm = getSystemService(StorageManager::class.java)
        sm.storageVolumes.forEach { v ->
            val dir = if (Build.VERSION.SDK_INT >= 30) v.directory else null
            AppLog.add("Drive '${v.getDescription(this)}' id=${v.uuid} removable=${v.isRemovable} state=${v.state} items=${dir?.list()?.size ?: "?"}")
        }
    }

    private fun askAccess() {
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
            } catch (_: Exception) {
                try { startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
                catch (_: Exception) { AppLog.add("Cannot open All-files-access settings; grant it manually") }
            }
        } else {
            requestPermissions(arrayOf(
                android.Manifest.permission.READ_EXTERNAL_STORAGE,
                android.Manifest.permission.WRITE_EXTERNAL_STORAGE), 1)
        }
    }

    override fun onRequestPermissionsResult(rc: Int, p: Array<out String>, g: IntArray) { onResume() }

    private fun refresh() {
        status.text = "Masters: ${store.masters.size}" + (if (store.armAlt) "  (alt armed)" else "") +
            "\n" + AppLog.tail(8).joinToString("\n") + "\n\n" + cur.path
        items = (cur.listFiles()?.filter { !it.name.endsWith(".part") }
            ?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))) ?: emptyList()
        list.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1,
            items.map { (if (it.isDirectory) "[dir] " else "") + it.name })
    }

    private fun open(f: File) {
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.fp", f)
            val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(f.extension.lowercase()) ?: "*/*"
            startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        } catch (e: Exception) { AppLog.add("Cannot open ${f.name}: ${e.message}") }
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        if (cur != Paths.collected && cur.parentFile != null) { cur = cur.parentFile!!; refresh() } else super.onBackPressed()
    }
}
