package com.aircursor.pro

import android.annotation.SuppressLint
import android.content.Context
import android.provider.Settings
import android.util.Log
import com.aircursor.core.License
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate

/**
 * Plan settings. The bundled assets/pro.json is the fallback; the live copy is read from
 * cloudpc/pro.json in the GitHub repo, so the owner can switch Pro on, change the price or
 * the UPI id from the phone's browser – no new APK needed.
 */
data class ProConfig(
    val enabled: Boolean = false,
    val priceMonthly: Int = 159,
    val priceYearly: Int = 999,
    val trialDays: Int = 7,
    val upiId: String = "",
    val payeeName: String = "Cloud PC",
    val whatsapp: String = "",
    val publicKey: String = "",
) {
    companion object {
        fun parse(json: String): ProConfig? = try {
            val o = JSONObject(json)
            ProConfig(
                enabled = o.optBoolean("enabled", false),
                priceMonthly = o.optInt("priceMonthly", 159),
                priceYearly = o.optInt("priceYearly", 999),
                trialDays = o.optInt("trialDays", 7),
                upiId = o.optString("upiId", "").trim(),
                payeeName = o.optString("payeeName", "Cloud PC").trim(),
                whatsapp = o.optString("whatsapp", "").filter { it.isDigit() },
                publicKey = o.optString("publicKey", "").trim(),
            )
        } catch (e: Exception) {
            null
        }
    }
}

sealed class ProStatus {
    /** Pro is not switched on by the owner yet: everything is free. */
    data object Free : ProStatus()
    data class Trial(val daysLeft: Long) : ProStatus()
    data class Active(val daysLeft: Long, val plan: String) : ProStatus()
    data class Locked(val reason: String) : ProStatus()

    val allowed get() = this !is Locked
}

/**
 * Which features are paid: Cloud PC one-tap start (icon, tile, card) and PC Mode.
 * The hand cursor itself (an accessibility feature) always stays free.
 */
object ProAccess {
    const val CONFIG_URL = "https://raw.githubusercontent.com/narzaryunder-source/AIR-CURSER/main/cloudpc/pro.json"
    private const val PREFS = "aircursor_pro"
    private const val KEY_CONFIG = "config_json"
    private const val KEY_LICENSE = "license"
    private const val KEY_TRIAL_START = "trial_start_day"

    private fun sp(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun today(): Long = LocalDate.now().toEpochDay()

    @SuppressLint("HardwareIds")
    fun deviceCode(c: Context): String {
        val id = Settings.Secure.getString(c.contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown"
        return License.deviceCode(id)
    }

    fun config(c: Context): ProConfig {
        sp(c).getString(KEY_CONFIG, null)?.let { ProConfig.parse(it) }?.let { return it }
        val bundled: ProConfig? = try {
            c.assets.open("pro.json").bufferedReader().use { ProConfig.parse(it.readText()) }
        } catch (e: Exception) {
            null
        }
        return bundled ?: ProConfig()
    }

    /** Downloads the live plan settings (call from a background thread). */
    fun refresh(c: Context): ProConfig? {
        return try {
            val conn = URL(CONFIG_URL).openConnection() as HttpURLConnection
            conn.connectTimeout = 6000
            conn.readTimeout = 6000
            conn.useCaches = false
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()
            val cfg = ProConfig.parse(text) ?: return null
            sp(c).edit().putString(KEY_CONFIG, text).apply()
            cfg
        } catch (e: Exception) {
            Log.i("ProAccess", "config refresh failed: ${e.message}")
            null
        }
    }

    fun savedLicense(c: Context): String = sp(c).getString(KEY_LICENSE, "") ?: ""

    /** Checks and saves a licence key. Returns null when it works, else a message for the user. */
    fun activate(c: Context, key: String): String? {
        val cfg = config(c)
        return when (val r = License.verify(key, cfg.publicKey, deviceCode(c), today())) {
            is License.Result.Valid -> {
                sp(c).edit().putString(KEY_LICENSE, key.filterNot { it.isWhitespace() }).apply()
                null
            }
            is License.Result.Expired -> "Yeh key expire ho chuki hai. Naya plan lo."
            is License.Result.WrongDevice -> "Yeh key kisi aur phone (${r.info.deviceCode}) ki hai."
            License.Result.BadSignature -> "Key galat hai (copy poori karo)."
            License.Result.Malformed -> "Key ka format galat hai. CPC1- se shuru hoti hai."
            License.Result.NoSellerKey -> "Seller ne abhi key setup nahi ki. Thodi der baad try karo."
        }
    }

    fun status(c: Context): ProStatus {
        val cfg = config(c)
        if (!cfg.enabled) return ProStatus.Free
        val key = savedLicense(c)
        if (key.isNotEmpty()) {
            val r = License.verify(key, cfg.publicKey, deviceCode(c), today())
            if (r is License.Result.Valid) return ProStatus.Active(r.daysLeft, r.info.plan)
        }
        // The free trial starts the first time this phone sees Pro switched on.
        val s = sp(c)
        var start = s.getLong(KEY_TRIAL_START, -1L)
        if (start < 0) {
            start = today()
            s.edit().putLong(KEY_TRIAL_START, start).apply()
        }
        val left = start + cfg.trialDays - today()
        if (left > 0) return ProStatus.Trial(left)
        return ProStatus.Locked(if (key.isNotEmpty()) "Plan khatam ho gaya" else "Free trial khatam")
    }

    fun allowed(c: Context): Boolean = status(c).allowed

    // ---- Owner tools: the seller key lives only on the owner's phone ----

    private const val KEY_SELLER_PRIVATE = "seller_private"
    private const val KEY_SELLER_PUBLIC = "seller_public"

    fun sellerPublic(c: Context): String? = sp(c).getString(KEY_SELLER_PUBLIC, null)
    fun sellerPrivate(c: Context): String? = sp(c).getString(KEY_SELLER_PRIVATE, null)

    fun createSellerKeys(c: Context) {
        val kp = License.newSellerKeys()
        sp(c).edit()
            .putString(KEY_SELLER_PRIVATE, License.encodePrivate(kp.private))
            .putString(KEY_SELLER_PUBLIC, License.encodePublic(kp.public))
            .apply()
    }

    /** One text with both halves of the seller key, for a safe backup (e.g. in Google Drive). */
    fun sellerBackup(c: Context): String? {
        val priv = sellerPrivate(c) ?: return null
        val pub = sellerPublic(c) ?: return null
        return "CPCSELLER1:$priv:$pub"
    }

    /** Restores a backup made by [sellerBackup]. Returns false if the text is not a valid backup. */
    fun restoreSellerKey(c: Context, backup: String): Boolean {
        val parts = backup.filterNot { it.isWhitespace() }.split(':')
        if (parts.size != 3 || parts[0] != "CPCSELLER1") return false
        val priv = License.decodePrivate(parts[1]) ?: return false
        // The two halves must belong together: sign a test key and check it.
        val test = License.issue(priv, "TEST-TEST", 1, "check")
        if (License.verify(test, parts[2], "TEST-TEST", 0) !is License.Result.Valid) return false
        sp(c).edit()
            .putString(KEY_SELLER_PRIVATE, parts[1])
            .putString(KEY_SELLER_PUBLIC, parts[2])
            .apply()
        return true
    }

    fun issueKey(c: Context, customerCode: String, days: Int, plan: String): String? {
        val priv = sellerPrivate(c)?.let { License.decodePrivate(it) } ?: return null
        return License.issue(priv, customerCode, today() + days, plan)
    }
}
