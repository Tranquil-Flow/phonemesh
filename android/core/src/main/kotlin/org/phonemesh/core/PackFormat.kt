package org.phonemesh.core

import java.io.File

enum class PackKind { ENGINE, VOICE }

/** A pack the app knows about: its identity and the SHA-256 of its `SHA256SUMS` file. */
data class CatalogEntry(val id: String, val version: Int, val kind: PackKind,
    val fileName: String, val zipBytes: Long, val manifestSha256: String)

data class PackInfo(val root: File, val id: String, val version: Int, val kind: PackKind,
    val label: String, val recognizerModel: String? = null,
    val languageTag: String? = null, val whisperLanguage: String? = null)

class PackRejected(val reason: Reason, message: String) : IllegalArgumentException(message) {
    enum class Reason { DAMAGED, WRONG_FILE, UNKNOWN_PACK, OLDER_VERSION, NO_SPACE, UNSAFE }
}

/** Format 2 archive layout: which entry paths a pack may contain. */
object PackFormat {
    val ROOT_FILES = setOf("pack.properties", "SHA256SUMS", "NOTICE.txt", "SOURCES.json")
    fun prefixFor(kind: PackKind) = if (kind == PackKind.ENGINE) "whisper/" else "tts/"
    /** Allowed for an archive whose kind is not yet known: root files, or either prefix. */
    fun plausible(name: String) = name in ROOT_FILES || name.startsWith("whisper/") || name.startsWith("tts/")
    fun allowed(name: String, kind: PackKind) = name in ROOT_FILES || name.startsWith(prefixFor(kind))
}
