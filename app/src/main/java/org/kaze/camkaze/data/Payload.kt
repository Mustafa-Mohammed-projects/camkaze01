package org.kaze.camkaze.data

/**
 * Plaintext inside every .kaze file: rotation (int32, big-endian) + JPEG bytes.
 * Secure captures arrive from CameraX with a separate rotation value (no EXIF),
 * so we keep it next to the JPEG and apply it when decoding.
 */
object Payload {
    const val HEADER = 4

    fun pack(rotation: Int, jpeg: ByteArray): ByteArray {
        val out = ByteArray(HEADER + jpeg.size)
        out[0] = (rotation ushr 24).toByte()
        out[1] = (rotation ushr 16).toByte()
        out[2] = (rotation ushr 8).toByte()
        out[3] = rotation.toByte()
        System.arraycopy(jpeg, 0, out, HEADER, jpeg.size)
        return out
    }

    fun rotation(p: ByteArray): Int =
        ((p[0].toInt() and 0xFF) shl 24) or ((p[1].toInt() and 0xFF) shl 16) or
            ((p[2].toInt() and 0xFF) shl 8) or (p[3].toInt() and 0xFF)
}
