package org.rivem.app.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import org.rivem.app.diagnostics.AppLog
import org.rivem.core.imaging.GrayImage
import org.rivem.core.imaging.PNG
import org.rivem.core.imaging.RGBAImage
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

/** Conversions between image files / `Bitmap` and the core's pixel buffers (`RGBAImage`, `GrayImage`). */
object ImageCoding {
    sealed class Failure(message: String) : Exception(message) {
        class Unreadable(file: File) : Failure("No se pudo leer la imagen ${file.name}.")
        class Encode(why: String) : Failure("No se pudo guardar la imagen: $why.")
    }

    /** Full-resolution decode (ARGB_8888, EXIF orientation ignored like OpenCV and the iOS app). */
    fun bitmap(file: File): Bitmap {
        val options = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
        return BitmapFactory.decodeFile(file.path, options) ?: throw Failure.Unreadable(file)
    }

    /** RGBA8 copy of a bitmap (straight alpha), resampled to the given size if it differs. */
    fun rgba(bitmap: Bitmap, width: Int? = null, height: Int? = null): RGBAImage {
        val w = width ?: bitmap.width
        val h = height ?: bitmap.height
        val src = if (w != bitmap.width || h != bitmap.height) Bitmap.createScaledBitmap(bitmap, w, h, true) else bitmap
        val pixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)
        if (src !== bitmap) src.recycle()
        val bytes = ByteArray(w * h * 4)
        for (i in 0 until w * h) {
            val c = pixels[i]
            bytes[4 * i] = (c shr 16 and 0xFF).toByte()
            bytes[4 * i + 1] = (c shr 8 and 0xFF).toByte()
            bytes[4 * i + 2] = (c and 0xFF).toByte()
            bytes[4 * i + 3] = (c ushr 24 and 0xFF).toByte()
        }
        return RGBAImage(w, h, bytes)
    }

    fun rgba(file: File): RGBAImage {
        val b = bitmap(file)
        try {
            return rgba(b)
        } finally {
            b.recycle()
        }
    }

    fun gray(file: File): GrayImage = rgba(file).toGray()

    /** Bitmap over an `RGBAImage` with straight (non-premultiplied) alpha, which is what the core produces. */
    fun bitmap(image: RGBAImage): Bitmap {
        val n = image.width * image.height
        val pixels = IntArray(n)
        val b = image.bytes
        for (i in 0 until n) {
            val r = b[4 * i].toInt() and 0xFF
            val g = b[4 * i + 1].toInt() and 0xFF
            val bl = b[4 * i + 2].toInt() and 0xFF
            val a = b[4 * i + 3].toInt() and 0xFF
            pixels[i] = (a shl 24) or (r shl 16) or (g shl 8) or bl
        }
        val bmp = Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888)
        bmp.isPremultiplied = false
        bmp.setPixels(pixels, 0, image.width, 0, 0, image.width, image.height)
        return bmp
    }

    /** JPEG at quality 95, like RIVeR desktop's extracted frames (`cv2.IMWRITE_JPEG_QUALITY, 95`). */
    fun writeJPEG(bitmap: Bitmap, file: File, quality: Int = 95) {
        val ok = BufferedOutputStream(FileOutputStream(file)).use { bitmap.compress(Bitmap.CompressFormat.JPEG, quality, it) }
        if (!ok) throw Failure.Encode(file.name)
    }

    fun writePNG(bitmap: Bitmap, file: File) {
        val ok = BufferedOutputStream(FileOutputStream(file)).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        if (!ok) throw Failure.Encode(file.name)
    }

    /**
     * PNG through `Bitmap.compress` (deflate-compressed, unlike the core's stored-block `PNG.encode`), keeping the
     * colour under alpha 0. Falls back to the core encoder if Android refuses the image.
     */
    fun writePNG(image: RGBAImage, file: File) {
        try {
            val bmp = bitmap(image)
            try {
                writePNG(bmp, file)
                return
            } finally {
                bmp.recycle()
            }
        } catch (e: Exception) {
            AppLog.error("Android no pudo escribir ${file.name}: $e; se usa el codificador del núcleo")
        }
        file.writeBytes(PNG.encode(image))
    }

    /** Gray 0…255 buffer as an opaque RGBA image (synthetic frames, previews). */
    fun rgba(gray: GrayImage): RGBAImage {
        val n = gray.width * gray.height
        val bytes = ByteArray(n * 4) { 255.toByte() }
        for (i in 0 until n) {
            val v = gray.pixels[i]
            val b = if (v <= 0f) 0 else if (v >= 255f) 255 else v.roundToInt()
            bytes[4 * i] = b.toByte()
            bytes[4 * i + 1] = b.toByte()
            bytes[4 * i + 2] = b.toByte()
        }
        return RGBAImage(gray.width, gray.height, bytes)
    }

    /** Mean luminance (0–255) of an image, on a 64-pixel-wide downscaled copy (cheap "is it black?" check). */
    fun meanLuminance(bitmap: Bitmap): Double {
        if (bitmap.width <= 0 || bitmap.height <= 0) return 0.0
        val w = 64
        val h = max(1, (w.toDouble() * bitmap.height / bitmap.width).roundToInt())
        val small = rgba(bitmap, w, h)
        var sum = 0.0
        for (i in 0 until w * h) {
            val r = (small.bytes[4 * i].toInt() and 0xFF).toDouble()
            val g = (small.bytes[4 * i + 1].toInt() and 0xFF).toDouble()
            val b = (small.bytes[4 * i + 2].toInt() and 0xFF).toDouble()
            sum += 0.299 * r + 0.587 * g + 0.114 * b
        }
        return sum / (w * h)
    }

    /** Pixel size of an image file without decoding it (stored pixel grid, EXIF orientation ignored). */
    fun pixelSize(file: File): Pair<Int, Int>? {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, options)
        if (options.outWidth <= 0 || options.outHeight <= 0) return null
        return Pair(options.outWidth, options.outHeight)
    }

    /** Downscaled copy for display (longest side ≤ [maxPixel]), decoded with `inSampleSize`. */
    fun thumbnailBitmap(file: File, maxPixel: Int = 2048): Bitmap? {
        val (w, h) = pixelSize(file) ?: return null
        val longest = max(w, h)
        var sample = 1
        while (longest / (sample * 2) >= maxPixel) sample *= 2
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = BitmapFactory.decodeFile(file.path, options) ?: return null
        val longestDecoded = max(decoded.width, decoded.height)
        if (longestDecoded <= maxPixel) return decoded
        val s = maxPixel.toDouble() / longestDecoded
        val scaled = Bitmap.createScaledBitmap(
            decoded, max(1, (decoded.width * s).roundToInt()), max(1, (decoded.height * s).roundToInt()), true,
        )
        if (scaled !== decoded) decoded.recycle()
        return scaled
    }

    /** [thumbnailBitmap] for Compose. */
    fun thumbnail(file: File, maxPixel: Int = 2048): ImageBitmap? = try {
        thumbnailBitmap(file, maxPixel)?.asImageBitmap()
    } catch (e: OutOfMemoryError) {
        AppLog.error("Sin memoria al leer ${file.name}")
        null
    }
}
