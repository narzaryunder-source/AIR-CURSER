package com.aircursor.cloudpc

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.provider.Settings
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption

/**
 * The phone's own apps inside AirCursor PC: every app from the phone's home screen gets an entry
 * in the PC's Start menu ("Phone apps") and in the "Phone Apps" folder on the desktop. Opening one
 * starts the real Android app – at the phone's full speed, GPU and FPS – and the hand cursor works
 * there as well. The PC asks for it through the desktop clipboard ("aircursor-app:<package>").
 */
object PhoneApps {
    const val MARKER = "aircursor-app:"
    private const val PREFIX = "aircursor-android-"
    private const val ICONS = "usr/share/aircursor/phone-icons"
    private const val MENU = "usr/share/applications"
    private const val FOLDER = "root/Desktop/Phone Apps"
    private val SAFE = Regex("[A-Za-z0-9._]+")

    /** Writes Start menu entries + icons for the phone's apps into the PC. Returns how many. */
    fun export(c: Context): Int {
        val pm = c.packageManager
        val root = CloudPcEngine.rootfs(c)
        val rootPath = root.toPath()
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = try {
            pm.queryIntentActivities(launcher, 0)
        } catch (_: Exception) {
            emptyList()
        }
        val seen = LinkedHashSet<String>()
        for (ri in apps) {
            val pkg = ri.activityInfo?.packageName ?: continue
            if (pkg == c.packageName || !SAFE.matches(pkg) || !seen.add(pkg)) continue
            try {
                val label = ri.loadLabel(pm).toString().replace(Regex("[\\r\\n\\t]+"), " ").trim().ifEmpty { pkg }
                val iconRel = "$ICONS/$pkg.png"
                val iconFile = File(root, iconRel)
                val updated = try {
                    pm.getPackageInfo(pkg, 0).lastUpdateTime
                } catch (_: Exception) {
                    0L
                }
                if (!iconFile.exists() || iconFile.lastModified() < updated) {
                    CloudPcEngine.writeGuestBytes(rootPath, iconRel, iconPng(ri.loadIcon(pm)))
                }
                val entry = "[Desktop Entry]\nType=Application\nName=$label\n" +
                    "Comment=Phone app – runs at full phone speed\nExec=cloudpc-phone-app $pkg\n" +
                    "Icon=/$iconRel\nTerminal=false\nCategories=X-AirCursor-Phone;\n"
                CloudPcEngine.writeGuestFile(rootPath, "$MENU/$PREFIX$pkg.desktop", entry)
                CloudPcEngine.writeGuestFile(rootPath, "$FOLDER/$pkg.desktop", entry, executable = true)
            } catch (_: Exception) {
            }
        }
        // Apps removed from the phone: remove their entries (only our own files).
        clean(File(root, MENU), seen) { it.startsWith(PREFIX) && it.endsWith(".desktop") }
        clean(File(root, FOLDER), seen) { it.endsWith(".desktop") }
        clean(File(root, ICONS), seen) { it.endsWith(".png") }
        return seen.size
    }

    private fun clean(dir: File, keep: Set<String>, ours: (String) -> Boolean) {
        val d = dir.toPath()
        if (!Files.isDirectory(d, LinkOption.NOFOLLOW_LINKS)) return
        for (f in dir.listFiles() ?: return) {
            val name = f.name
            if (!ours(name)) continue
            val pkg = name.removePrefix(PREFIX).removeSuffix(".desktop").removeSuffix(".png")
            if (pkg in keep) continue
            if (Files.isRegularFile(f.toPath(), LinkOption.NOFOLLOW_LINKS)) f.delete()
        }
    }

    private fun iconPng(d: android.graphics.drawable.Drawable): ByteArray {
        val size = 96
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        d.setBounds(0, 0, size, size)
        d.draw(Canvas(bmp))
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        bmp.recycle()
        return out.toByteArray()
    }

    /** Opens a phone app (as a window when the phone supports free-form windows). */
    fun open(c: Context, pkg: String): Boolean {
        if (!SAFE.matches(pkg)) return false
        val intent = c.packageManager.getLaunchIntentForPackage(pkg) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val freeform = c.packageManager.hasSystemFeature(PackageManager.FEATURE_FREEFORM_WINDOW_MANAGEMENT) ||
            try {
                Settings.Global.getInt(c.contentResolver, "enable_freeform_support", 0) == 1
            } catch (_: Exception) {
                false
            }
        return try {
            if (freeform) {
                val m = c.resources.displayMetrics
                val w = (m.widthPixels * 0.72f).toInt()
                val h = (m.heightPixels * 0.8f).toInt()
                val x = (m.widthPixels - w) / 2
                val y = (m.heightPixels - h) / 2
                val opts = ActivityOptions.makeBasic().setLaunchBounds(Rect(x, y, x + w, y + h))
                c.startActivity(intent, opts.toBundle())
            } else {
                c.startActivity(intent)
            }
            true
        } catch (_: Exception) {
            false
        }
    }
}
