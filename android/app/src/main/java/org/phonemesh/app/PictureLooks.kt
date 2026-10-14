package org.phonemesh.app

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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

    /** [engine]: [CPU] or [XNNPACK]. 0 when the model cannot be opened ([lastError] says why). */
    @JvmStatic external fun open(path: String, threads: Int, engine: Int): Long
    @JvmStatic external fun run(handle: Long, input: FloatArray, height: Int, width: Int): FloatArray?
    @JvmStatic external fun close(handle: Long)
    @JvmStatic external fun lastError(): String

    const val CPU = 0
    const val XNNPACK = 1
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
                Log.i(TAG, "view ${look.key} ${source.width}x${source.height} -> ${w}x$h in ${it.millis} ms: " +
                    "engine ${engineName(openEngine)}, pieces of ${tileFor(context, look)} px, ${threads()} threads")
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

    /**
     * The part of the picture a model works on at a time. Overlapping pieces repeat work (48 px pieces nearly double
     * a large model's time), so pieces are as large as the phone's memory allows: a large model on a whole Maximum
     * picture peaks near 700 MB, on a 96 px piece near 320 MB. A picture smaller than a piece is done in one go.
     */
    fun tileFor(context: Context, look: PictureLook): Int {
        if (look.tile >= SMALL_MODEL_TILE) return look.tile
        val memory = ActivityManager.MemoryInfo().also { (context.getSystemService(ActivityManager::class.java)).getMemoryInfo(it) }.totalMem
        return when {
            memory >= 7L shl 30 -> 256
            memory >= 3L shl 30 -> 96
            else -> look.tile
        }
    }

    /** One model open at a time: the large ones take hundreds of MB while they work. */
    private var openPath: String? = null
    private var openHandle = 0L
    private var openEngine = NativeUpscale.CPU

    private fun session(context: Context, file: File): Long {
        if (openPath == file.path && openHandle != 0L) return openHandle
        if (openHandle != 0L) NativeUpscale.close(openHandle)
        openHandle = 0L
        openPath = null
        val (handle, engine) = fastest(context, file)
        openPath = file.path
        openHandle = handle
        openEngine = engine
        return handle
    }

    /**
     * The first time a model runs on this phone, both engines run one small piece and the faster is kept, for that
     * model on this phone from then on. XNNPACK is often faster on phone processors, but not always.
     */
    private fun fastest(context: Context, file: File): Pair<Long, Int> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val key = "engine:${file.name}:${file.length()}"
        val known = prefs.getInt(key, -1)
        if (known >= 0) {
            val handle = NativeUpscale.open(file.path, threads(), known)
            if (handle != 0L) return handle to known
        }
        var best = 0L to NativeUpscale.CPU
        var bestMillis = Long.MAX_VALUE
        for (engine in listOf(NativeUpscale.CPU, NativeUpscale.XNNPACK)) {
            val handle = NativeUpscale.open(file.path, threads(), engine)
            if (handle == 0L) {
                Log.i(TAG, "engine ${engineName(engine)} not available for ${file.name}: ${NativeUpscale.lastError()}")
                continue
            }
            val millis = trial(handle)
            Log.i(TAG, "engine ${engineName(engine)} on ${file.name}: ${millis} ms for a trial piece")
            if (millis < bestMillis) {
                if (best.first != 0L) NativeUpscale.close(best.first)
                best = handle to engine
                bestMillis = millis
            } else {
                NativeUpscale.close(handle)
            }
        }
        check(best.first != 0L) { NativeUpscale.lastError() }
        prefs.edit().putInt(key, best.second).apply()
        return best
    }

    /** One warm-up run, then a timed one, on a 64 x 64 piece of mid-grey; Long.MAX_VALUE when it fails. */
    private fun trial(handle: Long): Long {
        val input = FloatArray(3 * TRIAL * TRIAL) { 0.5f }
        if (NativeUpscale.run(handle, input, TRIAL, TRIAL) == null) return Long.MAX_VALUE
        val start = SystemClock.elapsedRealtime()
        if (NativeUpscale.run(handle, input, TRIAL, TRIAL) == null) return Long.MAX_VALUE
        return SystemClock.elapsedRealtime() - start
    }

    private fun engineName(engine: Int) = if (engine == NativeUpscale.XNNPACK) "XNNPACK" else "CPU"

    /**
     * AI views already made, kept in the app's cache (Android may clear it) as JPEG, so opening a picture again or
     * going back to a view is instant. "<message>-<look>-<millis>.jpg"; at most [CACHE_BYTES], the oldest go first.
     */
    private fun cacheDir(context: Context) = File(context.cacheDir, "views")

    fun cached(context: Context, messageId: Long, look: PictureLook): Made? {
        if (!look.ai) return null
        val file = cacheDir(context).listFiles()?.firstOrNull { it.name.startsWith("$messageId-${look.key}-") } ?: return null
        val millis = file.name.removePrefix("$messageId-${look.key}-").removeSuffix(".jpg").toLongOrNull() ?: return null
        val bitmap = BitmapFactory.decodeFile(file.path) ?: return null
        file.setLastModified(System.currentTimeMillis())
        return Made(bitmap, millis)
    }

    fun keep(context: Context, messageId: Long, look: PictureLook, made: Made) {
        if (!look.ai) return
        runCatching {
            val dir = cacheDir(context).apply { mkdirs() }
            val file = File(dir, "$messageId-${look.key}-${made.millis}.jpg")
            val tmp = File(dir, file.name + ".tmp")
            tmp.outputStream().use { check(made.bitmap.compress(Bitmap.CompressFormat.JPEG, CACHE_QUALITY, it)) }
            check(tmp.renameTo(file))
            val all = dir.listFiles()?.sortedBy { it.lastModified() } ?: return
            var total = all.sumOf { it.length() }
            for (old in all) {
                if (total <= CACHE_BYTES) break
                total -= old.length()
                old.delete()
            }
        }.onFailure { Log.w(TAG, "view not kept", it) }
    }

    /** A deleted picture's views go with it. */
    fun forget(context: Context, messageId: Long) {
        cacheDir(context).listFiles()?.filter { it.name.startsWith("$messageId-") }?.forEach { it.delete() }
    }

    private const val PREFS = "picture_looks"
    private const val TRIAL = 64
    private const val SMALL_MODEL_TILE = 128
    private const val CACHE_BYTES = 50L shl 20
    private const val CACHE_QUALITY = 95

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
        val handle = session(context, file)
        val w = source.width
        val h = source.height
        val src = IntArray(w * h).also { source.getPixels(it, 0, w, 0, 0, w, h) }
        val outW = w * SCALE
        val outH = h * SCALE
        val out = IntArray(outW * outH)
        val tiles = PictureScale.tiles(w, h, tileFor(context, look), PAD)
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
