package org.rivem.core.piv

import org.rivem.core.imaging.GrayImage
import org.rivem.core.project.PIVResults
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.min

/** A frame pair that could not be processed (unreadable frame, size mismatch, processing error). */
data class PIVFailedPair(
    /** Pair index `i` (frames `i` and `i + 1`). */
    val index: Int,
    val reason: String,
)

data class PIVSequenceResult(
    /** Field for `piv_results.json`. Failed pairs are present as all-NaN rows (so pair indices keep matching frames). */
    var results: PIVResults,
    var failedPairs: List<PIVFailedPair>,
)

/**
 * Runs PIV over consecutive frame pairs (i, i+1), like `piv_pipeline.run_analyze_all` + `piv_loop`.
 *
 * Each frame is preprocessed ([PIVPreprocessing]: optional background subtraction, CLAHE) and each pair goes
 * through [PIVFFTMulti] (including the mask). `u_median` / `v_median` are `nanmedian` over pairs; `gradient` is
 * upstream's per-window gradient indicator of the last pass.
 *
 * Deviations from RIVeR 3.8.3:
 * - PLAN.md #5 / #11: a pair whose frames cannot be read or whose processing throws does not abort the run (upstream
 *   lost the whole analysis, or silently processed black frames). It is reported in `failedPairs` and stored as
 *   all-NaN (u, v, gradient), so it does not bias the medians.
 * - The background (`remove_background`) is the truncated mean of the frames that could be read (upstream crashed on
 *   an unreadable frame). Pass `background` to reuse a stored `background.jpg`.
 */
object PIVSequence {
    /**
     * Throws [PIVError] (`Cancelled`, `NoFrames`, `InvalidParameters`, `RegionTooSmall`).
     *
     * @param frameCount number of frames (pairs = frameCount − 1).
     * @param frame returns frame `i` (grayscale, 0–255), throwing if it cannot be read. Called from several threads
     *   at once when `parallel` is true.
     * @param bbox `bbox.json` `[x, y, w, h]` (null = whole frame).
     * @param mask `mask.npy` (null = everything analysed).
     * @param parallel processes pairs concurrently on a pool of `availableProcessors()` threads (Swift:
     *   `DispatchQueue.concurrentPerform`); results are identical to the sequential run (each pair writes its own slot).
     * @param progress `(pairsDone, pairsTotal)`, possibly from worker threads.
     * @param isCancelled polled before each frame/pair; when it returns true the run throws [PIVError.Cancelled].
     */
    fun run(
        frameCount: Int,
        frame: (Int) -> GrayImage,
        parameters: PIVParameters,
        bbox: List<Double>? = null,
        mask: PIVMask? = null,
        background: GrayImage? = null,
        parallel: Boolean = false,
        progress: ((Int, Int) -> Unit)? = null,
        isCancelled: (() -> Boolean)? = null,
    ): PIVSequenceResult {
        parameters.validate()
        if (frameCount < 2) throw PIVError.NoFrames()
        val cancelled: () -> Boolean = { isCancelled?.invoke() ?: false }

        // Reference size and background.
        var width = 0
        var height = 0
        var bg: GrayImage? = background
        if (parameters.removeBackground && bg == null) {
            var acc: PIVBackgroundAccumulator? = null
            for (i in 0 until frameCount) {
                if (cancelled()) throw PIVError.Cancelled()
                val img = tryFrame(frame, i) ?: continue
                if (acc == null) acc = PIVBackgroundAccumulator(img.width, img.height)
                acc.add(img)
            }
            val a = acc ?: throw PIVError.NoFrames()
            width = a.width
            height = a.height
            bg = a.background()
        } else {
            var found = false
            for (i in 0 until frameCount) {
                if (cancelled()) throw PIVError.Cancelled()
                val img = tryFrame(frame, i)
                if (img != null) {
                    width = img.width
                    height = img.height
                    found = true
                    break
                }
            }
            if (!found) throw PIVError.NoFrames()
        }
        val grid = PIVFFTMulti.outputGrid(width, height, parameters, bbox)
        val nodes = grid.rows * grid.columns
        val pairs = frameCount - 1
        val slots = PIVSlots(pairs)
        val bgImage = bg
        val refWidth = width
        val refHeight = height

        fun prepare(i: Int): GrayImage {
            val img = frame(i)
            if (!(img.width == refWidth && img.height == refHeight)) throw PIVError.ImageSizeMismatch()
            return PIVPreprocessing.preprocess(img, parameters, bgImage)
        }

        /** (field, error, prepared second frame). */
        fun process(i: Int, first: GrayImage?): Triple<PIVPairField?, String?, GrayImage?> {
            var second: GrayImage? = null
            try {
                val a: GrayImage = first ?: prepare(i)
                val b: GrayImage = prepare(i + 1)
                second = b
                val field = PIVFFTMulti.run(a, b, parameters, bbox, mask)
                if (!(field.rows == grid.rows && field.columns == grid.columns)) {
                    return Triple(null, "unexpected grid", second)
                }
                return Triple(field, null, second)
            } catch (e: Exception) {
                return Triple(null, describe(e), second)
            }
        }

        if (parallel) {
            val threads = min(pairs, Runtime.getRuntime().availableProcessors()).coerceAtLeast(1)
            val next = AtomicInteger(0)
            val pool = Executors.newFixedThreadPool(threads)
            try {
                val futures = (0 until threads).map {
                    pool.submit {
                        while (true) {
                            val i = next.getAndIncrement()
                            if (i >= pairs) break
                            if (slots.isCancelled || cancelled()) {
                                slots.cancel()
                                continue
                            }
                            val r = process(i, null)
                            val done = slots.store(i, r.first, r.second)
                            progress?.invoke(done, pairs)
                        }
                    }
                }
                // Rethrows a failure of the progress / isCancelled callbacks (process() catches everything else).
                for (f in futures) f.get()
            } finally {
                pool.shutdown()
                pool.awaitTermination(1, TimeUnit.MINUTES)
            }
            if (slots.isCancelled) throw PIVError.Cancelled()
        } else {
            var cached: GrayImage? = null
            for (i in 0 until pairs) {
                if (cancelled()) throw PIVError.Cancelled()
                val r = process(i, cached)
                cached = r.third
                val done = slots.store(i, r.first, r.second)
                progress?.invoke(done, pairs)
            }
        }

        // Assemble.
        val nanRow = DoubleArray(nodes) { Double.NaN }
        val u = ArrayList<DoubleArray>(pairs)
        val v = ArrayList<DoubleArray>(pairs)
        val gradient = ArrayList<DoubleArray>(pairs)
        val failed = ArrayList<PIVFailedPair>()
        for (i in 0 until pairs) {
            val f = slots.fields[i]
            if (f != null) {
                u.add(f.u)
                v.add(f.v)
                gradient.add(if (f.gradient.size == nodes) f.gradient else nanRow)
            } else {
                u.add(nanRow)
                v.add(nanRow)
                gradient.add(nanRow)
                failed.add(PIVFailedPair(i, slots.errors[i] ?: "unknown error"))
            }
        }
        val results = PIVResults(
            rows = grid.rows, columns = grid.columns, x = grid.x.toList(), y = grid.y.toList(),
            u = u.map { it.toList() }, v = v.map { it.toList() },
            uMedian = nanMedian(u, nodes).toList(), vMedian = nanMedian(v, nodes).toList(),
            gradient = gradient.map { it.toList() },
        )
        return PIVSequenceResult(results, failed)
    }

    /** Convenience over an in-memory frame list. */
    fun run(
        frames: List<GrayImage>,
        parameters: PIVParameters,
        bbox: List<Double>? = null,
        mask: PIVMask? = null,
        background: GrayImage? = null,
        parallel: Boolean = false,
        progress: ((Int, Int) -> Unit)? = null,
        isCancelled: (() -> Boolean)? = null,
    ): PIVSequenceResult {
        val list = frames.toList()
        return run(
            frameCount = list.size, frame = { i -> list[i] }, parameters = parameters, bbox = bbox, mask = mask,
            background = background, parallel = parallel, progress = progress, isCancelled = isCancelled,
        )
    }

    /** Swift `try? frame(i)`. */
    private fun tryFrame(frame: (Int) -> GrayImage, i: Int): GrayImage? =
        try {
            frame(i)
        } catch (e: Exception) {
            null
        }

    /** Swift `String(describing: error)`: the message of [PIVError] (its `description`), else the exception. */
    private fun describe(e: Exception): String = e.message ?: e.javaClass.simpleName

    /** `np.nanmedian(axis=pairs)`: NaN where every pair is NaN. */
    internal fun nanMedian(rows: List<DoubleArray>, nodes: Int): DoubleArray {
        val out = DoubleArray(nodes) { Double.NaN }
        val buf = DoubleArray(rows.size)
        for (k in 0 until nodes) {
            var n = 0
            for (r in rows) {
                val x = r[k]
                if (!x.isNaN()) buf[n++] = x
            }
            if (n == 0) continue
            java.util.Arrays.sort(buf, 0, n)
            out[k] = if (n % 2 == 1) buf[n / 2] else (buf[n / 2 - 1] + buf[n / 2]) / 2
        }
        return out
    }
}

/** Preallocated per-pair results shared by the workers (one slot per pair, guarded by a lock). */
internal class PIVSlots(count: Int) {
    private val lock = Any()
    val fields: Array<PIVPairField?> = arrayOfNulls(count)
    val errors: Array<String?> = arrayOfNulls(count)
    private var done = 0
    private var cancelledFlag = false

    /** Stores a result and returns how many pairs are done. */
    fun store(i: Int, field: PIVPairField?, error: String?): Int = synchronized(lock) {
        fields[i] = field
        errors[i] = if (field == null) (error ?: "unknown error") else null
        done += 1
        done
    }

    val isCancelled: Boolean get() = synchronized(lock) { cancelledFlag }

    fun cancel() {
        synchronized(lock) { cancelledFlag = true }
    }
}
