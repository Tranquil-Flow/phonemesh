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
    private fun archive(files:Map<String,String>):ByteArrayInputStream {
        val all=LinkedHashMap(files)
        all["SHA256SUMS"]=files.toSortedMap().map { (path,text) ->
            MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }+"  "+path+"\n"
        }.joinToString("")
        val output=ByteArrayOutputStream()
        ZipOutputStream(output).use { zip -> all.forEach { (path,text) ->
            zip.putNextEntry(ZipEntry(path)); zip.write(text.toByteArray()); zip.closeEntry()
        } }
        return ByteArrayInputStream(output.toByteArray())
    }
    private fun engine(model:String) = archive(linkedMapOf(
        "pack.properties" to "format=2\nkind=engine\nid=engine-whisper-$model\nversion=1\nlabel=Fixture $model\nrecognizerModel=whisper-$model\n",
        "whisper/encoder.int8.onnx" to "fixture", "whisper/decoder.int8.onnx" to "fixture",
        "whisper/tokens.txt" to "<|en|> 1\n"))
    private fun voice() = archive(linkedMapOf(
        "pack.properties" to "format=2\nkind=voice\nid=voice-en-US-fixture\nversion=1\nlabel=Fixture voice\nlanguageTag=en-US\nwhisperLanguage=en\n",
        "tts/model.onnx" to "fixture", "tts/tokens.txt" to "fixture", "tts/espeak-ng-data/phontab" to "fixture",
        "tts/espeak-ng-data/phondata" to "fixture", "tts/espeak-ng-data/phonindex" to "fixture",
        "tts/espeak-ng-data/intonations" to "fixture", "tts/espeak-ng-data/lang/gmw/en" to "fixture"))
    @Test fun canChooseTinyOrBaseAndRememberAcrossActivityRestart() {
        val inst=InstrumentationRegistry.getInstrumentation()
        val context=inst.targetContext
        val prefs=context.getSharedPreferences("MainActivity",Context.MODE_PRIVATE)
        val keys=listOf("language","engine")
        val original=keys.associateWith { prefs.getString(it,null) }
        val store=PackStore(File(context.filesDir,"packs"),emptyList(),allowUnknown=true)
        val before=store.list().map { it.root }.toSet()
        val tiny=store.install(engine("tiny"))
        val base=store.install(engine("base"))
        val fixtureVoice=store.install(voice())
        var activity:MainActivity?=null
        fun launch():MainActivity = inst.startActivitySync(Intent(context,MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        try {
            prefs.edit().putString("language","en-US").putString("engine",tiny.id).commit()
            activity=launch(); inst.waitForIdleSync()
            inst.runOnMainSync {
                val spinner=descendants(activity!!.window.decorView).first { it.contentDescription=="Offline model choice" } as Spinner
                assertTrue(spinner.selectedItem.toString().contains("Whisper Tiny"))
                val index=(0 until spinner.count).first { spinner.getItemAtPosition(it).toString().contains("Whisper Base") }
                spinner.setSelection(index)
            }
            inst.waitForIdleSync()
            assertEquals(base.id,prefs.getString("engine",null))
            inst.runOnMainSync { activity!!.finish() }; inst.waitForIdleSync()
            activity=launch(); inst.waitForIdleSync()
            inst.runOnMainSync {
                val spinner=descendants(activity!!.window.decorView).first { it.contentDescription=="Offline model choice" } as Spinner
                assertTrue(spinner.selectedItem.toString().contains("Whisper Base"))
            }
        } finally {
            inst.runOnMainSync { activity?.finish() }
            listOf(tiny,base,fixtureVoice).filter { it.root !in before }.forEach { it.root.deleteRecursively() }
            prefs.edit().also { edit -> original.forEach { (k,v) -> if(v==null) edit.remove(k) else edit.putString(k,v) } }.commit()
        }
    }
}
