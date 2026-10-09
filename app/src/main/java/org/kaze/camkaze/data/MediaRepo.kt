package org.kaze.camkaze.data

import android.content.ClipData
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.provider.MediaStore
import android.util.Size
import java.text.DateFormat
import java.util.Date

data class Photo(
    val id: Long, val uri: Uri, val name: String,
    val takenMs: Long, val size: Long, val width: Int, val height: Int,
)

/** Normal photos: MediaStore, album Pictures/cam_kaze (no storage permission to write). */
object MediaRepo {
    const val REL_PATH = "Pictures/cam_kaze"
    private val COLLECTION: Uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI

    fun list(ctx: Context): List<Photo> {
        val cols = arrayOf(
            MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATE_TAKEN, MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.SIZE, MediaStore.Images.Media.WIDTH, MediaStore.Images.Media.HEIGHT,
        )
        val out = ArrayList<Photo>()
        ctx.contentResolver.query(
            COLLECTION, cols, "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?",
            arrayOf("$REL_PATH/%"), "${MediaStore.Images.Media.DATE_TAKEN} DESC",
        )?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                val taken = c.getLong(2).takeIf { it > 0 } ?: (c.getLong(3) * 1000)
                out += Photo(id, ContentUris.withAppendedId(COLLECTION, id), c.getString(1) ?: "photo",
                    taken, c.getLong(4), c.getInt(5), c.getInt(6))
            }
        }
        return out.sortedByDescending { it.takenMs }
    }

    fun thumb(ctx: Context, p: Photo): Bitmap =
        ctx.contentResolver.loadThumbnail(p.uri, Size(Images.THUMB, Images.THUMB), null)

    /** Downscaled, EXIF-aware decode (bounds memory use). */
    fun loadFull(ctx: Context, p: Photo, maxSide: Int): Bitmap =
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(ctx.contentResolver, p.uri)) { dec, info, _ ->
            val longest = maxOf(info.size.width, info.size.height)
            if (longest > maxSide) {
                val s = longest.toFloat() / maxSide
                dec.setTargetSize((info.size.width / s).toInt(), (info.size.height / s).toInt())
            }
        }

    fun readBytes(ctx: Context, p: Photo): ByteArray =
        ctx.contentResolver.openInputStream(p.uri)?.use { it.readBytes() }
            ?: throw java.io.IOException("Cannot open photo")

    /** Throws SecurityException if the photo is not owned by this app (caller falls back). */
    fun delete(ctx: Context, p: Photo): Boolean = ctx.contentResolver.delete(p.uri, null, null) > 0

    fun share(ctx: Context, p: Photo) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "image/jpeg"
            putExtra(Intent.EXTRA_STREAM, p.uri)
            clipData = ClipData.newRawUri("", p.uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ctx.startActivity(Intent.createChooser(send, "Share photo"))
    }

    fun details(p: Photo): String {
        val res = if (p.width > 0) "${p.width} x ${p.height}" else "unknown"
        val date = DateFormat.getDateTimeInstance().format(Date(p.takenMs))
        return "Name: ${p.name}\nTaken: $date\nSize: ${fmtSize(p.size)}\nResolution: $res\nAlbum: $REL_PATH"
    }

    fun fmtSize(n: Long): String =
        if (n >= 1_048_576) "%.2f MB".format(n / 1_048_576.0) else "%.1f KB".format(n / 1024.0)
}
