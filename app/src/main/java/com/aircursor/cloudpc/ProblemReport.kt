package com.aircursor.cloudpc

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Build
import com.aircursor.BuildConfig
import java.io.File

/**
 * "Send a problem report": what the developer needs to find a problem – phone, settings, engine
 * mode and the last log lines – as plain text through the phone's share sheet (WhatsApp, mail…).
 * Nothing is sent anywhere by itself, and no personal files are included.
 */
object ProblemReport {
    fun text(c: Context): String = buildString {
        appendLine("AirCursor PC – problem report")
        appendLine("App ${BuildConfig.VERSION_NAME}")
        appendLine("Phone: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
        if (Build.VERSION.SDK_INT >= 31) appendLine("Chip: ${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}")
        val mi = ActivityManager.MemoryInfo().also { c.getSystemService(ActivityManager::class.java)?.getMemoryInfo(it) }
        appendLine("Memory: ${mi.availMem / 1_000_000} MB free of ${mi.totalMem / 1_000_000} MB${if (mi.lowMemory) " (LOW)" else ""}")
        appendLine("Cores: ${Runtime.getRuntime().availableProcessors()} – max MHz: ${coreSpeeds()}")
        appendLine("Storage free: ${CloudPcEngine.freeBytes(c) / 1_000_000} MB")
        appendLine()
        appendLine("Engine: ${if (CloudPcEngine.noSeccomp(c)) "COMPATIBILITY mode (slow)" else "fast mode"}")
        appendLine("Installed: base ${CloudPcEngine.isReady(c)}, desktop ${CloudPcDesktop.isInstalled(c)}, " +
            "image ${File(CloudPcEngine.base(c), "image-version").takeIf { it.isFile }?.readText()?.trim() ?: "–"}")
        appendLine("AirBoost: ${AirBoost.current(c).label}, steady FPS ${AirBoost.fpsLabel(AirBoost.fpsLimit(c))}, " +
            "phone GPU ${AirBoost.phoneGpu(c)}")
        appendLine("Android program limit: ${when (ChildLimit.limitOff(c)) { true -> "lifted"; false -> "ON (32)"; null -> "unknown" }}")
        val st = CloudPcBus.state.value
        appendLine("Desktop running ${st.desktopRunning}, ready ${st.desktopReady}, busy ${st.busy}, error ${st.error ?: "–"}")
        val root = CloudPcEngine.rootfs(c)
        for ((name, lines) in listOf(
            "tmp/cloudpc-app-errors.log" to 40, "tmp/ts-station.log" to 20, "tmp/ts-station-games.log" to 30, "tmp/pulseaudio.log" to 15,
        )) {
            val f = File(root, name)
            if (f.isFile && f.length() > 0) {
                appendLine()
                appendLine("== /$name ==")
                tail(f, lines).forEach { appendLine(it) }
            }
        }
        appendLine()
        appendLine("== App log (last lines) ==")
        CloudPcBus.flush()
        CloudPcBus.log.value.takeLast(150).forEach { appendLine(it) }
    }.take(90_000)

    fun share(c: Context) {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, "AirCursor PC problem report")
            .putExtra(Intent.EXTRA_TEXT, text(c))
        c.startActivity(Intent.createChooser(send, "Send the problem report").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun coreSpeeds(): String = (0 until Runtime.getRuntime().availableProcessors()).joinToString(" ") { i ->
        try {
            (File("/sys/devices/system/cpu/cpu$i/cpufreq/cpuinfo_max_freq").readText().trim().toLong() / 1000).toString()
        } catch (_: Exception) {
            "?"
        }
    }

    private fun tail(f: File, n: Int): List<String> = try {
        val len = f.length()
        f.inputStream().use { s ->
            if (len > 64_000) s.skip(len - 64_000)
            s.bufferedReader().readLines().takeLast(n).map { it.take(300) }
        }
    } catch (_: Exception) {
        emptyList()
    }
}
