package org.kaze.camkaze.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/** All image work happens on byte arrays / bitmaps in RAM. */
object Images {
    const val THUMB = 256

    /** Decode JPEG at [off, off+len), downsampled to ~maxSide, then rotated. */
    fun decode(data: ByteArray, off: Int, len: Int, rotation: Int, maxSide: Int): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(data, off, len, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxSide && bounds.outHeight / (sample * 2) >= maxSide) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bmp = BitmapFactory.decodeByteArray(data, off, len, opts)
            ?: throw IllegalArgumentException("Cannot decode image")
        if (rotation == 0) return bmp
        val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height,
            Matrix().apply { postRotate(rotation.toFloat()) }, true)
        if (rotated !== bmp) bmp.recycle()
        return rotated
    }

    fun decodePayload(payload: ByteArray, maxSide: Int): Bitmap =
        decode(payload, Payload.HEADER, payload.size - Payload.HEADER, Payload.rotation(payload), maxSide)

    /** Small encrypted-thumbnail payload (rotation already baked in => rotation 0). */
    fun thumbPayload(jpeg: ByteArray, rotation: Int): ByteArray {
        var bmp = decode(jpeg, 0, jpeg.size, rotation, THUMB)
        val longest = maxOf(bmp.width, bmp.height)
        if (longest > THUMB) {
            val s = THUMB.toFloat() / longest
            val scaled = Bitmap.createScaledBitmap(bmp, (bmp.width * s).toInt().coerceAtLeast(1),
                (bmp.height * s).toInt().coerceAtLeast(1), true)
            if (scaled !== bmp) bmp.recycle()
            bmp = scaled
        }
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 80, out)
        bmp.recycle()
        return Payload.pack(0, out.toByteArray())
    }

    /** EXIF orientation -> degrees (0/90/180/270). */
    fun exifRotation(jpeg: ByteArray): Int = try {
        when (ExifInterface(ByteArrayInputStream(jpeg))
            .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }
    } catch (e: Exception) {
        0
    }
}
