package com.aircursor.cloudpc

import android.content.Context
import com.aircursor.core.PinHasher

/**
 * Windows-style password for AirCursor PC. Stored only on this phone, as a salted hash
 * (never the password itself). The PC itself lives in the app's private storage on this phone –
 * nothing of it is uploaded or shared with other AirCursor users.
 */
object PcLock {
    private const val PREFS = "cloudpc_lock"
    private const val KEY_HASH = "hash"
    private const val KEY_NAME = "name"
    private const val KEY_NO_SETUP = "no_setup"   // "Don't show me again" on the first-start question
    private const val KEY_HINT = "hint"

    /** Lock again when the PC was out of sight this long. */
    const val RELOCK_AFTER_MS = 2 * 60 * 1000L
    const val MIN_LENGTH = 4

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun hasPassword(c: Context) = prefs(c).getString(KEY_HASH, null) != null

    /** First start without a password: ask to set one, unless the person said not to show it again. */
    fun shouldOfferSetup(c: Context) = !hasPassword(c) && !prefs(c).getBoolean(KEY_NO_SETUP, false)

    fun setNoSetup(c: Context, v: Boolean) = prefs(c).edit().putBoolean(KEY_NO_SETUP, v).apply()

    fun name(c: Context): String = prefs(c).getString(KEY_NAME, null)?.takeIf { it.isNotBlank() } ?: "User"

    fun setName(c: Context, name: String) = prefs(c).edit().putString(KEY_NAME, name.trim().take(24)).apply()

    fun hint(c: Context): String = prefs(c).getString(KEY_HINT, null).orEmpty()

    fun isValid(password: String) = password.length >= MIN_LENGTH

    fun setPassword(c: Context, password: String, hint: String = "") {
        require(isValid(password))
        prefs(c).edit()
            .putString(KEY_HASH, PinHasher.encode(password))
            .putString(KEY_HINT, hint.trim().take(40))
            .apply()
    }

    fun removePassword(c: Context) = prefs(c).edit().remove(KEY_HASH).remove(KEY_HINT).apply()

    fun check(c: Context, password: String): Boolean = PinHasher.verify(password, prefs(c).getString(KEY_HASH, null))
}
