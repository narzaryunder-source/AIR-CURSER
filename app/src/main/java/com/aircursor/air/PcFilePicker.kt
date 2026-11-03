package com.aircursor.air

import android.app.Activity
import android.app.AlertDialog
import java.io.File

/**
 * Pick a file from the PC's home folder (Documents, Downloads, Projects…) for an upload in Air.
 * Only the PC's own folder can be browsed; links that lead outside it are not followed.
 */
class PcFilePicker(private val activity: Activity, private val home: File, private val done: (File?) -> Unit) {
    private var finished = false

    fun show() = open(home)

    private fun open(dir: File) {
        val root = home.canonicalFile
        val entries = (dir.listFiles() ?: emptyArray())
            .filter { !it.name.startsWith(".") }
            .filter { it.canonicalFile.path.startsWith(root.path) }
            .sortedWith(compareBy<File>({ !it.isDirectory }, { it.name.lowercase() }))
        val up = dir.canonicalFile != root
        val labels = ArrayList<String>()
        if (up) labels += "⬑  Up"
        entries.forEach { labels += if (it.isDirectory) "📁  ${it.name}" else "📄  ${it.name}  (${size(it.length())})" }
        AlertDialog.Builder(activity)
            .setTitle(if (up) "PC: ~/" + dir.canonicalFile.path.removePrefix(root.path + "/") else "PC: home folder")
            .setItems(labels.toTypedArray()) { _, i ->
                val idx = if (up) i - 1 else i
                if (idx < 0) {
                    open(dir.parentFile ?: home)
                    return@setItems
                }
                val f = entries[idx]
                if (f.isDirectory) open(f) else finish(f)
            }
            .setNegativeButton("Cancel") { _, _ -> finish(null) }
            .setOnCancelListener { finish(null) }
            .show()
    }

    private fun finish(f: File?) {
        if (finished) return
        finished = true
        done(f)
    }

    private fun size(b: Long) = when {
        b >= 1_000_000 -> "${b / 1_000_000} MB"
        b >= 1000 -> "${b / 1000} KB"
        else -> "$b B"
    }
}
