package com.school.pendrivesync

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.os.Build
import android.os.Environment
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Paths {
    val root: File get() = File(Environment.getExternalStorageDirectory(), "StudyMaterials")
    val collected: File get() = File(root, "Collected")
}

object Access {
    fun ok(c: Context): Boolean =
        if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
        else c.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
}

object AppLog {
    private val lines = mutableListOf<String>()
    @Volatile var listener: (() -> Unit)? = null
    private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    @Synchronized fun add(msg: String) {
        val line = "${fmt.format(Date())}  $msg"
        lines.add(line)
        if (lines.size > 200) lines.removeAt(0)
        try {
            Paths.root.mkdirs()
            File(Paths.root, "log.txt").appendText(line + "\n")
        } catch (_: Exception) {}
        listener?.invoke()
    }

    @Synchronized fun tail(n: Int): List<String> = lines.takeLast(n)
}

class Store(ctx: Context) : SQLiteOpenHelper(ctx.applicationContext, "sync.db", null, 1) {
    private val appCtx = ctx.applicationContext
    private val prefs = appCtx.getSharedPreferences("cfg", Context.MODE_PRIVATE)

    /** Preset master ID baked into the APK (assets/master_id.txt). Empty = first drive inserted becomes master. */
    private val preset: String by lazy {
        try {
            appCtx.assets.open("master_id.txt").bufferedReader().use { it.readLines() }
                .map { it.trim() }.firstOrNull { it.isNotEmpty() && !it.startsWith("#") }?.uppercase() ?: ""
        } catch (_: Exception) { "" }
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE copied(uuid TEXT, rel TEXT, size INTEGER, mtime INTEGER, PRIMARY KEY(uuid, rel, size, mtime))")
    }
    override fun onUpgrade(db: SQLiteDatabase, o: Int, n: Int) {}

    var masters: Set<String>
        get() {
            val m = HashSet(prefs.getStringSet("masters", emptySet()) ?: emptySet())
            if (preset.isNotEmpty()) m.add(preset)
            return m
        }
        set(v) { prefs.edit().putStringSet("masters", HashSet(v)).apply() }

    var armAlt: Boolean
        get() = prefs.getBoolean("armAlt", false)
        set(v) { prefs.edit().putBoolean("armAlt", v).apply() }

    fun has(uuid: String, rel: String, size: Long, mtime: Long): Boolean =
        readableDatabase.rawQuery(
            "SELECT 1 FROM copied WHERE uuid=? AND rel=? AND size=? AND mtime=?",
            arrayOf(uuid, rel, size.toString(), mtime.toString())
        ).use { it.moveToFirst() }

    fun add(uuid: String, rel: String, size: Long, mtime: Long) {
        writableDatabase.execSQL("INSERT OR IGNORE INTO copied VALUES(?,?,?,?)", arrayOf<Any>(uuid, rel, size, mtime))
    }
}
