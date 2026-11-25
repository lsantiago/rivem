package org.rivem.core.imaging

import kotlin.math.min

/**
 * Single-channel image with Float samples on the 0–255 scale (what OpenCV gives RIVeR after `cv2.imread(...,
 * IMREAD_GRAYSCALE)` or a BGR→gray conversion). Row-major, origin top-left, y down: `pixels[y * width + x]`.
 *
 * Shared by the geometry (rectification, masks), PIV and STIV code. Decoding JPEG or video frames is done by the app
 * (Android Bitmap / MediaCodec) and handed to the core as one of these buffers.
 */
class GrayImage(val width: Int, val height: Int, val pixels: FloatArray) {
    init {
        require(width >= 0 && height >= 0 && pixels.size == width * height) { "GrayImage size mismatch" }
    }

    constructor(width: Int, height: Int, fill: Float = 0f) : this(width, height, FloatArray(width * height) { fill })

    operator fun get(x: Int, y: Int): Float = pixels[y * width + x]

    operator fun set(x: Int, y: Int, value: Float) {
        pixels[y * width + x] = value
    }

    /** Bilinear sample at a sub-pixel position (pixel centres at integer coordinates); null outside the image. */
    fun bilinear(x: Double, y: Double): Float? {
        if (x < 0 || y < 0 || x > (width - 1).toDouble() || y > (height - 1).toDouble()) return null
        val x0 = min(x.toInt(), width - 1)
        val y0 = min(y.toInt(), height - 1)
        val x1 = min(x0 + 1, width - 1)
        val y1 = min(y0 + 1, height - 1)
        val fx = (x - x0).toFloat()
        val fy = (y - y0).toFloat()
        val top = this[x0, y0] * (1 - fx) + this[x1, y0] * fx
        val bottom = this[x0, y1] * (1 - fx) + this[x1, y1] * fx
        return top * (1 - fy) + bottom * fy
    }

    fun copy(): GrayImage = GrayImage(width, height, pixels.copyOf())

    override fun equals(other: Any?): Boolean =
        other is GrayImage && width == other.width && height == other.height && pixels.contentEquals(other.pixels)

    override fun hashCode(): Int = 31 * (31 * width + height) + pixels.contentHashCode()
}

/** 8-bit RGBA image, row-major, 4 bytes per pixel (R, G, B, A). Bytes are unsigned: read them with `toInt() and 0xFF`. */
class RGBAImage(val width: Int, val height: Int, val bytes: ByteArray) {
    init {
        require(bytes.size == width * height * 4) { "RGBAImage size mismatch" }
    }

    constructor(width: Int, height: Int) : this(width, height, ByteArray(width * height * 4))

    /** Luminance like OpenCV's `COLOR_BGR2GRAY` / `COLOR_RGB2GRAY`: 0.299 R + 0.587 G + 0.114 B. */
    fun toGray(): GrayImage {
        val px = FloatArray(width * height)
        for (i in 0 until width * height) {
            val r = (bytes[4 * i].toInt() and 0xFF).toFloat()
            val g = (bytes[4 * i + 1].toInt() and 0xFF).toFloat()
            val b = (bytes[4 * i + 2].toInt() and 0xFF).toFloat()
            px[i] = 0.299f * r + 0.587f * g + 0.114f * b
        }
        return GrayImage(width, height, px)
    }

    override fun equals(other: Any?): Boolean =
        other is RGBAImage && width == other.width && height == other.height && bytes.contentEquals(other.bytes)

    override fun hashCode(): Int = 31 * (31 * width + height) + bytes.contentHashCode()
}
