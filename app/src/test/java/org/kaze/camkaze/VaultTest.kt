package org.kaze.camkaze

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.kaze.camkaze.crypto.Vault
import org.kaze.camkaze.crypto.VaultException
import org.kaze.camkaze.crypto.VaultLockedException
import org.kaze.camkaze.data.Payload
import java.io.File

class VaultTest {
    private lateinit var cfg: File
    private lateinit var vault: Vault

    @Before fun setUp() {
        cfg = File.createTempFile("vault", ".cfg").also { it.delete() }
        vault = Vault(cfg, iterations = 1000)
    }

    @Test fun roundTripAndFormat() {
        vault.create("correct horse".toCharArray())
        val data = ByteArray(5000) { it.toByte() }
        val blob = vault.encrypt(data)
        assertEquals("KAZE", String(blob.copyOfRange(0, 4)))
        assertEquals(1, blob[4].toInt())
        assertArrayEquals(data, vault.decrypt(blob))
        assertFalse(blob.contentEquals(vault.encrypt(data))) // random nonce
    }

    @Test fun wrongPasswordThenUnlock() {
        vault.create("correct horse".toCharArray())
        vault.lock()
        assertFalse(vault.unlock("wrong password".toCharArray()))
        assertFalse(vault.isUnlocked)
        assertTrue(vault.unlock("correct horse".toCharArray()))
        assertTrue(vault.isUnlocked)
    }

    @Test fun lockBlocksCrypto() {
        vault.create("correct horse".toCharArray())
        vault.lock()
        assertThrows(VaultLockedException::class.java) { vault.encrypt(byteArrayOf(1)) }
    }

    @Test fun tamperDetected() {
        vault.create("correct horse".toCharArray())
        val blob = vault.encrypt("secret".toByteArray())
        for (pos in intArrayOf(6, 20, blob.size - 1)) {
            val bad = blob.copyOf().also { it[pos] = (it[pos].toInt() xor 1).toByte() }
            assertThrows(VaultException::class.java) { vault.decrypt(bad) }
        }
        assertThrows(VaultException::class.java) { vault.decrypt(ByteArray(10)) }
    }

    @Test fun passwordNotStoredAndWeakRejected() {
        vault.create("correct horse".toCharArray())
        assertFalse(cfg.readText().contains("correct horse"))
        val other = Vault(File.createTempFile("vault2", ".cfg").also { it.delete() }, 1000)
        assertThrows(IllegalArgumentException::class.java) { other.create("short".toCharArray()) }
    }

    @Test fun payloadRoundTrip() {
        val jpeg = byteArrayOf(1, 2, 3)
        for (rot in intArrayOf(0, 90, 180, 270)) {
            val p = Payload.pack(rot, jpeg)
            assertEquals(rot, Payload.rotation(p))
            assertArrayEquals(jpeg, p.copyOfRange(Payload.HEADER, p.size))
        }
    }
}
