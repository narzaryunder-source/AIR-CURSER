@file:OptIn(ExperimentalFoundationApi::class)

package com.aircursor.pc

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.aircursor.MainActivity
import com.aircursor.service.CursorAccessibilityService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date
import kotlin.math.roundToInt

private val WALLPAPERS = listOf(
    listOf(Color(0xFF0F2027), Color(0xFF203A43), Color(0xFF2C5364)),
    listOf(Color(0xFF1A2A6C), Color(0xFF4A3F8F), Color(0xFF7B4397)),
    listOf(Color(0xFF134E5E), Color(0xFF2E7D5B), Color(0xFF71B280)),
    listOf(Color(0xFF232526), Color(0xFF414345), Color(0xFF5A5C5E)),
    listOf(Color(0xFF3A1C71), Color(0xFFD76D77), Color(0xFFFFAF7B)),
)

private val TASKBAR_HEIGHT = 46.dp

/** Right mouse button → [onRight] with the position inside the element. */
fun Modifier.onRightClick(onRight: (Offset) -> Unit): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            val e = awaitPointerEvent(PointerEventPass.Initial)
            if (e.type == PointerEventType.Press && e.buttons.isSecondaryPressed) {
                e.changes.forEach { it.consume() }
                onRight(e.changes.first().position)
            }
        }
    }
}

private fun globalAction(action: Int) {
    CursorAccessibilityService.instance?.performGlobalAction(action)
}

@Composable
fun PcDesktop(state: DesktopState, onExit: () -> Unit) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val apps = remember { mutableStateListOf<AppEntry>() }
    val pinned = remember { mutableStateListOf<String>().apply { addAll(PcMode.pinned(context)) } }
    var wallpaper by remember { mutableIntStateOf(PcMode.wallpaper(context)) }

    LaunchedEffect(Unit) {
        val list = withContext(Dispatchers.IO) { PcMode.loadApps(context) }
        apps.clear()
        apps.addAll(list)
    }

    fun togglePin(app: AppEntry) {
        if (app.key in pinned) pinned.remove(app.key) else pinned.add(app.key)
        PcMode.setPinned(context, pinned.toList())
    }

    fun appMenu(app: AppEntry, at: Offset) {
        state.menu = MenuState(
            at.x, at.y,
            listOf(
                "Open" to { PcMode.launch(context, app) },
                "Open full screen" to { PcMode.launch(context, app, window = false) },
                (if (app.key in pinned) "Unpin from taskbar" else "Pin to taskbar & desktop") to { togglePin(app) },
                "App info" to { PcMode.appInfo(context, app) },
            ),
        )
    }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(Brush.linearGradient(WALLPAPERS[wallpaper % WALLPAPERS.size])),
    ) {
        val areaW = maxWidth.value
        val areaH = (maxHeight - TASKBAR_HEIGHT).value

        fun desktopMenu(at: Offset) {
            state.menu = MenuState(
                at.x, at.y,
                listOf(
                    "📁 Open Files" to { state.open(BuiltIn.FILES, areaW, areaH) },
                    "📝 New note" to { state.open(BuiltIn.NOTEPAD, areaW, areaH) },
                    "🖼 Change wallpaper" to {
                        wallpaper = (wallpaper + 1) % WALLPAPERS.size
                        PcMode.setWallpaper(context, wallpaper)
                    },
                    "⚙ AirCursor settings" to { openSettings(context) },
                ),
            )
        }

        // Desktop background: tap closes menus, long press / right click opens the desktop menu.
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = {
                            state.startOpen = false
                            state.menu = null
                        },
                        onLongPress = { desktopMenu(it) },
                    )
                }
                .onRightClick { desktopMenu(it) },
        )

        // Desktop icons
        Column(
            Modifier
                .padding(8.dp)
                .fillMaxHeight()
                .padding(bottom = TASKBAR_HEIGHT)
                .verticalScroll(rememberScrollState()),
        ) {
            BuiltIn.entries.forEach { b ->
                DesktopIcon(label = b.title, emoji = b.emoji, onOpen = { state.open(b, areaW, areaH) }, onMenu = null)
            }
            apps.filter { it.key in pinned }.forEach { app ->
                var origin by remember { mutableStateOf(Offset.Zero) }
                Box(Modifier.onGloballyPositioned { origin = it.positionInRoot() }) {
                    DesktopIcon(
                        label = app.label,
                        app = app,
                        onOpen = { PcMode.launch(context, app) },
                        onMenu = { appMenu(app, origin + it) },
                    )
                }
            }
        }

        // Windows
        state.windows.filter { !it.minimized }.forEach { w ->
            key(w.id) {
                WindowFrame(
                    w = w,
                    areaW = areaW,
                    areaH = areaH,
                    focused = w.id == state.focusedId,
                    onFocus = { if (state.focusedId != w.id) state.focus(w) },
                    onMinimize = { state.minimize(w) },
                    onClose = { state.close(w) },
                ) {
                    BuiltInApp(w.app)
                }
            }
        }

        if (state.startOpen) {
            StartMenu(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 6.dp, bottom = TASKBAR_HEIGHT + 6.dp)
                    .zIndex(10_000f),
                maxHeightDp = areaH - 12f,
                apps = apps,
                onBuiltIn = { state.open(it, areaW, areaH) },
                onLaunch = {
                    state.startOpen = false
                    PcMode.launch(context, it)
                },
                onAppMenu = { app, at -> appMenu(app, at) },
                onExit = onExit,
            )
        }

        state.menu?.let { m ->
            ContextMenu(
                m = m,
                maxX = with(density) { maxWidth.toPx() },
                maxY = with(density) { (maxHeight - TASKBAR_HEIGHT).toPx() },
                onDone = { state.menu = null },
            )
        }

        Taskbar(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .zIndex(20_000f),
            state = state,
            pinnedApps = apps.filter { it.key in pinned },
            onStart = {
                state.menu = null
                state.startOpen = !state.startOpen
            },
            onApp = { PcMode.launch(context, it) },
            onAppMenu = { app, at -> appMenu(app, at) },
        )
    }
}

private fun openSettings(c: Context) {
    c.startActivity(Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

@Composable
private fun AppIconImage(app: AppEntry, sizeDp: Int) {
    val context = LocalContext.current
    val px = with(LocalDensity.current) { sizeDp.dp.roundToPx() }
    var bmp by remember(app.key) { mutableStateOf(PcMode.icon(context, app, px)?.asImageBitmap()) }
    LaunchedEffect(app.key) {
        if (bmp == null) bmp = withContext(Dispatchers.IO) { PcMode.icon(context, app, px)?.asImageBitmap() }
    }
    val b = bmp
    if (b != null) {
        Image(b, contentDescription = app.label, modifier = Modifier.size(sizeDp.dp))
    } else {
        Box(Modifier.size(sizeDp.dp).clip(RoundedCornerShape(8.dp)).background(Color(0x55FFFFFF)))
    }
}

@Composable
private fun DesktopIcon(
    label: String,
    emoji: String? = null,
    app: AppEntry? = null,
    onOpen: () -> Unit,
    onMenu: ((Offset) -> Unit)?,
) {
    Column(
        Modifier
            .width(84.dp)
            .clip(RoundedCornerShape(8.dp))
            .combinedClickable(onClick = onOpen, onLongClick = { onMenu?.invoke(Offset(40f, 40f)) })
            .onRightClick { onMenu?.invoke(it) }
            .padding(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (app != null) AppIconImage(app, 40) else Text(emoji ?: "•", fontSize = 30.sp)
        Text(
            label,
            color = Color.White,
            fontSize = 11.sp,
            maxLines = 2,
            textAlign = TextAlign.Center,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun WindowFrame(
    w: WinState,
    areaW: Float,
    areaH: Float,
    focused: Boolean,
    onFocus: () -> Unit,
    onMinimize: () -> Unit,
    onClose: () -> Unit,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current.density
    val shape = RoundedCornerShape(if (w.maximized) 0.dp else 8.dp)
    val frame = if (w.maximized) {
        Modifier
            .fillMaxWidth()
            .height(areaH.dp)
    } else {
        Modifier
            .offset { IntOffset((w.x * density).roundToInt(), (w.y * density).roundToInt()) }
            .size(w.w.dp, w.h.dp)
    }
    Column(
        frame
            .zIndex(w.z.toFloat())
            .shadow(if (focused) 16.dp else 6.dp, shape)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, if (focused) MaterialTheme.colorScheme.primary else Color(0x33000000), shape)
            .pointerInput(w.id) {
                awaitPointerEventScope {
                    while (true) {
                        val e = awaitPointerEvent(PointerEventPass.Initial)
                        if (e.type == PointerEventType.Press) onFocus()
                    }
                }
            },
    ) {
        // Title bar: drag to move, double tap to maximize
        Row(
            Modifier
                .fillMaxWidth()
                .height(34.dp)
                .background(
                    if (focused) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceVariant,
                )
                .pointerInput(w.id) {
                    detectDragGestures { change, drag ->
                        change.consume()
                        if (!w.maximized) {
                            w.x = (w.x + drag.x / density).coerceIn(-(w.w - 80f), areaW - 80f)
                            w.y = (w.y + drag.y / density).coerceIn(0f, (areaH - 34f).coerceAtLeast(0f))
                        }
                    }
                }
                .pointerInput(w.id) {
                    detectTapGestures(onDoubleTap = { w.maximized = !w.maximized })
                },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("  ${w.app.emoji}  ${w.app.title}", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            Spacer(Modifier.weight(1f))
            TitleButton("—", onClick = onMinimize)
            TitleButton(if (w.maximized) "❐" else "☐") { w.maximized = !w.maximized }
            TitleButton("✕", danger = true, onClick = onClose)
        }
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            content()
            if (!w.maximized) {
                // Resize handle (bottom-right corner)
                Box(
                    Modifier
                        .align(Alignment.BottomEnd)
                        .size(22.dp)
                        .pointerInput(w.id) {
                            detectDragGestures { change, drag ->
                                change.consume()
                                w.w = (w.w + drag.x / density).coerceIn(240f, areaW)
                                w.h = (w.h + drag.y / density).coerceIn(160f, areaH)
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) { Text("◢", fontSize = 12.sp, color = Color(0x88000000)) }
            }
        }
    }
}

@Composable
private fun TitleButton(text: String, danger: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier
            .width(42.dp)
            .fillMaxHeight()
            .clickable(onClick = onClick)
            .background(if (danger) Color(0x22FF0000) else Color.Transparent),
        contentAlignment = Alignment.Center,
    ) { Text(text, fontSize = 14.sp) }
}

@Composable
private fun StartMenu(
    modifier: Modifier,
    maxHeightDp: Float,
    apps: List<AppEntry>,
    onBuiltIn: (BuiltIn) -> Unit,
    onLaunch: (AppEntry) -> Unit,
    onAppMenu: (AppEntry, Offset) -> Unit,
    onExit: () -> Unit,
) {
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    var freeform by remember { mutableStateOf(PcMode.freeform(context)) }
    var keyCursor by remember { mutableStateOf(PcMode.keyboardCursor(context)) }
    val filtered = if (query.isBlank()) apps else apps.filter { it.label.contains(query, ignoreCase = true) }
    Surface(
        modifier = modifier
            .width(360.dp)
            .height(maxHeightDp.coerceAtMost(520f).dp),
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 6.dp,
        shadowElevation = 12.dp,
    ) {
        Column(Modifier.padding(10.dp)) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Search apps") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                BuiltIn.entries.forEach { b ->
                    Column(
                        Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onBuiltIn(b) }
                            .padding(6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(b.emoji, fontSize = 22.sp)
                        Text(b.title, fontSize = 10.sp)
                    }
                }
            }
            HorizontalDivider()
            LazyColumn(Modifier.weight(1f)) {
                items(filtered, key = { it.key }) { app ->
                    var origin by remember { mutableStateOf(Offset.Zero) }
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .onGloballyPositioned { origin = it.positionInRoot() }
                            .combinedClickable(
                                onClick = { onLaunch(app) },
                                onLongClick = { onAppMenu(app, origin + Offset(120f, 20f)) },
                            )
                            .onRightClick { onAppMenu(app, origin + it) }
                            .padding(vertical = 6.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AppIconImage(app, 28)
                        Spacer(Modifier.width(10.dp))
                        Text(app.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            HorizontalDivider()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Open apps as windows", Modifier.weight(1f), fontSize = 12.sp)
                Switch(checked = freeform, onCheckedChange = {
                    freeform = it
                    PcMode.setFreeform(context, it)
                })
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Keyboard cursor (Alt + arrows)", Modifier.weight(1f), fontSize = 12.sp)
                Switch(checked = keyCursor, onCheckedChange = {
                    keyCursor = it
                    PcMode.setKeyboardCursor(context, it)
                })
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                StartAction("⚙ Settings") { openSettings(context) }
                StartAction("🔒 Lock") { globalAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN) }
                StartAction("📱 Exit PC mode", onExit)
            }
        }
    }
}

@Composable
private fun StartAction(text: String, onClick: () -> Unit) {
    Text(
        text,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
    )
}

@Composable
private fun ContextMenu(m: MenuState, maxX: Float, maxY: Float, onDone: () -> Unit) {
    val density = LocalDensity.current
    val menuW = with(density) { 220.dp.toPx() }
    val menuH = with(density) { (m.items.size * 42 + 8).dp.toPx() }
    val x = m.x.coerceIn(0f, (maxX - menuW).coerceAtLeast(0f))
    val y = m.y.coerceIn(0f, (maxY - menuH).coerceAtLeast(0f))
    Surface(
        modifier = Modifier
            .offset { IntOffset(x.roundToInt(), y.roundToInt()) }
            .width(220.dp)
            .zIndex(30_000f),
        shape = RoundedCornerShape(8.dp),
        shadowElevation = 10.dp,
        tonalElevation = 4.dp,
    ) {
        Column(Modifier.padding(vertical = 4.dp)) {
            m.items.forEach { (label, action) ->
                Text(
                    label,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(42.dp)
                        .clickable {
                            onDone()
                            action()
                        }
                        .padding(horizontal = 14.dp, vertical = 11.dp),
                    fontSize = 14.sp,
                )
            }
        }
    }
}

@Composable
private fun Taskbar(
    modifier: Modifier,
    state: DesktopState,
    pinnedApps: List<AppEntry>,
    onStart: () -> Unit,
    onApp: (AppEntry) -> Unit,
    onAppMenu: (AppEntry, Offset) -> Unit,
) {
    val context = LocalContext.current
    var clock by remember { mutableStateOf("") }
    var battery by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        while (true) {
            clock = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date())
            battery = batteryText(context)
            delay(20_000)
        }
    }
    Row(
        modifier
            .fillMaxWidth()
            .height(TASKBAR_HEIGHT)
            .background(Color(0xE6101418))
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TaskbarButton(if (state.startOpen) "▼ Start" else "▲ Start", highlighted = state.startOpen, onClick = onStart)
        Spacer(Modifier.width(6.dp))
        state.windows.forEach { w ->
            TaskbarButton(
                "${w.app.emoji} ${w.app.title}",
                highlighted = w.id == state.focusedId && !w.minimized,
            ) {
                if (w.id == state.focusedId && !w.minimized) state.minimize(w) else state.focus(w)
            }
        }
        pinnedApps.forEach { app ->
            var origin by remember { mutableStateOf(Offset.Zero) }
            Box(
                Modifier
                    .padding(horizontal = 3.dp)
                    .onGloballyPositioned { origin = it.positionInRoot() }
                    .clip(RoundedCornerShape(6.dp))
                    .combinedClickable(onClick = { onApp(app) }, onLongClick = { onAppMenu(app, origin + Offset(0f, -200f)) })
                    .onRightClick { onAppMenu(app, origin + Offset(it.x, -200f)) }
                    .padding(4.dp),
            ) { AppIconImage(app, 30) }
        }
        Spacer(Modifier.weight(1f))
        TaskbarButton("⧉") { globalAction(AccessibilityService.GLOBAL_ACTION_RECENTS) }
        TaskbarButton("⊟") { globalAction(AccessibilityService.GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN) }
        TaskbarButton("🔔") { globalAction(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS) }
        Text("  $battery  $clock  ", color = Color.White, fontSize = 12.sp)
    }
}

@Composable
private fun TaskbarButton(text: String, highlighted: Boolean = false, onClick: () -> Unit) {
    Text(
        text,
        color = Color.White,
        fontSize = 13.sp,
        maxLines = 1,
        modifier = Modifier
            .padding(horizontal = 2.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(if (highlighted) Color(0x55FFFFFF) else Color(0x18FFFFFF))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
    )
}

private fun batteryText(c: Context): String {
    val i = c.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return ""
    val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
    val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
    val charging = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1) == BatteryManager.BATTERY_STATUS_CHARGING
    if (level < 0) return ""
    return (if (charging) "⚡" else "🔋") + "${level * 100 / scale.coerceAtLeast(1)}%"
}
