package org.phonemesh.app

import android.content.Context
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.Spinner
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.phonemesh.core.PackStore
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Fixture models only. This tests selection, not inference. */
@RunWith(AndroidJUnit4::class)
class ModelChoiceUiTest {
    private fun descendants(v:View):List<View> = listOf(v) +
        if(v is ViewGroup) (0 until v.childCount).flatMap { descendants(v.getChildAt(it)) } else emptyList()
    private fun archive(model:String):ByteArrayInputStream {
        val files=linkedMapOf("pack.properties" to "format=1\nlanguageTag=en-US\nwhisperLanguage=en\nlabel=Fixture $model\nrecognizerModel=whisper-$model\n")
        for(path in listOf("whisper/encoder.int8.onnx","whisper/decoder.int8.onnx","whisper/tokens.txt",
            "tts/model.onnx","tts/tokens.txt","tts/espeak-ng-data/phontab","tts/espeak-ng-data/phondata",
            "tts/espeak-ng-data/phonindex","tts/espeak-ng-data/intonations","tts/espeak-ng-data/lang/gmw/en")) files[path]="fixture"
        files["SHA256SUMS"]=files.map { (path,text) ->
            MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }+"  "+path
        }.joinToString("\n")
        val output=ByteArrayOutputStream()
        ZipOutputStream(output).use { zip -> files.forEach { (path,text) ->
            zip.putNextEntry(ZipEntry(path)); zip.write(text.toByteArray()); zip.closeEntry()
        } }
        return ByteArrayInputStream(output.toByteArray())
    }
    @Test fun canChooseTinyOrBaseAndRememberAcrossActivityRestart() {
        val inst=InstrumentationRegistry.getInstrumentation()
        val context=inst.targetContext
        val prefs=context.getSharedPreferences("MainActivity",Context.MODE_PRIVATE)
        val keys=listOf("language","pack","pack:en-US")
        val original=keys.associateWith { prefs.getString(it,null) }
        val store=PackStore(File(context.filesDir,"packs"))
        val tiny=store.install(archive("tiny"))
        val base=store.install(archive("base"))
        var activity:MainActivity?=null
        fun launch():MainActivity = inst.startActivitySync(Intent(context,MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        try {
            prefs.edit().putString("language","en-US").putString("pack:en-US",tiny.root.name).commit()
            activity=launch(); inst.waitForIdleSync()
            inst.runOnMainSync {
                val spinner=descendants(activity!!.window.decorView).first { it.contentDescription=="Offline model choice" } as Spinner
                assertTrue(spinner.selectedItem.toString().contains("Whisper Tiny"))
                val index=(0 until spinner.count).first { spinner.getItemAtPosition(it).toString().contains("Fixture base") }
                spinner.setSelection(index)
            }
            inst.waitForIdleSync()
            assertEquals(base.root.name,prefs.getString("pack:en-US",null))
            inst.runOnMainSync { activity!!.finish() }; inst.waitForIdleSync()
            activity=launch(); inst.waitForIdleSync()
            inst.runOnMainSync {
                val spinner=descendants(activity!!.window.decorView).first { it.contentDescription=="Offline model choice" } as Spinner
                assertTrue(spinner.selectedItem.toString().contains("Whisper Base"))
                assertTrue(spinner.selectedItem.toString().contains("Fixture base"))
            }
        } finally {
            inst.runOnMainSync { activity?.finish() }
            tiny.root.deleteRecursively(); base.root.deleteRecursively()
            prefs.edit().also { edit -> original.forEach { (k,v) -> if(v==null) edit.remove(k) else edit.putString(k,v) } }.commit()
        }
    }
}
