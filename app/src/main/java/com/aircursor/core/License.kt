package com.aircursor.core

import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * Cloud PC Pro licence keys, verified offline.
 *
 * The seller keeps a private key (made once in "Owner tools"); the app only knows the public key.
 * A key looks like  CPC1-<payload>.<signature>  where payload = "DEVICE|EXPIRY_DAY|PLAN"
 * (EXPIRY_DAY = days since 1970-01-01) and the signature is ECDSA P-256 / SHA-256.
 * Each key works on one phone only (its device code) until the expiry day.
 */
object License {
    const val PREFIX = "CPC1-"
    private const val SIG_ALG = "SHA256withECDSA"
    private const val CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789" // no 0/O, 1/I

    data class Info(val deviceCode: String, val expiryEpochDay: Long, val plan: String)

    sealed class Result {
        data class Valid(val info: Info, val daysLeft: Long) : Result()
        data class Expired(val info: Info) : Result()
        data class WrongDevice(val info: Info) : Result()
        data object BadSignature : Result()
        data object Malformed : Result()
        data object NoSellerKey : Result()
    }

    private val b64e = Base64.getUrlEncoder().withoutPadding()
    private val b64d = Base64.getUrlDecoder()

    /** Short, readable code of this phone, e.g. "K7QM-X29D" (from a stable per-device id). */
    fun deviceCode(seed: String): String {
        val h = MessageDigest.getInstance("SHA-256").digest(("cloudpc:" + seed).toByteArray())
        var bits = 0L
        for (i in 0 until 5) bits = (bits shl 8) or (h[i].toLong() and 0xFF)
        val sb = StringBuilder()
        for (i in 0 until 8) {
            sb.append(CODE_ALPHABET[((bits shr (35 - i * 5)) and 31).toInt()])
            if (i == 3) sb.append('-')
        }
        return sb.toString()
    }

    /** Device codes are typed by people: ignore case, spaces and a missing dash. */
    fun normalizeCode(code: String): String {
        val c = code.uppercase().filter { it.isLetterOrDigit() }
        return if (c.length == 8) c.substring(0, 4) + "-" + c.substring(4) else c
    }

    fun newSellerKeys(): KeyPair = KeyPairGenerator.getInstance("EC").apply {
        initialize(ECGenParameterSpec("secp256r1"))
    }.generateKeyPair()

    fun encodePublic(k: PublicKey): String = Base64.getEncoder().encodeToString(k.encoded)
    fun encodePrivate(k: PrivateKey): String = Base64.getEncoder().encodeToString(k.encoded)

    fun decodePublic(b64: String): PublicKey? = try {
        KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(Base64.getMimeDecoder().decode(b64.trim())))
    } catch (e: Exception) {
        null
    }

    fun decodePrivate(b64: String): PrivateKey? = try {
        KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(Base64.getMimeDecoder().decode(b64.trim())))
    } catch (e: Exception) {
        null
    }

    fun issue(privateKey: PrivateKey, deviceCode: String, expiryEpochDay: Long, plan: String): String {
        val payload = "${normalizeCode(deviceCode)}|$expiryEpochDay|${plan.filter { it.isLetterOrDigit() }}"
        val sig = Signature.getInstance(SIG_ALG).run {
            initSign(privateKey)
            update(payload.toByteArray())
            sign()
        }
        return PREFIX + b64e.encodeToString(payload.toByteArray()) + "." + b64e.encodeToString(sig)
    }

    fun verify(key: String, publicKeyB64: String, deviceCode: String, todayEpochDay: Long): Result {
        val pub = decodePublic(publicKeyB64) ?: return Result.NoSellerKey
        val k = key.filterNot { it.isWhitespace() }
        if (!k.startsWith(PREFIX)) return Result.Malformed
        val parts = k.removePrefix(PREFIX).split('.')
        if (parts.size != 2) return Result.Malformed
        val payloadBytes: ByteArray
        val sig: ByteArray
        try {
            payloadBytes = b64d.decode(parts[0])
            sig = b64d.decode(parts[1])
        } catch (e: IllegalArgumentException) {
            return Result.Malformed
        }
        val ok = try {
            Signature.getInstance(SIG_ALG).run {
                initVerify(pub)
                update(payloadBytes)
                verify(sig)
            }
        } catch (e: Exception) {
            false
        }
        if (!ok) return Result.BadSignature
        val f = String(payloadBytes).split('|')
        val expiry = f.getOrNull(1)?.toLongOrNull()
        if (f.size != 3 || expiry == null) return Result.Malformed
        val info = Info(f[0], expiry, f[2])
        return when {
            info.deviceCode != normalizeCode(deviceCode) -> Result.WrongDevice(info)
            todayEpochDay > info.expiryEpochDay -> Result.Expired(info)
            else -> Result.Valid(info, info.expiryEpochDay - todayEpochDay)
        }
    }
}
