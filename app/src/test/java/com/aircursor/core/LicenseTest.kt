package com.aircursor.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LicenseTest {
    private val keys = License.newSellerKeys()
    private val pub = License.encodePublic(keys.public)
    private val today = 20_000L

    @Test
    fun deviceCodeIsStableAndReadable() {
        val a = License.deviceCode("abc123")
        assertEquals(a, License.deviceCode("abc123"))
        assertNotEquals(a, License.deviceCode("abc124"))
        assertTrue(Regex("[A-Z2-9]{4}-[A-Z2-9]{4}").matches(a))
        assertEquals(a, License.normalizeCode(a.lowercase().replace("-", " ")))
    }

    @Test
    fun validKeyIsAccepted() {
        val code = License.deviceCode("phone-1")
        val key = License.issue(keys.private, code, today + 30, "monthly")
        val r = License.verify(key, pub, code, today)
        assertTrue(r is License.Result.Valid)
        assertEquals(30L, (r as License.Result.Valid).daysLeft)
        assertEquals("monthly", r.info.plan)
    }

    @Test
    fun keySurvivesCopyPasteWhitespace() {
        val code = License.deviceCode("phone-1")
        val key = License.issue(keys.private, code, today + 3, "monthly")
        val messy = " " + key.substring(0, 20) + "\n" + key.substring(20) + " "
        assertTrue(License.verify(messy, pub, code, today) is License.Result.Valid)
    }

    @Test
    fun keyForAnotherPhoneIsRejected() {
        val key = License.issue(keys.private, License.deviceCode("phone-1"), today + 30, "monthly")
        assertTrue(License.verify(key, pub, License.deviceCode("phone-2"), today) is License.Result.WrongDevice)
    }

    @Test
    fun expiredKeyIsRejected() {
        val code = License.deviceCode("phone-1")
        val key = License.issue(keys.private, code, today - 1, "monthly")
        assertTrue(License.verify(key, pub, code, today) is License.Result.Expired)
        // The last day still works.
        val lastDay = License.issue(keys.private, code, today, "monthly")
        assertTrue(License.verify(lastDay, pub, code, today) is License.Result.Valid)
    }

    @Test
    fun editedOrForeignKeysAreRejected() {
        val code = License.deviceCode("phone-1")
        val key = License.issue(keys.private, code, today + 30, "monthly")
        // Changing the expiry inside the payload breaks the signature.
        val payload = java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString("$code|${today + 9999}|monthly".toByteArray())
        val forged = License.PREFIX + payload + "." + key.substringAfter('.')
        assertEquals(License.Result.BadSignature, License.verify(forged, pub, code, today))
        // A key signed by someone else's seller key.
        val other = License.newSellerKeys()
        val foreign = License.issue(other.private, code, today + 30, "monthly")
        assertEquals(License.Result.BadSignature, License.verify(foreign, pub, code, today))
        assertEquals(License.Result.Malformed, License.verify("hello", pub, code, today))
        assertEquals(License.Result.NoSellerKey, License.verify(key, "", code, today))
    }

    @Test
    fun sellerKeysRoundTripAsText() {
        val priv = License.decodePrivate(License.encodePrivate(keys.private))!!
        val code = License.deviceCode("phone-9")
        val key = License.issue(priv, code, today + 365, "yearly")
        assertTrue(License.verify(key, pub, code, today) is License.Result.Valid)
    }
}
