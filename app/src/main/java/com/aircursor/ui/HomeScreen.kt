package com.aircursor.ui

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.rememberDrawerState
import com.aircursor.BuildConfig
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aircursor.R
import com.aircursor.auth.AppUser
import com.aircursor.auth.AuthManager
import com.aircursor.cloudpc.CloudPcBus
import com.aircursor.cloudpc.CloudPcDesktop
import com.aircursor.cloudpc.CloudPcDesktopActivity
import com.aircursor.cloudpc.CloudPcEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun HomeScreen(
    onOpenPc: () -> Unit,
    onOpenHelp: () -> Unit,
) {
    val context = LocalContext.current
    val user by AuthManager.user.collectAsStateWithLifecycle()
    var showAccount by remember { mutableStateOf(false) }
    var showAbout by remember { mutableStateOf(false) }
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    BackHandler(enabled = drawer.isOpen) { scope.launch { drawer.close() } }

    // Live plan settings (for later subscriptions) – quietly, in the background.

    fun fromDrawer(action: () -> Unit) {
        scope.launch { drawer.close() }
        action()
    }
    val openDesktop = { openPcDesktop(context, onOpenPc) }

    ModalNavigationDrawer(
        drawerState = drawer,
        drawerContent = {
            AppDrawer(
                onHome = { scope.launch { drawer.close() } },
                onDesktop = { fromDrawer(openDesktop) },
                onPc = { fromDrawer(onOpenPc) },
                onHelp = { fromDrawer(onOpenHelp) },
                onAccount = { fromDrawer { showAccount = true } },
                onAbout = { fromDrawer { showAbout = true } },
            )
        },
    ) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize()) {
                // Top bar
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 6.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = { scope.launch { drawer.open() } }) { UiIcon(R.drawable.ic_ui_menu) }
                    Text(
                        "AirCursor",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    Box(
                        Modifier
                            .padding(end = 8.dp)
                            .clip(CircleShape)
                            .clickable { showAccount = true },
                    ) { Avatar(user?.name, size = 36.dp) }
                }
                Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp)
                        .padding(top = 4.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    // Greeting
                    Column(Modifier.padding(horizontal = 4.dp, vertical = 6.dp)) {
                        Text(
                            user?.name?.substringBefore(' ')?.takeIf { it.isNotBlank() }?.let { "Hi, $it" } ?: "Welcome",
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            "Your pocket PC – Ubuntu desktop, apps and games on your phone.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    PcCard(onOpenPc)

                    InfoCards(onOpenHelp)

                    UpdateCard()

                    SectionLabel("Settings")
                    SectionCard {
                        NavRow(R.drawable.ic_ui_desktop, "AirCursor PC", "Desktop, AirBoost, graphics, apps and games", tint = Brand.Sky, onClick = onOpenPc)
                        NavRow(
                            R.drawable.ic_ui_phone, "Use as keyboard & touchpad",
                            "Control AirCursor PC on your other phone from this one", tint = Brand.Violet,
                        ) { context.startActivity(android.content.Intent(context, com.aircursor.cloudpc.RemotePadActivity::class.java)) }
                        NavRow(R.drawable.ic_ui_book, "Help & guide", "Gestures, the PC, apps and AI step by step", tint = Brand.Warning, onClick = onOpenHelp)
                    }

                    Column(Modifier.padding(horizontal = 4.dp)) {
                        Text(
                            "AirCursor PC and its files stay inside this app on your phone.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            "AirCursor ${BuildConfig.VERSION_NAME}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        )
                    }
                }
            }
        }
    }

    if (showAccount) AccountDialog(user, onDismiss = { showAccount = false })
    if (showAbout) AboutDialog(onDismiss = { showAbout = false })
}

private fun openPcDesktop(context: android.content.Context, onOpenPc: () -> Unit) {
    val ready = CloudPcEngine.isReady(context) && CloudPcDesktop.isInstalled(context)
    when {
        !ready -> onOpenPc()
        else -> context.startActivity(android.content.Intent(context, CloudPcDesktopActivity::class.java))
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp, top = 6.dp),
    )
}

// ---- Side menu ----------------------------------------------------------------------------------

@Composable
private fun AppDrawer(
    onHome: () -> Unit,
    onDesktop: () -> Unit,
    onPc: () -> Unit,
    onHelp: () -> Unit,
    onAccount: () -> Unit,
    onAbout: () -> Unit,
) {
    val context = LocalContext.current
    val theme by UiPrefs.theme.collectAsStateWithLifecycle()
    val dark = when (theme) {
        UiPrefs.ThemeMode.LIGHT -> false
        UiPrefs.ThemeMode.DARK -> true
        UiPrefs.ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }
    ModalDrawerSheet(
        modifier = Modifier.width(304.dp),
        drawerContainerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            // Header: logo, light/dark, name + version
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(Brand.handGradient)
                    .padding(horizontal = 20.dp, vertical = 18.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(52.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(Color.White),
                        contentAlignment = Alignment.Center,
                    ) { BrandLogo(size = 40.dp) }
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = {
                        UiPrefs.setTheme(context, if (dark) UiPrefs.ThemeMode.LIGHT else UiPrefs.ThemeMode.DARK)
                    }) {
                        UiIcon(if (dark) R.drawable.ic_ui_sun else R.drawable.ic_ui_moon, tint = Color.White)
                    }
                }
                Spacer(Modifier.height(14.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("AirCursor", color = Color.White, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text(
                            "Version ${BuildConfig.VERSION_NAME}",
                            color = Color.White.copy(alpha = 0.78f),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            DrawerEntry(R.drawable.ic_ui_home, "Home", selected = true, onClick = onHome)
            DrawerLabel("AirCursor PC")
            DrawerEntry(R.drawable.ic_ui_desktop, "Open desktop", onClick = onDesktop)
            DrawerEntry(R.drawable.ic_ui_bolt, "PC settings & AirBoost", onClick = onPc)
            DrawerEntry(R.drawable.ic_ui_phone, "Use as keyboard & touchpad") {
                context.startActivity(android.content.Intent(context, com.aircursor.cloudpc.RemotePadActivity::class.java))
            }
            DrawerDivider()
            DrawerLabel("Support")
            DrawerEntry(R.drawable.ic_ui_book, "Help & guide", onClick = onHelp)
            DrawerEntry(R.drawable.ic_ui_user, "Account", onClick = onAccount)
            DrawerEntry(R.drawable.ic_ui_info, "About AirCursor", onClick = onAbout)
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun DrawerLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 28.dp, top = 14.dp, bottom = 6.dp),
    )
}

@Composable
private fun DrawerDivider() {
    HorizontalDivider(
        color = MaterialTheme.colorScheme.outlineVariant,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
    )
}

@Composable
private fun DrawerEntry(
    @DrawableRes icon: Int,
    label: String,
    selected: Boolean = false,
    badge: String? = null,
    onClick: () -> Unit,
) {
    val badgeContent: (@Composable () -> Unit)? = badge?.let { b -> @Composable { StatusPill(b, Brand.Danger) } }
    NavigationDrawerItem(
        label = { Text(label, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium) },
        icon = { UiIcon(icon) },
        selected = selected,
        onClick = onClick,
        badge = badgeContent,
        shape = RoundedCornerShape(14.dp),
        colors = NavigationDrawerItemDefaults.colors(
            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
            selectedIconColor = MaterialTheme.colorScheme.primary,
            selectedTextColor = MaterialTheme.colorScheme.onPrimaryContainer,
            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
        modifier = Modifier.padding(horizontal = 12.dp),
    )
}

// ---- Info cards (the person can close them) -----------------------------------------------------

/** Changes when there is something new to tell; closing the card hides it until the next update. */
private const val WHATS_NEW_ID = "whatsnew_phone_keyboard"

@Composable
private fun InfoCards(onOpenHelp: () -> Unit) {
    val context = LocalContext.current
    var whatsNew by remember { mutableStateOf(!UiPrefs.closed(context, WHATS_NEW_ID)) }
    var help by remember { mutableStateOf(!UiPrefs.closed(context, "help")) }
    if (whatsNew) {
        InfoCard(R.drawable.ic_ui_sparkles, Brand.Violet, "What's new", onClose = {
            UiPrefs.close(context, WHATS_NEW_ID)
            whatsNew = false
        }) {
            Bullet("Use a second phone as keyboard & touchpad: on it open AirCursor → Use as keyboard & touchpad; on the PC: menu → Another phone as keyboard")
            Bullet("Hand control is removed – AirCursor is now only your pocket PC (no camera or accessibility permission needed)")
            Bullet("The app PIN lock is gone too; the PC keeps its own password (lock screen)")
            Bullet("AirCursor is now free for everyone – no plan, no key")
            Bullet("Games: the whole screen for the game (menu bar and dock step aside), a smaller picture for 3D games = more FPS, background helper rests")
            Bullet("The PC is lighter again: the background helper uses far less CPU")
            Bullet("Dock: one tap opens an app; the dock no longer flickers")
            Bullet("Touch is mouse style again: hold and move, the pointer follows your finger (change it in AirBoost → Touch)")
            Bullet("Fixed: apps in the dock and the all-apps screen now open (and stay open)")
            Bullet("Dock and menu bar in a lighter glass look")
            Bullet("TS Station 1.0: 51 hand-picked PC games, 22 web games that play at once, cloud gaming (GeForce NOW, Xbox) and your phone's own games – one library")
            Bullet("TS Station, the game console, now comes with AirCursor PC for everyone (in the dock)")
            Bullet("New lock screen (blurred wallpaper, big clock) and a start screen with logo and progress bar")
            Bullet("Back up your whole PC to one file and restore it on any phone: AirCursor PC → Back up the PC")
            Bullet("Desktop widgets: clock, weather, storage, battery – they hide when a window is open")
            Bullet("A short tour on the first start, in Hindi or English")
            Bullet("Google Drive sync in the PC works again")
            Bullet("Air has a new logo: a globe with a swirl of air – a real browser look")
            Bullet("27 new games in Air Store: Hedgewars, Wesnoth, OpenTTD, Warzone 2100, Solitaire, Chess, Sudoku… and web games like Krunker, Slither.io, skribbl.io")
            Bullet("AirCursor PC looks like a Mac: menu bar on top, dock with live dots, all-apps screen with search – drawn by the phone, so it stays smooth")
            Bullet("New window frames: round close / minimise / maximise buttons on the left (AirMac)")
            Bullet("Files → hold a video → Edit on the phone: opens in CapCut (or another editor) on your phone")
            Bullet("VS Code now runs like the guide (code-server) and opens in Air: smooth, signs in by itself")
            Bullet("Blender, Kdenlive, OpenShot, Krita: automatic speed mode while they run, plus a light Blender viewport preset")
            Bullet("Only Air Store now – the old App Store is gone. 50+ new apps: Meet, Zoom, Teams, OBS, OpenShot, chess, courses…")
            Bullet("Air pages a little smaller, so more fits on the screen")
            Bullet("New AirCursor logo: the blue air-arrow (AirCursor PC in violet)")
            Bullet("Air, like Brave: laptop-size pages and desktop YouTube, tabs with real logos, bookmarks, history")
            Bullet("New tab page with real photos of the Earth (NASA), clock and shortcuts with real app logos")
            Bullet("Shields: trackers and ads blocked (uBlock Origin), with a counter")
            Bullet("Downloads: save each file in the PC, on the phone, or both – e.g. a project made with Claude")
            Bullet("Voice assistant: tap the mic or Win+A – \"YouTube kholo\", \"search …\", \"type …\"; give it any name")
            Bullet("App windows: Air Store (small window), TS Station and web apps open like apps, no address bar")
            Bullet("Premium PC look: modern dark theme and icons, clean desktop, real-photo wallpaper, eagle logo")
            Bullet("Air Store: Apple-style store – Spotify, YouTube Music, Netflix, Photopea, AI apps and PC apps")
            Bullet("New PC look: modern dock and our own wallpaper (Windows taskbar still one tap away)")
            Bullet("Games: smoother – the PC drops to the Game resolution while a game runs")
            Bullet("Air – our own browser on the phone's engine: fast YouTube & AI apps, Google sign-in works")
            Bullet("Faster setup: the ready-made PC (370 MB) is unpacked – no long install, far fewer errors")
            Bullet("Touch like a phone: swipe scrolls with glide, hold = right click, hold + move = select")
            Bullet("Brave: much fewer programs – YouTube and AI sites load instead of hanging")
            Bullet("Send a problem report from the PC screen or the Close menu")
            Bullet("Smooth video: Win+Y (or ▶ in the toolbar) plays the browser video on the phone's video chip")
            Bullet("Share → AirCursor PC: send photos, videos and files from any app straight into the PC")
            Bullet("Focus boost: the app in front gets the fast cores; lighter VS Code")
            Bullet("Password for AirCursor PC, like Windows – set it at the first start or in toolbar → Lock")
            Bullet("Smoother YouTube: videos come as H.264, much lighter for the phone (Brave and Firefox)")
            Bullet("Game controllers work in AirCursor PC: connect one to the phone and play")
            Bullet("Sound in AirCursor PC – videos, music and games play on the phone")
            Bullet("GitHub and Google Drive apps; Files: Send to phone, Projects folder")
            Bullet("PC Assistant: Claude checks and fixes the PC, builds and opens apps")
            Bullet("24 more apps: Geany, Thonny, PDF Reader, Calculator, Paint, mpv, Remmina…")
            Bullet("Windows keys: Win+E, Win+R, snap windows, Task Manager, screenshots, zip")
            Bullet("Brave as default browser, Phone GPU for games, Turbo and Steady FPS")
        }
    }
    if (help) {
        InfoCard(R.drawable.ic_ui_help, Brand.Sky, "Questions?", onClick = onOpenHelp, onClose = {
            UiPrefs.close(context, "help")
            help = false
        }) {
            Text(
                "Open the guide: gestures, AirCursor PC, apps, games and AI – step by step.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun InfoCard(
    @DrawableRes icon: Int,
    tint: Color,
    title: String,
    onClose: () -> Unit,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(20.dp))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(start = 16.dp, top = 14.dp, bottom = 14.dp, end = 4.dp),
        verticalAlignment = Alignment.Top,
    ) {
        IconBadge(icon, tint, size = 44.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f).padding(top = 2.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            content()
        }
        IconButton(onClick = onClose, modifier = Modifier.size(36.dp)) {
            UiIcon(R.drawable.ic_ui_close, tint = MaterialTheme.colorScheme.onSurfaceVariant, size = 18.dp)
        }
    }
}

@Composable
private fun Bullet(text: String) {
    Row(verticalAlignment = Alignment.Top) {
        Box(
            Modifier
                .padding(top = 7.dp)
                .size(6.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(MaterialTheme.colorScheme.primary),
        )
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ---- Quick actions ------------------------------------------------------------------------------

// ---- About --------------------------------------------------------------------------------------

@Composable
private fun AboutDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { BrandLogo(size = 56.dp) },
        title = { Text("AirCursor") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Version ${BuildConfig.VERSION_NAME}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text("Control your phone with your hand – and carry a full PC in your pocket.")
                Text("Privacy", fontWeight = FontWeight.SemiBold)
                Text(
                    "Hand tracking runs only on this phone; camera images are never saved or sent. " +
                        "AirCursor PC and its files stay inside the app on this phone.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text("Open-source parts", fontWeight = FontWeight.SemiBold)
                Text(
                    "MediaPipe (Apache-2.0) · Ubuntu · PRoot (GPL-2.0) · talloc (LGPL-3.0) · " +
                        "virglrenderer and libepoxy (MIT) · XFCE, TigerVNC and Firefox inside the PC.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}


@Composable
private fun PcCard(onOpenPc: () -> Unit) {
    val context = LocalContext.current
    val state by CloudPcBus.state.collectAsStateWithLifecycle()
    var tick by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { tick++ }
    val installed = remember(tick, state.version, state.busy) {
        CloudPcEngine.isReady(context) && CloudPcDesktop.isInstalled(context)
    }
    val (pill, pillColor) = when {
        state.busy -> "Setting up" to Brand.Warning
        state.desktopRunning -> "Running" to Brand.Success
        installed -> "Ready" to Brand.Sky
        else -> "Not installed" to MaterialTheme.colorScheme.onSurfaceVariant
    }
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BrandLogo(R.drawable.logo_pc, size = 46.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text("AirCursor PC", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    "Full Ubuntu desktop on your phone",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            StatusPill(pill, pillColor)
        }
        Spacer(Modifier.height(14.dp))
        if (state.busy) {
            Text(state.title, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(6.dp))
            if (state.progress >= 0f) {
                LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth())
            } else {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            Spacer(Modifier.height(10.dp))
            SecondaryButton("View progress", onClick = onOpenPc)
        } else if (installed) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PrimaryButton(
                    "Open desktop",
                    onClick = { context.startActivity(android.content.Intent(context, CloudPcDesktopActivity::class.java)) },
                    icon = R.drawable.ic_ui_play,
                    modifier = Modifier.weight(1f),
                    container = Brand.Sky,
                )
                SecondaryButton("Manage", onClick = onOpenPc, modifier = Modifier.weight(0.6f))
            }
        } else {
            PrimaryButton("Set up AirCursor PC", onClick = onOpenPc, icon = R.drawable.ic_ui_download, container = Brand.Sky)
        }
    }
}

@Composable
private fun AccountDialog(user: AppUser?, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var confirmSignOut by remember { mutableStateOf(false) }
    if (confirmSignOut) {
        AlertDialog(
            onDismissRequest = { confirmSignOut = false },
            title = { Text("Sign out?") },
            text = { Text("Your settings and AirCursor PC files stay on this phone.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmSignOut = false
                    onDismiss()
                    scope.launch { AuthManager.signOut(context) }
                }) { Text("Sign out") }
            },
            dismissButton = { TextButton(onClick = { confirmSignOut = false }) { Text("Cancel") } },
        )
        return
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Avatar(user?.name, size = 56.dp) },
        title = { Text(user?.name ?: "Account") },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                val detail = user?.phone ?: user?.email
                val guest = user?.guest == true
                val provider = user?.provider
                if (detail != null) Text(detail, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(6.dp))
                Text(
                    when {
                        user == null -> ""
                        guest && AuthManager.configured -> "You are not signed in."
                        guest -> "Using AirCursor without an account."
                        provider == "google.com" -> "Signed in with Google"
                        provider == "phone" -> "Signed in with phone number"
                        else -> "Signed in"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            if (user != null && !user.guest) {
                TextButton(onClick = { confirmSignOut = true }) { Text("Sign out", color = MaterialTheme.colorScheme.error) }
            } else if (AuthManager.configured) {
                TextButton(onClick = {
                    onDismiss()
                    scope.launch { AuthManager.signOut(context) }
                }) { Text("Sign in") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
