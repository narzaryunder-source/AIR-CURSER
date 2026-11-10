package com.aircursor.air

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.aircursor.service.ScreenUtils
import org.mozilla.geckoview.ContentBlocking
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.WebExtension
import org.mozilla.geckoview.WebExtensionController
import kotlin.math.max

/**
 * Air's engine: one Gecko runtime for the whole app, made the first time Air opens.
 *
 * Pages are laid out like on a laptop: the phone's long side shows [DESKTOP_WIDTH] CSS pixels –
 * the same size as Brave inside AirCursor PC – and sites get the desktop version (YouTube,
 * ChatGPT, Claude…). Trackers are blocked by Gecko itself, ads by uBlock Origin.
 */
object AirEngine {
    const val PAGES = "resource://android/assets/air/"
    const val START = PAGES + "start.html"

    /**
     * CSS pixels across the long side of the screen: a laptop-sized page, a little smaller than
     * before (1280) so more fits on the phone's screen.
     */
    const val DESKTOP_WIDTH = 1400f

    private const val TAG = "AirEngine"
    private const val UBLOCK_ID = "uBlock0@raymondhill.net"
    private const val UBLOCK_URL = "https://addons.mozilla.org/firefox/downloads/latest/ublock-origin/latest.xpi"

    private var rt: GeckoRuntime? = null
    private var installing = false

    /** uBlock Origin once it is installed (null until then, or when ads are allowed). */
    var adBlocker: WebExtension? = null
        private set

    /** The ad blocker's button, for its panel ("this site" on/off) – null until it reports one. */
    var adBlockerAction: WebExtension.Action? = null
        private set

    /** Ads blocked per tab, from the ad blocker's badge (main thread). */
    val adsBlocked = HashMap<GeckoSession, Int>()

    /** Called when the ad blocker opens its panel: the activity shows the session in a dialog. */
    var showExtensionPopup: ((GeckoSession) -> Unit)? = null

    /** Something about the ad blocker changed (badge count, installed) – browsers refresh Shields. */
    val listeners = ArrayList<() -> Unit>()

    private val main = Handler(Looper.getMainLooper())

    /** GeckoView ships for 64-bit ARM only here – the same phones that run AirCursor PC. */
    fun supported() = Build.SUPPORTED_ABIS.contains("arm64-v8a")

    /**
     * Device pixels per CSS pixel: laptop layout (like the PC), never bigger than Android's own
     * size and never so small that text cannot be read on small, sharp screens.
     */
    fun scale(c: Context): Float {
        val s = ScreenUtils.realSize(c)
        val long = max(s.x, s.y).toFloat()
        val d = c.resources.displayMetrics.density
        return (long / DESKTOP_WIDTH).coerceAtLeast(d * 0.6f).coerceIn(1f, d.coerceAtLeast(1f))
    }

    fun runtime(c: Context): GeckoRuntime {
        rt?.let { return it }
        val app = c.applicationContext
        val prefs = AirData.prefs(app)
        val blocking = ContentBlocking.Settings.Builder()
            .antiTracking(ContentBlocking.AntiTracking.DEFAULT or ContentBlocking.AntiTracking.STP)
            .enhancedTrackingProtectionLevel(
                if (prefs.getBoolean(AirData.KEY_TRACKERS, true)) ContentBlocking.EtpLevel.DEFAULT else ContentBlocking.EtpLevel.NONE,
            )
            .cookieBehavior(ContentBlocking.CookieBehavior.ACCEPT_FIRST_PARTY_AND_ISOLATE_OTHERS)
            .safeBrowsing(ContentBlocking.SafeBrowsing.DEFAULT)
            .queryParameterStrippingEnabled(true)
            .build()
        val settings = GeckoRuntimeSettings.Builder()
            .displayDensityOverride(scale(app))
            .contentBlocking(blocking)
            .preferredColorScheme(AirData.colorScheme(prefs))
            .forceUserScalableEnabled(true)
            .inputAutoZoomEnabled(false)
            .doubleTapZoomingEnabled(true)
            .globalPrivacyControlEnabled(true)
            .webManifest(true)
            .aboutConfigEnabled(false)
            .consoleOutput(false)
            .build()
        val r = GeckoRuntime.create(app, settings)
        rt = r
        setUpExtensions(app, r)
        return r
    }

    fun current(): GeckoRuntime? = rt

    // ---- uBlock Origin (Brave-style ad blocking) ----

    private fun setUpExtensions(c: Context, r: GeckoRuntime) {
        val controller = r.webExtensionController
        // Only extensions Air itself installs come through here: accept uBlock Origin, nothing else.
        controller.promptDelegate = object : WebExtensionController.PromptDelegate {
            override fun onInstallPromptRequest(
                extension: WebExtension,
                permissions: Array<out String>,
                origins: Array<out String>,
                dataCollectionPermissions: Array<out String>,
            ): GeckoResult<WebExtension.PermissionPromptResponse> =
                GeckoResult.fromValue(WebExtension.PermissionPromptResponse(extension.id == UBLOCK_ID, false, false))

            override fun onUpdatePrompt(
                extension: WebExtension,
                newPermissions: Array<out String>,
                newOrigins: Array<out String>,
                newDataCollectionPermissions: Array<out String>,
            ): GeckoResult<org.mozilla.geckoview.AllowOrDeny> =
                GeckoResult.fromValue(
                    if (extension.id == UBLOCK_ID) org.mozilla.geckoview.AllowOrDeny.ALLOW else org.mozilla.geckoview.AllowOrDeny.DENY,
                )
        }
        controller.list().accept({ list ->
            val ub = list?.firstOrNull { it.id == UBLOCK_ID }
            when {
                ub != null -> attach(c, ub)
                AirData.prefs(c).getBoolean(AirData.KEY_ADS, true) -> installAdBlocker(c)
            }
        }, { e -> Log.w(TAG, "extension list failed", e) })
    }

    /** Downloads uBlock Origin from Mozilla's add-on site once (about 4 MB). */
    fun installAdBlocker(c: Context) {
        val r = rt ?: return
        if (installing || adBlocker != null) return
        installing = true
        r.webExtensionController.install(UBLOCK_URL).accept({ ext ->
            installing = false
            if (ext != null) attach(c, ext)
        }, { e ->
            installing = false
            Log.w(TAG, "ad blocker install failed (will retry next start)", e)
        })
    }

    private fun attach(c: Context, ext: WebExtension) {
        val wantOn = AirData.prefs(c).getBoolean(AirData.KEY_ADS, true)
        val on = ext.metaData.enabled
        if (wantOn != on) {
            val r = rt ?: return
            val res = if (wantOn) r.webExtensionController.enable(ext, WebExtensionController.EnableSource.USER)
            else r.webExtensionController.disable(ext, WebExtensionController.EnableSource.USER)
            res.accept({ e -> e?.let { use(it) } }, { _ -> use(ext) })
        } else {
            use(ext)
        }
    }

    private fun use(ext: WebExtension) {
        adBlocker = ext
        ext.setActionDelegate(object : WebExtension.ActionDelegate {
            override fun onBrowserAction(extension: WebExtension, session: GeckoSession?, action: WebExtension.Action) {
                main.post {
                    if (session == null) {
                        adBlockerAction = action
                    } else {
                        adsBlocked[session] = action.badgeText?.trim()?.toIntOrNull() ?: 0
                    }
                    for (l in listeners.toList()) l()
                }
            }

            override fun onTogglePopup(extension: WebExtension, action: WebExtension.Action): GeckoResult<GeckoSession>? = popup()

            override fun onOpenPopup(extension: WebExtension, action: WebExtension.Action): GeckoResult<GeckoSession>? = popup()
        })
        main.post { for (l in listeners.toList()) l() }
    }

    private fun popup(): GeckoResult<GeckoSession>? {
        val r = rt ?: return null
        val show = showExtensionPopup ?: return null
        val s = GeckoSession()
        s.open(r)
        main.post { show(s) }
        return GeckoResult.fromValue(s)
    }

    /** Settings: ads on/off. */
    fun setAdsBlocked(c: Context, on: Boolean) {
        AirData.prefs(c).edit().putBoolean(AirData.KEY_ADS, on).apply()
        val r = rt ?: return
        val ext = adBlocker
        if (ext == null) {
            if (on) installAdBlocker(c)
            return
        }
        val res = if (on) r.webExtensionController.enable(ext, WebExtensionController.EnableSource.USER)
        else r.webExtensionController.disable(ext, WebExtensionController.EnableSource.USER)
        res.accept({ e -> e?.let { use(it) } }, { _ -> })
    }

    /** Settings: trackers on/off (the whole browser). */
    fun setTrackersBlocked(c: Context, on: Boolean) {
        AirData.prefs(c).edit().putBoolean(AirData.KEY_TRACKERS, on).apply()
        rt?.settings?.contentBlocking?.setEnhancedTrackingProtectionLevel(
            if (on) ContentBlocking.EtpLevel.DEFAULT else ContentBlocking.EtpLevel.NONE,
        )
    }

    /** Settings: page colours (dark / light / like the phone). */
    fun setColorScheme(c: Context, scheme: Int) {
        AirData.prefs(c).edit().putInt(AirData.KEY_THEME, scheme).apply()
        rt?.settings?.preferredColorScheme = scheme
    }
}
