package org.phonemesh.core

import java.util.Base64
import org.junit.Assert.*
import org.junit.Test

class PythonWireCompatibilityTest {
    @Test fun matchesActualPythonGoldenVectorsForEveryOriginalProfile() {
        val source=checkNotNull(javaClass.classLoader.getResourceAsStream("python-wire-vectors.tsv"))
        val lines=source.bufferedReader().use { it.readLines() }.filter { it.isNotBlank() }
        assertEquals(9,lines.size)
        lines.forEach { line ->
            val fields=line.split('\t')
            val text=String(Base64.getDecoder().decode(fields[1]),Charsets.UTF_8)
            val payload=String(Base64.getDecoder().decode(fields[2]),Charsets.UTF_8)
            assertEquals(payload,MessageCodec.encode(text,fields[0]))
            assertEquals(MessageCodec.Message(text,fields[0]),MessageCodec.decode(payload))
        }
    }
}
