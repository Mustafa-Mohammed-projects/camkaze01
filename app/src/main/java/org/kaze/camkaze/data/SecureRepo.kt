package org.kaze.camkaze.data

import android.graphics.Bitmap
import android.media.ExifInterface
import org.kaze.camkaze.crypto.Vault
import java.io.File
import java.security.SecureRandom

data class SecureItem(val id: String, val file: File, val thumb: File, val takenMs: Long, val size: Long)

/**
 * Secure photo store. Only ciphertext ever reaches the disk:
 *   <id>.kaze    full image   (AES-256-GCM)
 *   <id>.t.kaze  256px thumbnail (AES-256-GCM)
 * File names are random; the capture time is kept in the file's mtime.
 */
class SecureRepo(private val dir: File, private val vault: Vault) {
    private val random = SecureRandom()

    init { dir.mkdirs() }

    fun list(): List<SecureItem> =
        (dir.listFiles() ?: emptyArray())
            .filter { it.name.endsWith(".kaze") && !it.name.endsWith(".t.kaze") }
            .mapNotNull { f ->
                val id = f.name.removeSuffix(".kaze")
                val t = File(dir, "$id.t.kaze")
                if (t.isFile) SecureItem(id, f, t, f.lastModified(), f.length()) else null
            }
            .sortedByDescending { it.takenMs }

    /** [jpeg] is raw JPEG bytes; [rotation] in degrees. Returns the new item id. */
    fun save(jpeg: ByteArray, rotation: Int, takenMs: Long): String {
        val thumbPlain = Images.thumbPayload(jpeg, rotation)
        val fullBlob = vault.encrypt(Payload.pack(rotation, jpeg))
        val thumbBlob = vault.encrypt(thumbPlain)
        thumbPlain.fill(0)
        val id = ByteArray(12).also(random::nextBytes).joinToString("") { "%02x".format(it) }
        write(File(dir, "$id.kaze"), fullBlob, takenMs)
        write(File(dir, "$id.t.kaze"), thumbBlob, takenMs)
        return id
    }

    fun thumb(item: SecureItem): Bitmap {
        val p = vault.decrypt(item.thumb.readBytes())
        try { return Images.decodePayload(p, Images.THUMB) } finally { p.fill(0) }
    }

    fun full(item: SecureItem, maxSide: Int): Bitmap {
        val p = vault.decrypt(item.file.readBytes())
        try { return Images.decodePayload(p, maxSide) } finally { p.fill(0) }
    }

    /**
     * Writes a decrypted, normal-looking JPEG (rotation stored as EXIF) to [target].
     * Only used for the explicit "share as photo" action.
     */
    fun decryptTo(item: SecureItem, target: File) {
        val p = vault.decrypt(item.file.readBytes())
        try {
            target.outputStream().use { it.write(p, Payload.HEADER, p.size - Payload.HEADER) }
            val orientation = when (Payload.rotation(p)) {
                90 -> ExifInterface.ORIENTATION_ROTATE_90
                180 -> ExifInterface.ORIENTATION_ROTATE_180
                270 -> ExifInterface.ORIENTATION_ROTATE_270
                else -> ExifInterface.ORIENTATION_NORMAL
            }
            try {
                val exif = ExifInterface(target.absolutePath)
                exif.setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
                exif.saveAttributes()
            } catch (_: Exception) { /* still a valid JPEG */ }
        } finally {
            p.fill(0)
        }
    }

    fun delete(item: SecureItem) {
        item.file.delete()
        item.thumb.delete()
    }

    private fun write(target: File, data: ByteArray, mtime: Long) {
        val tmp = File(target.path + ".tmp")
        tmp.writeBytes(data)
        if (!tmp.renameTo(target)) throw java.io.IOException("Could not write ${target.name}")
        target.setLastModified(mtime)
    }
}
