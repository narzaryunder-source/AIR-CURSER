package com.aircursor.ui

import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aircursor.R

private class Topic(
    @DrawableRes val icon: Int,
    val title: String,
    val summary: String,
    val rows: List<Pair<String, String>> = emptyList(),
    val steps: List<String> = emptyList(),
)

private val TOPICS = listOf(
    Topic(
        R.drawable.ic_ui_play, "Get started", "Three steps to your first click",
        steps = listOf(
            "Open AirCursor and switch on Hand control.",
            "Hold your hand 30–60 cm from the front camera, palm facing the screen.",
            "Move your index finger to move the pointer. Pinch thumb and index finger to click.",
            "If the pointer shakes, tap Auto-tune and keep your finger still for 2 seconds.",
        ),
    ),
    Topic(
        R.drawable.ic_ui_hand, "Hand gestures", "Everything you can do in the air",
        rows = listOf(
            "Move index finger" to "Move the pointer",
            "Quick pinch" to "Click / tap",
            "Two quick pinches" to "Double click",
            "Pinch and hold (ring fills)" to "Long press · right click on the PC",
            "Pinch, hold a moment, then move" to "Drag · select · move windows",
            "Two fingers up, move up/down" to "Scroll (Simple mode off)",
            "Open palm swipe left" to "Back (Simple mode off)",
            "Open palm held still" to "Home (Simple mode off)",
        ),
    ),
    Topic(
        R.drawable.ic_ui_desktop, "Using AirCursor PC", "A full desktop on your phone",
        steps = listOf(
            "Open AirCursor PC from the home screen icon or from AirCursor.",
            "Start (bottom left) opens all apps. The taskbar shows open windows.",
            "Tap = click, press and hold = right click, two fingers = scroll.",
            "The button on the right edge opens the toolbar: keyboard, hand control, paste, files, wallpaper, boost and FPS.",
            "Double-tap Getting Started on the desktop for a full guide.",
        ),
    ),
    Topic(
        R.drawable.ic_ui_store, "Install apps", "Office, photo editing, video, coding and games",
        steps = listOf(
            "Open App Store on the desktop.",
            "Tick apps such as LibreOffice, GIMP, VLC or VS Code and tap Install.",
            "The Status column shows which apps are already installed.",
            "New apps appear on the desktop and in Start menu Favourites. Games go into the Games folder.",
            "Search 60,000+ more apps with the search button.",
            "Website downloads: choose the ARM64 .deb file and double-tap it.",
            "Windows .exe programs do not run – use the Linux version from the App Store.",
        ),
    ),
    Topic(
        R.drawable.ic_ui_sparkles, "AI apps", "Claude, ChatGPT, Gemini and offline AI",
        steps = listOf(
            "Open the AI Apps folder on the desktop.",
            "Claude, ChatGPT and Gemini open in Firefox – sign in once, Firefox remembers it.",
            "Offline AI works without internet using a small model (first start downloads about 1 GB).",
            "Developers: install Claude Code from the App Store and type claude in Terminal.",
        ),
    ),
    Topic(
        R.drawable.ic_ui_folder, "Files and copy-paste", "Move files and text between phone and PC",
        steps = listOf(
            "Toolbar → Files → Import from phone copies files into Downloads/From phone.",
            "Put files in the To phone folder on the desktop, then Toolbar → Files → Save to phone.",
            "Text copied in the PC is copied to your phone automatically.",
            "Toolbar → Paste puts the phone's copied text into the PC.",
        ),
    ),
    Topic(
        R.drawable.ic_ui_bolt, "Speed and AirBoost", "More FPS for games and video",
        rows = listOf(
            "Game" to "Highest FPS for games and videos",
            "Performance" to "Smooth on slower phones",
            "Balanced" to "Recommended",
            "Quality" to "Sharpest text on fast phones",
        ),
        steps = listOf(
            "Change the profile any time, it applies at once: desktop toolbar → AirBoost, or AirCursor PC → AirBoost.",
            "AirBoost → Boost now stops background jobs. Toolbar → FPS shows the frame rate.",
            "Open Benchmark on the desktop to measure graphics FPS and compare profiles.",
        ),
    ),
    Topic(
        R.drawable.ic_ui_shield, "Privacy and your data", "What stays where",
        steps = listOf(
            "Hand tracking runs only on this phone. Camera images are never saved or sent.",
            "AirCursor PC and its files live inside the AirCursor app on this phone.",
            "Android keeps AirCursor PC in its own sandbox: it cannot reach your other apps, photos or messages.",
            "Firefox in the PC uses HTTPS only, blocks trackers and updates itself for security.",
            "App updates keep everything. Signing out keeps everything.",
            "Uninstalling AirCursor deletes AirCursor PC – save important files to your phone first.",
        ),
    ),
    Topic(
        R.drawable.ic_ui_help, "Troubleshooting", "Quick fixes",
        rows = listOf(
            "Pointer shakes" to "Tap Auto-tune, or raise Anti-shake in Settings",
            "Clicks are hard" to "Turn on Dwell click in Settings",
            "Pointer moves the wrong way" to "Settings → Swap left / right",
            "Desktop black or frozen" to "Toolbar → Close → Stop the desktop, then open it again",
            "Desktop slow" to "Toolbar → AirBoost → Game",
            "New app not on the desktop" to "Start menu → All apps or search its name",
            "Taskbar missing" to "Terminal: aircursor-windows-layout --force",
        ),
    ),
)

@Composable
fun HelpScreen(onBack: () -> Unit) {
    ScreenScaffold("Help & guide", onBack) {
        for (t in TOPICS) TopicCard(t)
    }
}

@Composable
private fun TopicCard(t: Topic) {
    var open by remember { mutableStateOf(false) }
    SectionCard {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { open = !open },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconBadge(t.icon, MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(t.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(t.summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            UiIcon(
                if (open) R.drawable.ic_ui_up else R.drawable.ic_ui_down,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                size = 20.dp,
            )
        }
        AnimatedVisibility(open) {
            Column(Modifier.padding(top = 12.dp)) {
                for ((a, b) in t.rows) {
                    Row(Modifier.padding(vertical = 6.dp)) {
                        Text(a, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                        Spacer(Modifier.width(10.dp))
                        Text(
                            b,
                            Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    ThinDivider()
                }
                if (t.rows.isNotEmpty() && t.steps.isNotEmpty()) Spacer(Modifier.height(10.dp))
                t.steps.forEachIndexed { i, s ->
                    Row(Modifier.padding(vertical = 5.dp)) {
                        Text(
                            "${i + 1}.",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.width(24.dp),
                        )
                        Text(s, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}
