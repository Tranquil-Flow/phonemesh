package org.phonemesh.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A by-hand check of the real upscaling models, not part of the usual suite's meaning: it does nothing unless the
 * models (ONNX and their ncnn copies) were copied into the app's `files/upscalers` on the emulator first. Then each
 * model runs on both engines at several sizes, and the graphics chip's picture must match the processor's.
 */
@RunWith(AndroidJUnit4::class)
class PictureGpuModelsCheck {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun everyNcnnCopyMatchesItsOnnxModelAtSeveralSizes() {
        val models = PictureLooks.folders(context).flatMap { dir ->
            dir.listFiles { f -> f.name.endsWith(".onnx") }?.filter { PictureLooks.gpuFiles(it) != null }.orEmpty()
        }
        android.util.Log.i("PhonemeshPicture", "check: ${models.size} models with ncnn copies")
        if (models.isEmpty()) return
        assertTrue(NativeUpscale.loaded)
        assertTrue(NativeGpu.loaded)
        val allFaults = mutableListOf<String>()
        for (onnx in models) {
            val (param, bin) = PictureLooks.gpuFiles(onnx)!!
            val cpu = NativeUpscale.open(onnx.path, 2, NativeUpscale.CPU)
            assertNotEquals(NativeUpscale.lastError(), 0L, cpu)
            // Each ncnn path, against ONNX Runtime on the processor: [vulkan, fp16].
            val paths = listOf(false to false, false to true, true to false, true to true).associateWith { (vulkan, fp16) ->
                NativeGpu.open(param.path, bin.path, 2, vulkan, fp16).also { assertNotEquals(NativeGpu.lastError(), 0L, it) }
            }
            try {
                val faults = mutableListOf<String>()
                for ((h, w) in listOf(24 to 24, 40 to 56, 37 to 29)) {
                    val input = FloatArray(3 * h * w) { ((it * 37) % 101) / 100f }
                    val a = checkNotNull(NativeUpscale.run(cpu, input, h, w)) { NativeUpscale.lastError() }
                    val line = paths.entries.joinToString { (path, handle) ->
                        val b = checkNotNull(NativeGpu.run(handle, input, h, w)) { "${onnx.name} ${w}x$h: ${NativeGpu.lastError()}" }
                        val worst = a.indices.maxOf { kotlin.math.abs(a[it] - b[it]) }
                        val name = (if (path.first) "vulkan" else "cpu") + (if (path.second) "-fp16" else "-fp32")
                        // Only the conversion is judged here: the emulator's software Vulkan (llvmpipe) gives wrong
                        // pictures for these models in any mode, which says nothing about a phone's graphics chip.
                        if (!path.first && !path.second && worst > 0.05f) faults += "${onnx.name} ${w}x$h $name $worst"
                        "$name $worst"
                    }
                    android.util.Log.i("PhonemeshPicture", "check ${onnx.name} ${w}x$h: $line")
                }
                allFaults += faults
            } finally {
                NativeUpscale.close(cpu)
                paths.values.forEach { NativeGpu.close(it) }
            }
        }
        assertTrue(allFaults.joinToString(), allFaults.isEmpty())

        // The engine trial must keep away from the emulator's wrong Vulkan pictures with the real models.
        context.getSharedPreferences("picture_looks", android.content.Context.MODE_PRIVATE).edit().clear().commit()
        val photo = android.graphics.Bitmap.createBitmap(48, 36, android.graphics.Bitmap.Config.ARGB_8888).apply {
            for (x in 0 until 48) for (y in 0 until 36) setPixel(x, y, android.graphics.Color.rgb(x * 5, y * 7, (x + y) * 3))
        }
        for (look in listOf(PictureLook.AI_QUICK, PictureLook.AI_LARGE)) {
            if (!PictureLooks.available(context, look)) continue
            PictureLooks.make(context, look, photo)
            val kept = context.getSharedPreferences("picture_looks", android.content.Context.MODE_PRIVATE).all
                .filterKeys { it.startsWith("engine3:${look.model}") }.values.single() as Int
            android.util.Log.i("PhonemeshPicture", "check: trial kept engine $kept for ${look.model}")
            assertTrue("no Vulkan path with a wrong picture is kept", kept == PictureLooks.ENGINE_CPU || kept == PictureLooks.ENGINE_NCNN_CPU)
            PictureLooks.release()
        }
    }
}
