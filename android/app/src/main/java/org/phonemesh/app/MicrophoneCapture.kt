package org.phonemesh.app

import android.media.AudioFormat
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

/** User-initiated, bounded, in-memory PCM capture; never uploads or auto-sends. */
class MicrophoneCapture(private val context:Context) : Closeable {
    private val active=AtomicBoolean(false)
    private val inFlight=AtomicBoolean(false)
    private val handler=Handler(Looper.getMainLooper())
    private val timeout=Runnable { stop() }
    @Volatile private var recorder: AudioRecord?=null
    fun start(onStarted:()->Unit = {}, done:(FloatArray?,String?)->Unit) {
        check(inFlight.compareAndSet(false,true)) { "Previous recording is still closing" }
        active.set(true)
        Thread({
            var result:FloatArray?=null
            var failure:String?=null
            var audio:AudioRecord?=null
            try {
                if(context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED) {
                    throw SecurityException("RECORD_AUDIO not granted")
                }
                val minimum=AudioRecord.getMinBufferSize(16000,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT)
                check(minimum>0) { "16 kHz recording is unavailable on this phone" }
                audio=AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION,16000,
                    AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT,maxOf(minimum*2,8192))
                check(audio.state==AudioRecord.STATE_INITIALIZED) { "Microphone could not initialize" }
                recorder=audio
                val samples=FloatArray(16000*30)
                val buffer=ShortArray(2048)
                var count=0
                val deadline=SystemClock.elapsedRealtime()+30000
                handler.postDelayed(timeout,30000)
                if(active.get()) {
                    audio.startRecording()
                    check(audio.recordingState==AudioRecord.RECORDSTATE_RECORDING) { "Microphone did not start" }
                    onStarted()
                }
                while(active.get() && count<samples.size && SystemClock.elapsedRealtime()<deadline) {
                    val n=audio.read(buffer,0,minOf(buffer.size,samples.size-count),AudioRecord.READ_BLOCKING)
                    if(n<0) { if(active.get()) error("Microphone read failed ($n)") else break }
                    for(i in 0 until n) samples[count++]=buffer[i]/32768f
                }
                failure=if(count<1600) "Enregistrement trop court. Réessayez." else null
                if(failure==null) result=samples.copyOf(count)
            } catch(error:SecurityException) {
                failure="Permission microphone refusée ou révoquée"
            } catch(error:Exception) {
                // Details go to the log; the screen gets a fixed sentence.
                Log.w("Phonemesh","recording failed",error)
                failure="Microphone indisponible."
            } finally {
                handler.removeCallbacks(timeout)
                active.set(false)
                runCatching { audio?.stop() }
                audio?.release()
                recorder=null
                inFlight.set(false)
            }
            done(result,failure)
        },"Phonemesh-microphone").start()
    }
    fun stop() {
        active.set(false)
        runCatching { recorder?.stop() }
    }
    override fun close()=stop()
}
