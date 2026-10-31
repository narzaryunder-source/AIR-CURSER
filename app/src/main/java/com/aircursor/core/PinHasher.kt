package com.aircursor.core

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/** Stores a PIN as a salted PBKDF2 hash, never as plain text. */
object PinHasher {
    private const val ITERATIONS = 20_000
    private const val KEY_BITS = 256

    fun isValidPin(pin: String) = pin.length in 4..8 && pin.all { it.isDigit() }

    fun encode(pin: String, salt: ByteArray = newSalt(), iterations: Int = ITERATIONS): String {
        val hash = derive(pin, salt, iterations)
        val b64 = Base64.getEncoder()
        return "v1:$iterations:${b64.encodeToString(salt)}:${b64.encodeToString(hash)}"
    }

    fun verify(pin: String, encoded: String?): Boolean {
        val parts = encoded?.split(':') ?: return false
        if (parts.size != 4 || parts[0] != "v1") return false
        val iterations = parts[1].toIntOrNull() ?: return false
        return try {
            val b64 = Base64.getDecoder()
            val salt = b64.decode(parts[2])
            val expected = b64.decode(parts[3])
            MessageDigest.isEqual(expected, derive(pin, salt, iterations))
        } catch (e: IllegalArgumentException) {
            false
        }
    }

    private fun newSalt() = ByteArray(16).also { SecureRandom().nextBytes(it) }

    private fun derive(pin: String, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, iterations, KEY_BITS)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }
}
