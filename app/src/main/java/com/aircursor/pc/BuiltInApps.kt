package com.aircursor.pc

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Paint
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aircursor.core.Calc
import com.aircursor.core.SafeNames
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun BuiltInApp(app: BuiltIn) {
    when (app) {
        BuiltIn.FILES -> FilesApp()
        BuiltIn.NOTEPAD -> NotepadApp()
        BuiltIn.CALCULATOR -> CalculatorApp()
        BuiltIn.PAINT -> PaintApp()
    }
}

@Composable
fun ToolButton(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    Text(
        text,
        fontSize = 13.sp,
        color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline,
        modifier = Modifier
            .padding(2.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    )
}

// ---------------------------------------------------------------- Notepad

/** Notes live only in AirCursor's private storage, so no phone file is ever touched. */
@Composable
private fun NotepadApp() {
    val context = LocalContext.current
    val dir = remember { File(context.filesDir, "notes").apply { mkdirs() } }
    val notes = remember { mutableStateListOf<String>() }
    var current by remember { mutableStateOf<String?>(null) }
    var text by remember { mutableStateOf("") }
    var saved by remember { mutableStateOf(true) }

    fun refresh() {
        notes.clear()
        notes.addAll(dir.listFiles()?.filter { it.isFile }?.sortedByDescending { it.lastModified() }?.map { it.name }.orEmpty())
    }

    fun newNote() {
        val name = SafeNames.unique("Note ${SimpleDateFormat("dd-MM HH.mm", Locale.US).format(Date())}.txt", notes)
        File(dir, name).writeText("")
        refresh()
        current = name
        text = ""
        saved = true
    }

    LaunchedEffect(Unit) {
        refresh()
        if (notes.isEmpty()) newNote() else {
            current = notes.first()
            text = File(dir, notes.first()).readText()
        }
    }
    // Auto-save shortly after typing stops.
    LaunchedEffect(text, current) {
        val name = current ?: return@LaunchedEffect
        if (saved) return@LaunchedEffect
        delay(700)
        withContext(Dispatchers.IO) { File(dir, name).writeText(text) }
        saved = true
    }

    Row(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .width(150.dp)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            ToolButton("➕ New note") { newNote() }
            LazyColumn {
                items(notes, key = { it }) { n ->
                    Text(
                        n.removeSuffix(".txt"),
                        fontSize = 12.sp,
                        fontWeight = if (n == current) FontWeight.Bold else FontWeight.Normal,
                        maxLines = 1,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                current?.let { File(dir, it).writeText(text) }
                                current = n
                                text = File(dir, n).readText()
                                saved = true
                            }
                            .padding(8.dp),
                    )
                }
            }
        }
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(current?.removeSuffix(".txt") ?: "", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Text(if (saved) "Saved" else "Saving…", fontSize = 11.sp, color = MaterialTheme.colorScheme.outline)
                ToolButton("Share") {
                    context.startActivity(
                        Intent.createChooser(
                            Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text),
                            "Share note",
                        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
            }
            BasicTextField(
                value = text,
                onValueChange = {
                    text = it
                    saved = false
                },
                textStyle = TextStyle(fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurface),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(10.dp),
            )
        }
    }
}

// ---------------------------------------------------------------- Calculator

@Composable
private fun CalculatorApp() {
    var expr by remember { mutableStateOf("") }
    var result by remember { mutableStateOf("") }
    val rows = listOf(
        listOf("C", "(", ")", "÷"),
        listOf("7", "8", "9", "×"),
        listOf("4", "5", "6", "-"),
        listOf("1", "2", "3", "+"),
        listOf("0", ".", "%", "⌫"),
    )
    fun press(k: String) {
        when (k) {
            "C" -> { expr = ""; result = "" }
            "⌫" -> expr = expr.dropLast(1)
            "=" -> Calc.evaluate(expr)?.let { expr = it; result = "" }
            else -> expr += k
        }
        if (k != "=") result = Calc.evaluate(expr) ?: ""
    }
    Column(Modifier.fillMaxSize().padding(8.dp)) {
        Text(
            expr.ifEmpty { "0" },
            fontSize = 26.sp,
            fontFamily = FontFamily.Monospace,
            textAlign = TextAlign.End,
            maxLines = 2,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            if (result.isNotEmpty()) "= $result" else " ",
            fontSize = 16.sp,
            color = MaterialTheme.colorScheme.outline,
            textAlign = TextAlign.End,
            modifier = Modifier.fillMaxWidth(),
        )
        rows.forEach { r ->
            Row(Modifier.weight(1f).fillMaxWidth()) {
                r.forEach { k -> CalcKey(k, Modifier.weight(1f)) { press(k) } }
            }
        }
        Row(Modifier.weight(1f).fillMaxWidth()) { CalcKey("=", Modifier.weight(1f), accent = true) { press("=") } }
    }
}

@Composable
private fun CalcKey(k: String, modifier: Modifier, accent: Boolean = false, onClick: () -> Unit) {
    Box(
        modifier
            .fillMaxHeight()
            .padding(3.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (accent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            k,
            fontSize = 18.sp,
            color = if (accent) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
        )
    }
}

// ---------------------------------------------------------------- Paint

private class Stroke2(val points: List<Offset>, val color: Color, val width: Float)

@Composable
private fun PaintApp() {
    val context = LocalContext.current
    val strokes = remember { mutableStateListOf<Stroke2>() }
    var current by remember { mutableStateOf<List<Offset>>(emptyList()) }
    var color by remember { mutableStateOf(Color.Black) }
    var width by remember { mutableFloatStateOf(8f) }
    var canvasW by remember { mutableStateOf(1) }
    var canvasH by remember { mutableStateOf(1) }
    val colors = listOf(Color.Black, Color.Red, Color(0xFF1E88E5), Color(0xFF43A047), Color(0xFFFDD835), Color.White)

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            colors.forEach { c ->
                Box(
                    Modifier
                        .padding(3.dp)
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(c)
                        .border(if (c == color) 3.dp else 1.dp, Color.Gray, CircleShape)
                        .clickable { color = c },
                )
            }
            Spacer(Modifier.width(8.dp))
            listOf(4f to "S", 8f to "M", 18f to "L").forEach { (w, label) ->
                ToolButton(if (w == width) "[$label]" else label) { width = w }
            }
            Spacer(Modifier.weight(1f))
            ToolButton("↶ Undo", enabled = strokes.isNotEmpty()) { strokes.removeAt(strokes.lastIndex) }
            ToolButton("🗋 New", enabled = strokes.isNotEmpty()) { strokes.clear() }
            ToolButton("💾 Save") { savePainting(context, strokes.toList(), canvasW, canvasH) }
        }
        Canvas(
            Modifier
                .fillMaxSize()
                .background(Color.White)
                .onSizeChanged {
                    canvasW = it.width.coerceAtLeast(1)
                    canvasH = it.height.coerceAtLeast(1)
                }
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { current = listOf(it) },
                        onDragEnd = {
                            if (current.size > 1) strokes += Stroke2(current, color, width)
                            current = emptyList()
                        },
                        onDragCancel = { current = emptyList() },
                    ) { change, _ ->
                        change.consume()
                        current = current + change.position
                    }
                },
        ) {
            fun draw(points: List<Offset>, c: Color, w: Float) {
                if (points.size < 2) return
                val p = Path().apply {
                    moveTo(points[0].x, points[0].y)
                    for (i in 1 until points.size) lineTo(points[i].x, points[i].y)
                }
                drawPath(p, c, style = Stroke(width = w, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
            strokes.forEach { draw(it.points, it.color, it.width) }
            draw(current, color, width)
        }
    }
}

/** Saves as a NEW picture (never overwrites anything) in Pictures/AirCursor. */
private fun savePainting(context: Context, strokes: List<Stroke2>, w: Int, h: Int) {
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bmp)
    canvas.drawColor(android.graphics.Color.WHITE)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    for (s in strokes) {
        if (s.points.size < 2) continue
        paint.color = s.color.toArgb()
        paint.strokeWidth = s.width
        val path = android.graphics.Path()
        path.moveTo(s.points[0].x, s.points[0].y)
        for (i in 1 until s.points.size) path.lineTo(s.points[i].x, s.points[i].y)
        canvas.drawPath(path, paint)
    }
    val name = "Drawing ${SimpleDateFormat("yyyy-MM-dd HH.mm.ss", Locale.US).format(Date())}.png"
    try {
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/AirCursor")
            }
            val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("no uri")
            context.contentResolver.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            Toast.makeText(context, "Saved to Pictures/AirCursor", Toast.LENGTH_SHORT).show()
        } else {
            val dir = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: context.filesDir
            val file = File(dir, SafeNames.unique(name, dir.list()?.toList().orEmpty()))
            file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            Toast.makeText(context, "Saved: ${file.absolutePath}", Toast.LENGTH_LONG).show()
        }
    } catch (e: Exception) {
        Toast.makeText(context, "Could not save: ${e.message}", Toast.LENGTH_LONG).show()
    }
}
