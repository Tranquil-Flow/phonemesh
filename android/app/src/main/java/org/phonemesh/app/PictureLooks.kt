package org.phonemesh.app

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import org.phonemesh.core.PictureScale
import java.io.File

/** The ONNX Runtime bridge (src/main/cpp): one model file in, a picture four times larger out. */
object NativeUpscale {
    /** Loads sherpa-onnx's ONNX Runtime first; the bridge finds it by name. False when either is missing. */
    val loaded: Boolean by lazy {
        runCatching { System.loadLibrary("onnxruntime"); System.loadLibrary("phonemesh_upscale") }.isSuccess
    }

    @JvmStatic external fun open(path: String, threads: Int): Long
    @JvmStatic external fun run(handle: Long, input: FloatArray, height: Int, width: Int): FloatArray?
    @JvmStatic external fun close(handle: Long)
    @JvmStatic external fun lastError(): String
}

/**
 * Ways to show a received picture four times larger, all on this phone: the exact pixels, smoothed, sharper (no AI),
 * and AI models that may invent detail. [model] is the file an AI look needs; [tile] the part of the picture it works
 * on at a time, small for the large models so they stay within a phone's memory.
 */
enum class PictureLook(val key: String, val label: Int, val model: String?, val tile: Int) {
    EXACT("exact", R.string.look_exact, null, 0),
    SMOOTH("smooth", R.string.look_smooth, null, 0),
    SHARPER("sharper", R.string.look_sharper, null, 0),
    AI_QUICK("ai_quick", R.string.look_ai_quick, "realesr-x4v3-strong.onnx", 128),
    AI_GENTLE("ai_gentle", R.string.look_ai_gentle, "realesr-x4v3-gentle.onnx", 128),
    AI_LARGE("ai_large", R.string.look_ai_large, "realesrgan-x4plus.onnx", 48),
    AI_BSRGAN("ai_bsrgan", R.string.look_ai_bsrgan, "bsrgan-x4.onnx", 48);

    val ai: Boolean get() = model != null
}

object PictureLooks {
    const val SCALE = 4
    private const val PAD = 8

    /** Where AI models are looked for: the app's own folder on the phone's storage, then its private files. */
    fun folders(context: Context): List<File> =
        listOfNotNull(context.getExternalFilesDir(null), context.filesDir).map { File(it, "upscalers") }

    fun modelFile(context: Context, look: PictureLook): File? =
        look.model?.let { name -> folders(context).map { File(it, name) }.firstOrNull { it.isFile && it.length() > 0 && it.canRead() } }

    fun available(context: Context, look: PictureLook): Boolean =
        look.model == null || (NativeUpscale.loaded && modelFile(context, look) != null)

    /** [millis] counts only the work, not any wait for the view before it to stop. */
    class Made(val bitmap: Bitmap, val millis: Long)

    /** One request for a view. Setting [cancelled] stops it before it starts, or between two pieces of the picture. */
    class Job {
        @Volatile var cancelled = false
    }

    /** Thrown by [make] when its [Job] was cancelled; not a failure. */
    class Cancelled : RuntimeException("cancelled")

    /**
     * Makes [look] of [source] off the main thread; throws with the reason when it cannot, or [Cancelled]. Only one AI
     * view is made at a time: a later request waits until the one before it stops, then [started] is called and the
     * clock starts. [progress] gets 0-100.
     */
    fun make(
        context: Context, look: PictureLook, source: Bitmap, job: Job = Job(), started: () -> Unit = {}, progress: (Int) -> Unit = {},
    ): Made {
        val w = source.width * SCALE
        val h = source.height * SCALE
        if (look.ai) return synchronized(lock) {
            if (job.cancelled) throw Cancelled()
            started()
            val start = SystemClock.elapsedRealtime()
            val bitmap = ai(context, look, source, job, progress)
            Made(bitmap, SystemClock.elapsedRealtime() - start).also {
                Log.i(TAG, "view ${look.key} ${source.width}x${source.height} -> ${w}x$h in ${it.millis} ms, ${threads()} threads")
            }
        }
        started()
        val start = SystemClock.elapsedRealtime()
        val bitmap = when (look) {
            PictureLook.EXACT -> Bitmap.createScaledBitmap(source, w, h, false)
            PictureLook.SMOOTH -> Bitmap.createScaledBitmap(source, w, h, true)
            else -> {
                val big = Bitmap.createScaledBitmap(source, w, h, true)
                val pixels = IntArray(w * h).also { big.getPixels(it, 0, w, 0, 0, w, h) }
                Bitmap.createBitmap(PictureScale.unsharp(pixels, w, h, radius = 3, amount = 1.0f, threshold = 3), w, h, Bitmap.Config.ARGB_8888)
            }
        }
        return Made(bitmap, SystemClock.elapsedRealtime() - start)
    }

    /** Held while an AI view is made, and while a model is opened or let go. */
    private val lock = Any()
    private const val TAG = "PhonemeshPicture"

    /** One model open at a time: the large ones take hundreds of MB while they work. */
    private var openPath: String? = null
    private var openHandle = 0L

    private fun session(file: File): Long {
        if (openPath == file.path && openHandle != 0L) return openHandle
        if (openHandle != 0L) NativeUpscale.close(openHandle)
        openHandle = 0L
        openPath = null
        val handle = NativeUpscale.open(file.path, threads())
        check(handle != 0L) { NativeUpscale.lastError() }
        openPath = file.path
        openHandle = handle
        return handle
    }

    /** Lets go of the open model (when the picture screen closes), giving its memory back; waits for a running piece. */
    fun release() = synchronized(lock) {
        if (openHandle != 0L) NativeUpscale.close(openHandle)
        openHandle = 0L
        openPath = null
    }

    private fun threads(): Int = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)

    /** Called with [lock] held. */
    private fun ai(context: Context, look: PictureLook, source: Bitmap, job: Job, progress: (Int) -> Unit): Bitmap {
        check(NativeUpscale.loaded) { "the AI engine is not in this build" }
        val file = checkNotNull(modelFile(context, look)) { "model ${look.model} is not on this phone" }
        val handle = session(file)
        val w = source.width
        val h = source.height
        val src = IntArray(w * h).also { source.getPixels(it, 0, w, 0, 0, w, h) }
        val outW = w * SCALE
        val outH = h * SCALE
        val out = IntArray(outW * outH)
        val tiles = PictureScale.tiles(w, h, look.tile, PAD)
        tiles.forEachIndexed { index, t ->
            if (job.cancelled) {
                Log.i(TAG, "view ${look.key} stopped after ${index} of ${tiles.size} pieces")
                throw Cancelled()
            }
            progress(index * 100 / tiles.size)
            val plane = t.width * t.height
            val input = FloatArray(3 * plane)
            for (y in 0 until t.height) for (x in 0 until t.width) {
                val p = src[(t.y + y) * w + t.x + x]
                val o = y * t.width + x
                input[o] = ((p shr 16) and 255) / 255f
                input[plane + o] = ((p shr 8) and 255) / 255f
                input[2 * plane + o] = (p and 255) / 255f
            }
            val result = NativeUpscale.run(handle, input, t.height, t.width) ?: error(NativeUpscale.lastError())
            val tw = t.width * SCALE
            val plane4 = tw * t.height * SCALE
            for (y in 0 until t.innerHeight * SCALE) for (x in 0 until t.innerWidth * SCALE) {
                val o = (t.innerY * SCALE + y) * tw + t.innerX * SCALE + x
                out[((t.y + t.innerY) * SCALE + y) * outW + (t.x + t.innerX) * SCALE + x] =
                    (255 shl 24) or (level(result[o]) shl 16) or (level(result[plane4 + o]) shl 8) or level(result[2 * plane4 + o])
            }
        }
        progress(100)
        return Bitmap.createBitmap(out, outW, outH, Bitmap.Config.ARGB_8888)
    }

    private fun level(v: Float): Int = (v * 255f + 0.5f).toInt().coerceIn(0, 255)
}
