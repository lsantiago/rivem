package org.rivem.core.piv

import org.rivem.core.imaging.GrayImage
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.truncate

/**
 * Frame preprocessing before PIV, as `river/core/image_preprocessing.py` → `preprocess_image`:
 * grayscale (8-bit) → optional background subtraction → optional CLAHE.
 *
 * Upstream works on the `uint8` images OpenCV decodes; `GrayImage` carries Float samples, so every frame is first
 * quantised to 0…255 (round half to even, clamped). With integer-valued input this is the identity.
 *
 * Swift `[UInt8]` buffers are `ByteArray`s holding unsigned bytes: read them with `toInt() and 0xFF`.
 */
object PIVPreprocessing {
    /**
     * Full chain for one frame. `background` (if any) must have the frame's size; it is used only when
     * `parameters.removeBackground` is true.
     */
    fun preprocess(image: GrayImage, parameters: PIVParameters, background: GrayImage?): GrayImage {
        var px = quantize(image)
        if (parameters.removeBackground && background != null &&
            background.width == image.width && background.height == image.height
        ) {
            val b = quantize(background)
            subtractBackground(px, b)
        }
        if (parameters.clahe) {
            px = clahe(px, image.width, image.height, parameters.clipLimit)
        }
        val out = FloatArray(px.size)
        for (i in px.indices) out[i] = (px[i].toInt() and 0xFF).toFloat()
        return GrayImage(image.width, image.height, out)
    }

    /** 0…255 bytes, round half to even (like `saturate_cast<uchar>`), NaN → 0. */
    fun quantize(image: GrayImage): ByteArray {
        val src = image.pixels
        val out = ByteArray(src.size)
        for (i in src.indices) {
            val v: Float = src[i]
            if (!(v > 0)) {
                out[i] = 0
                continue
            }
            if (v >= 255) {
                out[i] = 255.toByte()
                continue
            }
            out[i] = Math.rint(v.toDouble()).toInt().toByte()
        }
        return out
    }

    /** `cv2.subtract(img, bg)` on `uint8`: saturating `max(img − bg, 0)`, in place. */
    fun subtractBackground(px: ByteArray, background: ByteArray) {
        require(px.size == background.size) { "background size mismatch" }
        for (i in px.indices) {
            val a = px[i].toInt() and 0xFF
            val b = background[i].toInt() and 0xFF
            px[i] = if (a > b) (a - b).toByte() else 0
        }
    }

    /**
     * CLAHE exactly as OpenCV's `createCLAHE(clipLimit).apply()` on 8-bit images (default 8×8 tiles), checked
     * bit-exact against OpenCV 4.11 (including sizes not divisible by the tile count):
     * - if the size is not a multiple of the tile grid, the image is extended right/bottom with BORDER_REFLECT_101
     *   by `tiles − size % tiles` for computing the histograms;
     * - per tile: histogram, clip at `max(Int(clipLimit · tileArea / 256), 1)`, redistribute the excess
     *   (uniform batch plus a strided residual), cumulative LUT `round(cdf · 255 / tileArea)` in Float;
     * - bilinear interpolation between the four neighbouring tile LUTs in Float, rounded half to even.
     */
    fun clahe(src: ByteArray, width: Int, height: Int, clipLimit: Double, tiles: Int = 8): ByteArray {
        val w = width
        val h = height
        require(src.size == w * h) { "CLAHE size mismatch" }
        if (w == 0 || h == 0) return src.copyOf()
        val tx = tiles
        val ty = tiles
        val extW: Int = if (w % tx == 0) w else w + tx - (w % tx)
        val extH: Int = if (h % ty == 0) h else h + ty - (h % ty)
        val tw = extW / tx
        val th = extH / ty
        val area = tw * th
        val lutScale: Float = (255.0 / area.toDouble()).toFloat()
        var clip = 0
        if (clipLimit > 0) {
            clip = max((clipLimit * area.toDouble() / 256.0).toInt(), 1)
        }
        // Reflect-101 index maps for the extension (clamped for degenerate 1-pixel images).
        val colMap = IntArray(extW) { reflect101(it, w) }
        val rowMap = IntArray(extH) { reflect101(it, h) }

        val lut = ByteArray(tx * ty * 256)
        val hist = IntArray(256)
        for (j in 0 until ty) {
            for (i in 0 until tx) {
                java.util.Arrays.fill(hist, 0)
                for (yy in (j * th) until ((j + 1) * th)) {
                    val row = rowMap[yy] * w
                    for (xx in (i * tw) until ((i + 1) * tw)) {
                        hist[src[row + colMap[xx]].toInt() and 0xFF] += 1
                    }
                }
                if (clip > 0) {
                    var clipped = 0
                    for (k in 0 until 256) {
                        if (hist[k] > clip) {
                            clipped += hist[k] - clip
                            hist[k] = clip
                        }
                    }
                    val batch = clipped / 256
                    var residual = clipped - batch * 256
                    for (k in 0 until 256) hist[k] += batch
                    if (residual != 0) {
                        val residualStep = max(256 / residual, 1)
                        var k = 0
                        while (k < 256 && residual > 0) {
                            hist[k] += 1
                            k += residualStep
                            residual -= 1
                        }
                    }
                }
                var sum = 0
                val base = (j * tx + i) * 256
                for (k in 0 until 256) {
                    sum += hist[k]
                    val v: Float = sum.toFloat() * lutScale
                    lut[base + k] = saturateByte(v).toByte()
                }
            }
        }

        // Interpolation tables along x.
        val invTW: Float = 1.0f / tw.toFloat()
        val invTH: Float = 1.0f / th.toFloat()
        val ind1 = IntArray(w)
        val ind2 = IntArray(w)
        val xa = FloatArray(w)
        val xa1 = FloatArray(w)
        for (x in 0 until w) {
            val txf: Float = x.toFloat() * invTW - 0.5f
            var t1 = floor(txf).toInt()
            var t2 = t1 + 1
            val a: Float = txf - t1.toFloat()
            xa[x] = a
            xa1[x] = 1.0f - a
            t1 = max(t1, 0)
            t2 = min(t2, tx - 1)
            ind1[x] = t1 * 256
            ind2[x] = t2 * 256
        }
        val dst = ByteArray(w * h)
        for (y in 0 until h) {
            val tyf: Float = y.toFloat() * invTH - 0.5f
            var t1 = floor(tyf).toInt()
            var t2 = t1 + 1
            val ya: Float = tyf - t1.toFloat()
            val ya1: Float = 1.0f - ya
            t1 = max(t1, 0)
            t2 = min(t2, ty - 1)
            val p1 = t1 * tx * 256
            val p2 = t2 * tx * 256
            val row = y * w
            for (x in 0 until w) {
                val v = src[row + x].toInt() and 0xFF
                val a: Float = (lut[p1 + ind1[x] + v].toInt() and 0xFF).toFloat()
                val b: Float = (lut[p1 + ind2[x] + v].toInt() and 0xFF).toFloat()
                val c: Float = (lut[p2 + ind1[x] + v].toInt() and 0xFF).toFloat()
                val e: Float = (lut[p2 + ind2[x] + v].toInt() and 0xFF).toFloat()
                val top: Float = a * xa1[x] + b * xa[x]
                val bottom: Float = c * xa1[x] + e * xa[x]
                val res: Float = top * ya1 + bottom * ya
                dst[row + x] = saturateByte(res).toByte()
            }
        }
        return dst
    }

    /** Rounds half to even and saturates to 0…255 (Swift returns `UInt8`; here the 0…255 value as Int). */
    internal fun saturateByte(v: Float): Int {
        val r = Math.rint(v.toDouble())
        if (!(r > 0)) return 0
        if (r >= 255) return 255
        return r.toInt()
    }

    internal fun reflect101(i: Int, n: Int): Int {
        if (n <= 1) return 0
        var j = i
        if (j >= n) j = 2 * n - 2 - j
        return min(max(j, 0), n - 1)
    }
}

/**
 * Background for `remove_background`: `np.uint8(mean of all frames)` (`image_preprocessing.calculate_average`),
 * i.e. the pixel-wise mean of the 8-bit frames truncated towards zero. Accumulates one frame at a time.
 *
 * Deviation: upstream crashes on an unreadable frame; here the caller simply skips it (the mean is over the frames
 * actually added, see [PIVSequence]).
 */
class PIVBackgroundAccumulator(val width: Int, val height: Int) {
    var count: Int = 0
        private set
    private val sum = DoubleArray(width * height)

    /** Adds a frame (quantised to 8 bits like a decoded JPEG). Returns false if the size does not match. */
    fun add(image: GrayImage): Boolean {
        if (!(image.width == width && image.height == height)) return false
        val px = PIVPreprocessing.quantize(image)
        val s = sum
        for (i in px.indices) s[i] += (px[i].toInt() and 0xFF).toDouble()
        count += 1
        return true
    }

    /** Truncated mean (0…255); null if no frame was added. */
    fun background(): GrayImage? {
        if (count <= 0) return null
        val out = FloatArray(width * height)
        val n = count.toDouble()
        for (i in out.indices) {
            out[i] = min(255.0, truncate(sum[i] / n)).toFloat()
        }
        return GrayImage(width, height, out)
    }
}
