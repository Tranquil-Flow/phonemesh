package org.phonemesh.app

import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

object WavOutput {
    fun write(file:File,samples:FloatArray,rate:Int) {
        require(rate in 8000..48000 && samples.isNotEmpty() && samples.size<=rate*60)
        require(samples.all { it.isFinite() })
        val size=samples.size*2
        val header=ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray()).putInt(36+size).put("WAVEfmt ".toByteArray())
        header.putInt(16).putShort(1).putShort(1).putInt(rate).putInt(rate*2)
        header.putShort(2).putShort(16).put("data".toByteArray()).putInt(size)
        FileOutputStream(file).use { out ->
            out.write(header.array())
            val pcm=ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
            samples.forEach { pcm.putShort((it.coerceIn(-1f,1f)*32767).toInt().toShort()) }
            out.write(pcm.array())
        }
    }
}
