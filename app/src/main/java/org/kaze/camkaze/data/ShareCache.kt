package org.kaze.camkaze.data

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import org.kaze.camkaze.Services
import java.io.File
import java.security.SecureRandom

/**
 * Sharing a secure photo as a normal image needs a plaintext JPEG that other apps can read.
 * It is written to a private cache folder (random name, no vault info), exposed through
 * FileProvider with a temporary read grant, and purged on app start and after 10 minutes.
 */
object ShareCache {
    private val random = SecureRandom()

    private fun dir(ctx: Context) = File(ctx.cacheDir, "share").apply { mkdirs() }

    /** Decrypt [item] into the share cache; returns a content:// URI for it. */
    fun export(ctx: Context, item: SecureItem): Uri {
        val name = ByteArray(8).also(random::nextBytes).joinToString("") { "%02x".format(it) }
        val file = File(dir(ctx), "IMG_$name.jpg")
        Services.secure.decryptTo(item, file)
        return FileProvider.getUriForFile(ctx, "${ctx.packageName}.share", file)
    }

    /** Opens the system share sheet (WhatsApp, Messenger, Bluetooth, Nearby Share, ...). */
    fun launch(ctx: Context, uri: Uri) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "image/jpeg"
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri("", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ctx.startActivity(Intent.createChooser(send, "Share photo"))
    }

    /** Delete cached copies older than [maxAgeMs] (0 = all). */
    fun purge(ctx: Context, maxAgeMs: Long) {
        val now = System.currentTimeMillis()
        File(ctx.cacheDir, "share").listFiles()?.forEach {
            if (now - it.lastModified() >= maxAgeMs) it.delete()
        }
    }
}
