package org.phonemesh.speech

import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One app-owned copy of the espeak-ng pronunciation data, used by every voice.
 *
 * sherpa-onnx sets up espeak-ng once per process from the first voice's data directory and keeps
 * reading that path (for example a language's dictionary the first time that language speaks). If
 * every voice pointed at its own pack, removing or replacing the first voice used would silence the
 * other languages until the app restarts. Every voice points here instead; pack removal never
 * touches this directory.
 *
 * Files come from an installed (already verified) voice pack, listed with their digests in the pack's
 * `SHA256SUMS`; a `.files` manifest records what is here. Files missing here are always added (new
 * files are safe for a running espeak-ng). A file whose content changed is replaced only while no
 * voice has loaded from here in this process ([loaded]); otherwise on a later start.
 */
class SharedEspeakData(private val dir: File, private val loaded: AtomicBoolean = PROCESS_LOADED) {
    /** Brings the shared copy up to date from [voiceRoot] and returns the directory to give espeak-ng. */
    fun prepare(voiceRoot: File): File = synchronized(LOCK) {
        val wanted = digests(voiceRoot.resolve("SHA256SUMS").readLines(), PACK_PREFIX)
        require(wanted.isNotEmpty()) { "Voice pack without pronunciation data" }
        if (!dir.isDirectory && !dir.mkdirs()) error("Cannot create ${dir.path}")
        val manifest = dir.resolve(MANIFEST)
        val have = if (manifest.isFile) digests(manifest.readLines(), "").toMutableMap() else mutableMapOf()
        val base = dir.canonicalPath + File.separator
        var changed = false
        for ((path, digest) in wanted) {
            val target = dir.resolve(path)
            require(path.split('/').none { it == ".." || it.isEmpty() } && target.canonicalPath.startsWith(base)) {
                "Unsafe pronunciation data path"
            }
            val present = have.containsKey(path) && target.isFile
            if (present && (have[path] == digest || loaded.get())) continue
            target.parentFile?.mkdirs()
            val temp = File(target.parentFile, ".${target.name}.${UUID.randomUUID()}")
            try {
                voiceRoot.resolve(PACK_PREFIX + path).copyTo(temp)
                if (!temp.renameTo(target)) error("Cannot update ${target.path}")
            } finally {
                temp.delete()
            }
            have[path] = digest
            changed = true
        }
        if (changed || !manifest.isFile) {
            val temp = dir.resolve(".$MANIFEST.${UUID.randomUUID()}")
            temp.writeText(have.toSortedMap().entries.joinToString("") { (path, digest) -> "$digest  $path\n" })
            if (!temp.renameTo(manifest)) { temp.delete(); error("Cannot update ${manifest.path}") }
        }
        dir
    }

    /** Call once a voice has loaded from [prepare]'s directory: espeak-ng now holds it for this process. */
    fun markLoaded() = loaded.set(true)

    /** `digest  path` lines whose path starts with [prefix], keyed by the path without it. */
    private fun digests(lines: List<String>, prefix: String): Map<String, String> = lines.mapNotNull { line ->
        val fields = line.split("  ", limit = 2)
        if (fields.size == 2 && fields[1].startsWith(prefix) && fields[1].length > prefix.length) {
            fields[1].removePrefix(prefix) to fields[0].lowercase()
        } else null
    }.toMap()

    companion object {
        private const val PACK_PREFIX = "tts/espeak-ng-data/"
        private const val MANIFEST = ".files"
        /** espeak-ng is set up once per process, so this flag is process-wide. */
        private val PROCESS_LOADED = AtomicBoolean(false)
        private val LOCK = Any()
    }
}
