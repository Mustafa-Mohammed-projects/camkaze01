package org.kaze.camkaze.crypto

import java.io.File
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

class VaultException(message: String, cause: Throwable? = null) : Exception(message, cause)
class VaultLockedException : Exception("Vault is locked")

/**
 * Password-based key management + AES-256-GCM (javax.crypto only, no extra libs).
 *
 *  master  = PBKDF2-HMAC-SHA256(password, salt, iterations, 256 bit)
 *  encKey  = HMAC-SHA256(master, "kaze/enc")      -> AES-256-GCM key (RAM only)
 *  verifier= HMAC-SHA256(master, "kaze/verify")   -> stored, constant-time compare
 *
 * .kaze layout: "KAZE" | version(1) | nonce(12) | ciphertext | tag(16)
 * The 17-byte header is authenticated as AAD. (Same wire format as the Python version.)
 */
class Vault(
    private val configFile: File,
    private val iterations: Int = KDF_ITERATIONS,
) {
    companion object {
        const val KDF_ITERATIONS = 600_000
        const val MIN_PASSWORD_LEN = 8
        private val MAGIC = byteArrayOf(0x4B, 0x41, 0x5A, 0x45) // "KAZE"
        private const val VERSION: Byte = 1
        private const val NONCE_LEN = 12
        private const val TAG_LEN = 16
        private const val HEADER_LEN = 4 + 1 + NONCE_LEN
    }

    private val random = SecureRandom()
    private val lock = Any()
    @Volatile private var key: ByteArray? = null

    val isConfigured: Boolean get() = configFile.isFile
    val isUnlocked: Boolean get() = key != null

    /** First-time setup. Throws IllegalArgumentException for weak passwords. */
    fun create(password: CharArray) {
        require(password.size >= MIN_PASSWORD_LEN) { "Password must be at least $MIN_PASSWORD_LEN characters" }
        check(!isConfigured) { "Vault already exists" }
        val salt = ByteArray(16).also(random::nextBytes)
        val master = derive(password, salt, iterations)
        val b64 = Base64.getEncoder()
        val text = "v=1\niter=$iterations\nsalt=${b64.encodeToString(salt)}\n" +
            "verifier=${b64.encodeToString(hmac(master, "kaze/verify"))}\n"
        val tmp = File(configFile.path + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(configFile)) throw VaultException("Could not write vault config")
        setKey(hmac(master, "kaze/enc"))
        master.fill(0)
    }

    /** True (and key loaded) if the password is correct. */
    fun unlock(password: CharArray): Boolean {
        val cfg = configFile.readLines().filter { '=' in it }
            .associate { it.substringBefore('=') to it.substringAfter('=') }
        val dec = Base64.getDecoder()
        val master = derive(password, dec.decode(cfg.getValue("salt")), cfg.getValue("iter").toInt())
        try {
            val ok = MessageDigest.isEqual(hmac(master, "kaze/verify"), dec.decode(cfg.getValue("verifier")))
            if (ok) setKey(hmac(master, "kaze/enc"))
            return ok
        } finally {
            master.fill(0)
        }
    }

    /** Zero the key. Anything encrypted afterwards fails with VaultLockedException. */
    fun lock() = synchronized(lock) {
        key?.fill(0)
        key = null
    }

    fun encrypt(plain: ByteArray): ByteArray {
        val k = key ?: throw VaultLockedException()
        val nonce = ByteArray(NONCE_LEN).also(random::nextBytes)
        val header = MAGIC + VERSION + nonce
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(k, "AES"), GCMParameterSpec(TAG_LEN * 8, nonce))
        cipher.updateAAD(header)
        return header + cipher.doFinal(plain) // doFinal output = ciphertext || tag
    }

    fun decrypt(blob: ByteArray): ByteArray {
        val k = key ?: throw VaultLockedException()
        if (blob.size < HEADER_LEN + TAG_LEN || !blob.copyOfRange(0, 4).contentEquals(MAGIC)) {
            throw VaultException("Not a .kaze file")
        }
        if (blob[4] != VERSION) throw VaultException("Unsupported .kaze version ${blob[4]}")
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val nonce = blob.copyOfRange(5, HEADER_LEN)
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(k, "AES"), GCMParameterSpec(TAG_LEN * 8, nonce))
            cipher.updateAAD(blob, 0, HEADER_LEN)
            return cipher.doFinal(blob, HEADER_LEN, blob.size - HEADER_LEN)
        } catch (e: GeneralSecurityException) {
            throw VaultException("Corrupted file or wrong key", e)
        }
    }

    private fun setKey(k: ByteArray) = synchronized(lock) { key = k }

    private fun derive(password: CharArray, salt: ByteArray, iter: Int): ByteArray {
        val spec = PBEKeySpec(password, salt, iter, 256)
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    private fun hmac(keyBytes: ByteArray, label: String): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(keyBytes, "HmacSHA256"))
        return mac.doFinal(label.toByteArray())
    }
}
