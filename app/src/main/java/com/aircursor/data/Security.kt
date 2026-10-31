package com.aircursor.data

import android.content.Context
import android.os.SystemClock
import com.aircursor.core.HandProfile
import com.aircursor.core.PinHasher

/** PIN and owner-hand settings. The PIN is stored only as a salted hash. */
class Security(context: Context) {
    private val prefs = Prefs(context)
    private val sp = prefs.sp

    val hasPin: Boolean get() = sp.getString(KEY_PIN, null) != null

    fun setPin(pin: String) {
        require(PinHasher.isValidPin(pin))
        sp.edit().putString(KEY_PIN, PinHasher.encode(pin)).apply()
    }

    /** Checks a PIN, with a 30 s lock-out after 5 wrong tries. */
    fun checkPin(pin: String): Boolean {
        if (isLockedOut()) return false
        val ok = PinHasher.verify(pin, sp.getString(KEY_PIN, null))
        if (ok) {
            wrongTries = 0
        } else if (++wrongTries >= 5) {
            wrongTries = 0
            lockedUntil = SystemClock.elapsedRealtime() + 30_000
        }
        return ok
    }

    fun isLockedOut() = SystemClock.elapsedRealtime() < lockedUntil
    fun lockOutSecondsLeft() = ((lockedUntil - SystemClock.elapsedRealtime()) / 1000).coerceAtLeast(0)

    /** Removes PIN and hand lock (used after confirming the phone's own screen lock). */
    fun clearPinAndLock() {
        sp.edit().remove(KEY_PIN).putBoolean(AppSettings.OWNER_LOCK.key, false).apply()
    }

    val ownerProfile: HandProfile? get() = HandProfile.parse(sp.getString(KEY_PROFILE, null))

    fun saveOwnerProfile(profile: HandProfile) {
        sp.edit().putString(KEY_PROFILE, profile.serialize()).putBoolean(AppSettings.OWNER_LOCK.key, true).apply()
    }

    fun removeOwnerProfile() {
        sp.edit().remove(KEY_PROFILE).putBoolean(AppSettings.OWNER_LOCK.key, false).apply()
    }

    var ownerLock: Boolean
        get() = prefs.get(AppSettings.OWNER_LOCK) && ownerProfile != null
        set(value) = prefs.set(AppSettings.OWNER_LOCK, value)

    companion object {
        const val KEY_PIN = "pin_hash"
        const val KEY_PROFILE = "owner_profile"

        private var wrongTries = 0
        private var lockedUntil = 0L

        /** Threshold for [com.aircursor.core.OwnerGate] from the strictness setting. */
        fun threshold(strictness: Float) = when (Math.round(strictness)) {
            0 -> 3.5f
            2 -> 1.8f
            else -> 2.5f
        }
    }
}

/** App-level unlock state: after entering the PIN the app stays open until it is left for 60 s. */
object AppLock {
    private var unlocked = false
    private var leftAt = 0L

    fun isUnlocked(): Boolean {
        if (unlocked && leftAt != 0L && SystemClock.elapsedRealtime() - leftAt > 60_000) unlocked = false
        return unlocked
    }

    fun unlock() {
        unlocked = true
        leftAt = 0L
    }

    fun onAppHidden() {
        leftAt = SystemClock.elapsedRealtime()
    }

    fun onAppShown() {
        isUnlocked()
        leftAt = 0L
    }
}
