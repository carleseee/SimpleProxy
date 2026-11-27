package com.carlese.simpleproxy.app

import com.carlese.simpleproxy.app.data.E2eeCipher
import org.junit.Assert.*
import org.junit.Test
import javax.crypto.AEADBadTagException

class E2eeCryptoIntegrityTest {

    @Test
    fun encryptAndDecryptProxyRoundtrip_preservesSecretFields() {
        val uid = "user-abc-123"
        val dek = E2eeCipher.generateMasterDek()
        val plaintext = """{"name":"Casa","protocol":"SOCKS5","host":"203.0.113.42","port":1080,"username":"carlo","password":"S3cret!Password"}"""

        val enc = E2eeCipher.encryptString(plaintext, dek, "proxy:$uid:profile-1")
        assertFalse("Host must never appear in ciphertext", enc.contains("203.0.113.42"))
        assertFalse("Username must never appear in ciphertext", enc.contains("carlo"))
        assertFalse("Password must never appear in ciphertext", enc.contains("S3cret!Password"))

        val dec = E2eeCipher.decryptString(enc, dek, "proxy:$uid:profile-1")
        assertEquals(plaintext, dec)
    }

    @Test
    fun encryptSamePayloadTwice_producesDifferentIvAndCiphertext() {
        val dek = E2eeCipher.generateMasterDek()
        val payload = """{"host":"198.51.100.7","port":8080}"""

        val enc1 = E2eeCipher.encryptString(payload, dek, "proxy:u1:p1")
        val enc2 = E2eeCipher.encryptString(payload, dek, "proxy:u1:p1")
        assertNotEquals("Each encryption must use a fresh random GCM IV", enc1, enc2)
    }

    @Test(expected = AEADBadTagException::class)
    fun decryptWithSwappedUserOrDocAad_isRejected() {
        val dek = E2eeCipher.generateMasterDek()
        val enc = E2eeCipher.encryptString("secret", dek, "proxy:user-1:p1")
        E2eeCipher.decryptString(enc, dek, "proxy:user-2:p1")
    }

    @Test(expected = AEADBadTagException::class)
    fun decryptTamperedCiphertext_isRejected() {
        val dek = E2eeCipher.generateMasterDek()
        val enc = E2eeCipher.encryptString("192.0.2.99", dek, "proxy:user-1:p1")
        val parts = enc.split(":")
        val lastChar = parts[1].last()
        val flipped = if (lastChar == 'A') 'B' else 'A'
        val tampered = parts[0] + ":" + parts[1].dropLast(1) + flipped
        E2eeCipher.decryptString(tampered, dek, "proxy:user-1:p1")
    }

    @Test
    fun encodeAndDecodeDekBase64_enforces256BitKeyLength() {
        val dek = E2eeCipher.generateMasterDek()
        val b64 = E2eeCipher.encodeDek(dek)
        val restored = E2eeCipher.decodeDek(b64)
        assertArrayEquals(dek.encoded, restored.encoded)

        try {
            E2eeCipher.decodeDek("AAAA")
            fail("Short key must be rejected")
        } catch (_: IllegalArgumentException) {
            // Expected key-size validation error
        }
    }
}
