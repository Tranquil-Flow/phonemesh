package org.phonemesh.app

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The ways to show a picture larger, on the device: the ONNX Runtime bridge with a tiny model (the real ones are copied
 * onto test phones by hand), the AI path whole and in tiles, and the views that need no model.
 */
@RunWith(AndroidJUnit4::class)
class PictureLooksTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val testContext = InstrumentationRegistry.getInstrumentation().context
    private val placed = mutableListOf<File>()

    @After fun tearDown() {
        PictureLooks.release()
        placed.forEach { it.delete() }
    }

    /** The tiny test model under [name], in the app's private model folder. */
    private fun place(name: String, asset: String = "tiny-test.onnx"): File {
        val file = File(PictureLooks.folders(context).last(), name)
        file.parentFile!!.mkdirs()
        testContext.assets.open(asset).use { input -> file.outputStream().use { input.copyTo(it) } }
        placed += file
        return file
    }

    /** The tiny model and its ncnn copy, as [model]. */
    private fun placeWithNcnn(model: String): File {
        val onnx = place(model)
        val base = model.removeSuffix(".onnx")
        place("$base.ncnn.param", "tiny-test.ncnn.param")
        place("$base.ncnn.bin", "tiny-test.ncnn.bin")
        return onnx
    }

    private fun photo(w: Int, h: Int) = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
        for (x in 0 until w) for (y in 0 until h) setPixel(x, y, Color.rgb((x * 9) % 256, (y * 7) % 256, (x + y) % 256))
    }

    @Test fun theBridgeRunsAModelThroughTheAppsOwnOnnxRuntime() {
        assertTrue("sherpa-onnx's runtime and the bridge load", NativeUpscale.loaded)
        val file = place("bridge-test.onnx")
        val handle = NativeUpscale.open(file.path, 2, NativeUpscale.CPU)
        assertNotEquals(NativeUpscale.lastError(), 0L, handle)
        try {
            val input = FloatArray(3 * 6 * 8) { (it % 17) / 17f }
            val out = checkNotNull(NativeUpscale.run(handle, input, 6, 8)) { NativeUpscale.lastError() }
            assertEquals("four times larger each way", 3 * 24 * 32, out.size)
            assertTrue(out.all { it.isFinite() })
            assertNull("a wrong size is refused, not a crash", NativeUpscale.run(handle, FloatArray(5), 6, 8))
            assertTrue(NativeUpscale.lastError().isNotEmpty())
        } finally {
            NativeUpscale.close(handle)
        }
        assertEquals("a missing model says so", 0L, NativeUpscale.open(File(context.cacheDir, "none.onnx").path, 1, NativeUpscale.CPU))
    }

    @Test fun anAiLookIsMadeWholeOrInTilesAtFourTimesTheSize() {
        assertFalse("no model, no AI look", PictureLooks.available(context, PictureLook.AI_QUICK))
        place(PictureLook.AI_QUICK.model!!)
        place(PictureLook.AI_LARGE.model!!)
        assertTrue(PictureLooks.available(context, PictureLook.AI_QUICK))
        val source = photo(100, 70)
        val steps = mutableListOf<Int>()
        val whole = PictureLooks.make(context, PictureLook.AI_QUICK, source) { steps += it }
        assertEquals(400, whole.bitmap.width)
        assertEquals(280, whole.bitmap.height)
        assertEquals(100, steps.last())
        val tiled = PictureLooks.make(context, PictureLook.AI_LARGE, source)
        assertEquals("tiles join into the whole picture", 400 to 280, tiled.bitmap.width to tiled.bitmap.height)
        assertTrue(PictureScaleTiles.count(100, 70, PictureLook.AI_LARGE.tile) > 1)
    }

    @Test fun aCancelledViewStopsBetweenPiecesAndTheNextOneRuns() {
        place(PictureLook.AI_LARGE.model!!)
        place(PictureLook.AI_QUICK.model!!)
        val source = photo(200, 150)
        val pieces = PictureScaleTiles.count(200, 150, PictureLook.AI_LARGE.tile)
        assertTrue(pieces > 4)
        val before = PictureLooks.Job().apply { cancelled = true }
        var started = false
        try {
            PictureLooks.make(context, PictureLook.AI_QUICK, source, before, started = { started = true })
            fail("a request cancelled before it starts never runs")
        } catch (expected: PictureLooks.Cancelled) {
        }
        assertFalse(started)
        val job = PictureLooks.Job()
        val steps = mutableListOf<Int>()
        try {
            PictureLooks.make(context, PictureLook.AI_LARGE, source, job) { percent ->
                steps += percent
                if (steps.size == 2) job.cancelled = true
            }
            fail("a cancelled view stops")
        } catch (expected: PictureLooks.Cancelled) {
        }
        assertEquals("it stops at the next piece, not at the end", 2, steps.size)
        // The lock is free again: the next request runs, and its clock counts only its own work.
        val next = PictureLooks.make(context, PictureLook.AI_QUICK, source)
        assertEquals(800, next.bitmap.width)
        assertTrue(next.millis >= 0)
    }

    @Test fun withoutAnNcnnCopyThereIsNoTrialAndNoSettingUp() {
        val prefs = context.getSharedPreferences("picture_looks", android.content.Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val onnx = place(PictureLook.AI_QUICK.model!!)
        assertNull(PictureLooks.gpuFiles(onnx))
        assertFalse(PictureLooks.needsTrial(context, PictureLook.AI_QUICK))
        val steps = mutableListOf<Int>()
        assertEquals(160, PictureLooks.make(context, PictureLook.AI_QUICK, photo(40, 30), preparing = { steps += it }).bitmap.width)
        assertEquals("nothing to choose between", emptyList<Int>(), steps)
        assertTrue(prefs.all.isEmpty())
    }

    @Test fun theOneTimeTrialSaysHowFarItIsThenNeverRunsAgain() {
        val prefs = context.getSharedPreferences("picture_looks", android.content.Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val onnx = placeWithNcnn(PictureLook.AI_QUICK.model!!)
        assertNotNull(PictureLooks.gpuFiles(onnx))
        assertTrue(PictureLooks.needsTrial(context, PictureLook.AI_QUICK))
        val steps = mutableListOf<Int>()
        var startedAfterTrial = false
        val made = PictureLooks.make(context, PictureLook.AI_QUICK, photo(40, 30),
            started = { startedAfterTrial = steps.lastOrNull() == 100 }, preparing = { steps += it })
        assertEquals(160, made.bitmap.width)
        assertEquals("one step per engine, from 0 to 100", listOf(0, 25, 50, 75, 100), steps)
        assertTrue("the view's own clock starts after the trial", startedAfterTrial)
        val kept = prefs.all.entries.single { it.key.startsWith("engine3:${PictureLook.AI_QUICK.model}") }.value as Int
        assertTrue(kept in setOf(PictureLooks.ENGINE_CPU, PictureLooks.ENGINE_GPU, PictureLooks.ENGINE_GPU32, PictureLooks.ENGINE_NCNN_CPU))
        assertFalse(PictureLooks.needsTrial(context, PictureLook.AI_QUICK))
        PictureLooks.release()
        steps.clear()
        assertEquals(160, PictureLooks.make(context, PictureLook.AI_QUICK, photo(40, 30), preparing = { steps += it }).bitmap.width)
        assertEquals("the remembered engine is used without a new trial", emptyList<Int>(), steps)
    }

    @Test fun theGraphicsChipBridgeLoadsAndSaysWhetherItHasADevice() {
        assertTrue("ncnn and the bridge are in the app", NativeGpu.loaded)
        // The emulator may or may not offer Vulkan; either way the bridge answers instead of crashing.
        val device = NativeGpu.device()
        if (device.isEmpty()) assertTrue(NativeGpu.lastError().isNotEmpty())
    }

    @Test fun aViewMadeOnceIsKeptUntilItsPictureGoes() {
        place(PictureLook.AI_QUICK.model!!)
        PictureLooks.forget(context, 4242)
        assertNull(PictureLooks.cached(context, 4242, PictureLook.AI_QUICK))
        val made = PictureLooks.make(context, PictureLook.AI_QUICK, photo(40, 30))
        PictureLooks.keep(context, 4242, PictureLook.AI_QUICK, made)
        val again = checkNotNull(PictureLooks.cached(context, 4242, PictureLook.AI_QUICK))
        assertEquals(160 to 120, again.bitmap.width to again.bitmap.height)
        assertEquals("the time it took when made", made.millis, again.millis)
        assertNull("views without a model are not kept", PictureLooks.cached(context, 4242, PictureLook.SMOOTH))
        PictureLooks.forget(context, 4242)
        assertNull(PictureLooks.cached(context, 4242, PictureLook.AI_QUICK))
    }

    @Test fun piecesAreAsLargeAsThePhonesMemoryAllows() {
        assertEquals("the small models keep their pieces", PictureLook.AI_QUICK.tile, PictureLooks.tileFor(context, PictureLook.AI_QUICK))
        assertTrue(PictureLooks.tileFor(context, PictureLook.AI_LARGE) in setOf(PictureLook.AI_LARGE.tile, 96, 256))
    }

    @Test fun theViewsWithoutAModelAreFourTimesLarger() {
        val source = photo(24, 18)
        for (look in listOf(PictureLook.EXACT, PictureLook.SMOOTH, PictureLook.SHARPER)) {
            assertTrue(PictureLooks.available(context, look))
            val made = PictureLooks.make(context, look, source)
            assertEquals(look.name, 96 to 72, made.bitmap.width to made.bitmap.height)
        }
        assertEquals("exact keeps each pixel", source.getPixel(3, 2), PictureLooks.make(context, PictureLook.EXACT, source).bitmap.getPixel(13, 9))
    }
}

/** How many tiles a look's model works through. */
private object PictureScaleTiles {
    fun count(w: Int, h: Int, tile: Int) = org.phonemesh.core.PictureScale.tiles(w, h, tile, 8).size
}
