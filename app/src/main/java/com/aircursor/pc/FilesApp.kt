@file:OptIn(ExperimentalFoundationApi::class)

package com.aircursor.pc

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.horizontalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.documentfile.provider.DocumentFile
import com.aircursor.core.SafeNames
import com.aircursor.data.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val KEY_ROOT = "pc_files_root"
private const val KEY_BIN_MAP = "pc_bin_map"
private const val BIN_NAME = "AirCursor Recycle Bin"

private class FileItem(val doc: DocumentFile, val name: String, val isDir: Boolean, val size: Long)

/**
 * A safe file explorer. It only sees the folder the user picks (no "all files" access) and it
 * never deletes anything: "Delete" moves the item into a Recycle Bin folder, from where it can
 * be restored. Copies and new files always get a new name, so nothing is overwritten.
 */
@Composable
fun FilesApp() {
    val context = LocalContext.current
    val sp = remember { Prefs(context).sp }
    val scope = rememberCoroutineScope()
    var root by remember { mutableStateOf(sp.getString(KEY_ROOT, null)?.let(Uri::parse)) }
    val stack = remember { mutableStateListOf<DocumentFile>() }
    var items by remember { mutableStateOf<List<FileItem>>(emptyList()) }
    var refresh by remember { mutableIntStateOf(0) }
    var showBin by remember { mutableStateOf(false) }
    var clipboard by remember { mutableStateOf<DocumentFile?>(null) }
    var renaming by remember { mutableStateOf<FileItem?>(null) }
    var newFolder by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            } catch (_: SecurityException) {
            }
            sp.edit().putString(KEY_ROOT, uri.toString()).apply()
            stack.clear()
            showBin = false
            root = uri
        }
    }

    val rootUri = root
    if (rootUri == null) {
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            Text("📁 Files", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Text(
                "Choose a folder to browse (for example Download or Documents). AirCursor only sees " +
                    "the folder you choose, and it never deletes your files: \"Delete\" moves them to a " +
                    "Recycle Bin from where you can restore them.",
                fontSize = 13.sp,
            )
            Spacer(Modifier.padding(6.dp))
            ToolButton("📂 Choose folder") { picker.launch(null) }
        }
        return
    }

    val rootDoc = remember(rootUri) { DocumentFile.fromTreeUri(context, rootUri) }
    var binDoc by remember { mutableStateOf<DocumentFile?>(null) }
    LaunchedEffect(showBin, refresh) {
        if (showBin) binDoc = withContext(Dispatchers.IO) { rootDoc?.findFile(BIN_NAME) }
    }
    val current: DocumentFile? = if (showBin) binDoc else stack.lastOrNull() ?: rootDoc
    val hideBin = !showBin && stack.isEmpty()

    LaunchedEffect(current?.uri, refresh, showBin) {
        items = withContext(Dispatchers.IO) { list(current, hideBin) }
    }

    fun runIo(block: suspend () -> String?) {
        if (busy) return
        busy = true
        scope.launch {
            val msg = try {
                withContext(Dispatchers.IO) { block() }
            } catch (e: Exception) {
                "Failed: ${e.message}"
            }
            busy = false
            refresh++
            if (msg != null) Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ToolButton("⬆ Up", enabled = !showBin && stack.isNotEmpty()) { stack.removeAt(stack.lastIndex) }
            ToolButton("➕ Folder", enabled = !showBin && current != null) { newFolder = true }
            ToolButton("📋 Paste", enabled = !showBin && clipboard != null && current != null) {
                val src = clipboard ?: return@ToolButton
                val dest = current ?: return@ToolButton
                runIo { copyFile(context, src, dest) }
            }
            ToolButton(if (showBin) "⬅ Back to files" else "♻ Recycle Bin") { showBin = !showBin }
            ToolButton("🔄") { refresh++ }
            ToolButton("📂 Change folder") { picker.launch(null) }
        }
        val path = if (showBin) BIN_NAME else (listOf(rootDoc?.name ?: "Folder") + stack.map { it.name ?: "?" }).joinToString(" › ")
        Text(path, fontSize = 12.sp, color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(horizontal = 8.dp))
        HorizontalDivider()
        if (items.isEmpty()) {
            Text(if (showBin) "Recycle Bin is empty" else "This folder is empty", Modifier.padding(16.dp))
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(items, key = { it.doc.uri.toString() }) { item ->
                var menu by remember { mutableStateOf(false) }
                Box {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .combinedClickable(
                                onClick = {
                                    when {
                                        showBin -> menu = true
                                        item.isDir -> stack.add(item.doc)
                                        else -> openFile(context, item.doc)
                                    }
                                },
                                onLongClick = { menu = true },
                            )
                            .onRightClick { menu = true }
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(if (item.isDir) "📁" else "📄", fontSize = 18.sp)
                        Spacer(Modifier.width(10.dp))
                        Text(item.name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (!item.isDir) Text(sizeText(item.size), fontSize = 11.sp, color = MaterialTheme.colorScheme.outline)
                        TextButton(onClick = { menu = true }) { Text("⋮") }
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        if (showBin) {
                            DropdownMenuItem(text = { Text("↩ Restore") }, onClick = {
                                menu = false
                                runIo { restore(context, rootDoc, item.doc) }
                            })
                        } else {
                            DropdownMenuItem(text = { Text("Open") }, onClick = {
                                menu = false
                                if (item.isDir) stack.add(item.doc) else openFile(context, item.doc)
                            })
                            if (!item.isDir) {
                                DropdownMenuItem(text = { Text("Copy") }, onClick = {
                                    menu = false
                                    clipboard = item.doc
                                    Toast.makeText(context, "Copied. Open a folder and press Paste.", Toast.LENGTH_SHORT).show()
                                })
                            }
                            DropdownMenuItem(text = { Text("Rename") }, onClick = {
                                menu = false
                                renaming = item
                            })
                            DropdownMenuItem(text = { Text("Move to Recycle Bin") }, onClick = {
                                menu = false
                                val parent = current ?: return@DropdownMenuItem
                                runIo { recycle(context, rootDoc, parent, item.doc) }
                            })
                        }
                    }
                }
            }
        }
    }

    renaming?.let { item ->
        NameDialog(
            title = "Rename",
            initial = item.name,
            onDismiss = { renaming = null },
        ) { newName ->
            renaming = null
            runIo {
                val siblings = items.filter { it.doc.uri != item.doc.uri }.map { it.name }
                val name = SafeNames.unique(SafeNames.clean(newName), siblings)
                if (item.doc.renameTo(name)) "Renamed to $name" else "Could not rename"
            }
        }
    }
    if (newFolder) {
        NameDialog(title = "New folder", initial = "New folder", onDismiss = { newFolder = false }) { newName ->
            newFolder = false
            val dest = current
            if (dest != null) {
                runIo {
                    val name = SafeNames.unique(SafeNames.clean(newName), items.map { it.name })
                    if (dest.createDirectory(name) != null) null else "Could not create folder"
                }
            }
        }
    }
}

@Composable
private fun NameDialog(title: String, initial: String, onDismiss: () -> Unit, onOk: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true) },
        confirmButton = { TextButton(onClick = { onOk(text) }) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun list(dir: DocumentFile?, hideBin: Boolean): List<FileItem> {
    if (dir == null) return emptyList()
    return dir.listFiles()
        .filter { !(hideBin && it.isDirectory && it.name == BIN_NAME) }
        .map { FileItem(it, it.name ?: "?", it.isDirectory, it.length()) }
        .sortedWith(compareBy<FileItem>({ !it.isDir }, { it.name.lowercase() }))
}

private fun sizeText(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    bytes < 1024L * 1024 * 1024 -> "%.1f MB".format(bytes / 1048576.0)
    else -> "%.2f GB".format(bytes / 1073741824.0)
}

private fun openFile(context: Context, doc: DocumentFile) {
    val intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(doc.uri, doc.type ?: "*/*")
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(Intent.createChooser(intent, doc.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: Exception) {
        Toast.makeText(context, "No app can open this file", Toast.LENGTH_SHORT).show()
    }
}

/** Copies a file into [dest] under a new, unused name. Never overwrites. */
private fun copyFile(context: Context, src: DocumentFile, dest: DocumentFile): String {
    val existing = dest.listFiles().mapNotNull { it.name }
    val name = SafeNames.unique(src.name ?: "file", existing)
    val out = dest.createFile("application/octet-stream", name) ?: return "Could not create file"
    val resolver = context.contentResolver
    resolver.openInputStream(src.uri).use { input ->
        resolver.openOutputStream(out.uri).use { output ->
            if (input == null || output == null) return "Could not copy"
            input.copyTo(output)
        }
    }
    return "Pasted as $name"
}

/** "Delete" = move into the Recycle Bin folder. Nothing is ever removed for good. */
private fun recycle(context: Context, rootDoc: DocumentFile?, parent: DocumentFile, doc: DocumentFile): String {
    if (rootDoc == null) return "No folder"
    if (doc.name == BIN_NAME) return "This is the Recycle Bin"
    val bin = rootDoc.findFile(BIN_NAME) ?: rootDoc.createDirectory(BIN_NAME) ?: return "Could not create Recycle Bin"
    val moved = try {
        DocumentsContract.moveDocument(context.contentResolver, doc.uri, parent.uri, bin.uri)
    } catch (e: Exception) {
        null
    }
    if (moved == null) return "This folder does not support safe delete – nothing was deleted"
    val sp = Prefs(context).sp
    val map = sp.getStringSet(KEY_BIN_MAP, emptySet())!!.toMutableSet()
    map += "$moved|${parent.uri}"
    sp.edit().putStringSet(KEY_BIN_MAP, map).apply()
    return "Moved to Recycle Bin (you can restore it)"
}

private fun restore(context: Context, rootDoc: DocumentFile?, doc: DocumentFile): String {
    if (rootDoc == null) return "No folder"
    val bin = rootDoc.findFile(BIN_NAME) ?: return "Recycle Bin not found"
    val sp = Prefs(context).sp
    val map = sp.getStringSet(KEY_BIN_MAP, emptySet())!!.toMutableSet()
    val entry = map.firstOrNull { it.substringBefore('|') == doc.uri.toString() }
    val target = entry?.substringAfter('|')?.let(Uri::parse) ?: rootDoc.uri
    val moved = try {
        DocumentsContract.moveDocument(context.contentResolver, doc.uri, bin.uri, target)
    } catch (e: Exception) {
        null
    } ?: try {
        DocumentsContract.moveDocument(context.contentResolver, doc.uri, bin.uri, rootDoc.uri)
    } catch (e: Exception) {
        null
    }
    if (moved == null) return "Could not restore (a file with that name may already exist)"
    if (entry != null) {
        map.remove(entry)
        sp.edit().putStringSet(KEY_BIN_MAP, map).apply()
    }
    return "Restored"
}
