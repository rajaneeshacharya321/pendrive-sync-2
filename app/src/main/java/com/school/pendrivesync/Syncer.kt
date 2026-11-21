package com.school.pendrivesync

import android.os.StatFs
import java.io.File

object Syncer {
    private val skipDirs = setOf("system volume information", "lost.dir", "android", "\$recycle.bin", "studymaterials")

    private fun safe(s: String) = s.replace(Regex("[^A-Za-z0-9 _-]"), "_").trim().ifEmpty { "drive" }

    private fun copyAtomic(from: File, to: File) {
        to.parentFile?.mkdirs()
        val tmp = File(to.path + ".part")
        from.copyTo(tmp, overwrite = true)
        if (!tmp.renameTo(to)) { tmp.copyTo(to, overwrite = false); tmp.delete() }
    }

    /** Teacher pendrive -> Collected/<label>_<uuid>/ . Never overwrites; only new/changed files are added. */
    fun importFrom(src: File, uuid: String, label: String, store: Store): Int {
        val dest = File(Paths.collected, "${safe(label)}_$uuid")
        var n = 0
        src.walkTopDown()
            .onEnter { d -> d == src || !(d.name.startsWith(".") || d.name.lowercase() in skipDirs) }
            .filter { it.isFile && !it.name.startsWith(".") }
            .forEach { f ->
                try {
                    val rel = f.relativeTo(src).path
                    if (store.has(uuid, rel, f.length(), f.lastModified())) return@forEach
                    var out = File(dest, rel)
                    if (out.exists()) {
                        val ext = if (out.extension.isEmpty()) "" else "." + out.extension
                        out = File(out.parentFile, "${out.nameWithoutExtension}_${f.lastModified()}$ext")
                    }
                    if (!out.exists()) copyAtomic(f, out)
                    store.add(uuid, rel, f.length(), f.lastModified())
                    n++
                } catch (e: Exception) {
                    AppLog.add("Failed: ${f.name} (${e.message})")
                }
            }
        return n
    }

    /** Everything collected -> <master>/StudyMaterials/ . Returns files copied, or -1 if not enough space. */
    fun exportTo(master: File): Int {
        val src = Paths.collected
        if (!src.exists()) return 0
        val destRoot = File(master, "StudyMaterials")
        val todo = src.walkTopDown().filter { it.isFile && !it.name.endsWith(".part") }.filter {
            val o = File(destRoot, it.relativeTo(src).path)
            !(o.exists() && o.length() == it.length())
        }.toList()
        val need = todo.sumOf { it.length() }
        val free = try { StatFs(master.path).availableBytes } catch (_: Exception) { Long.MAX_VALUE }
        if (need > free) return -1
        var n = 0
        todo.forEach { f ->
            try {
                copyAtomic(f, File(destRoot, f.relativeTo(src).path))
                n++
            } catch (e: Exception) {
                AppLog.add("Failed: ${f.name} (${e.message})")
            }
        }
        return n
    }
}
