package org.phonemesh.core

import org.phonemesh.core.PackRejected.Reason
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.StringReader
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.Properties
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipInputStream

/**
 * Installs and lists format-2 engine and voice packs.
 *
 * An archive is accepted only if every entry is inside the format-2 allowlist (checked before
 * anything is written), its `SHA256SUMS` hash matches the built-in [catalog] entry for its id,
 * version and kind (unless [allowUnknown]), every file matches `SHA256SUMS` exactly, and it is not
 * older than the installed pack with the same id. Every failure throws [PackRejected] and leaves
 * nothing behind. Staging directories left by an install that was killed are removed when a store
 * opens.
 */
class PackStore(private val directory: File, private val catalog: List<CatalogEntry>,
    private val allowUnknown: Boolean = false, private val maxBytes: Long = 512L * 1024 * 1024) {
    /**
     * The largest archive accepted when only catalog packs are allowed (null: no archive cap): the
     * largest catalog ZIP plus an eighth. An imported or shared archive was rebuilt on another phone,
     * so it is not size-matched; a rebuilt Whisper Base pack with ONNX entries deflated at
     * BEST_SPEED measured 8.2 % over its catalog size. Reading stops as soon as an archive is over.
     */
    val maxArchiveBytes: Long? = if (allowUnknown) null else catalog.maxOfOrNull { it.zipBytes }?.let { it + it / 8 }

    init { sweepStaging() }

    private val engineFiles = listOf("whisper/encoder.int8.onnx", "whisper/decoder.int8.onnx", "whisper/tokens.txt")
    private val voiceFiles = listOf("tts/model.onnx", "tts/tokens.txt", "tts/espeak-ng-data/phontab",
        "tts/espeak-ng-data/phondata", "tts/espeak-ng-data/phonindex", "tts/espeak-ng-data/intonations")
    private val recognizers = setOf("whisper-tiny", "whisper-base")
    private val idPattern = Regex("^[A-Za-z0-9-]{3,64}$")
    private val languages = ("en zh de es ru ko fr ja pt tr pl ca nl ar sv it id hi fi vi he uk el ms cs ro da hu ta no th ur hr bg lt la mi ml cy sk te fa lv bn sr az sl kn et mk br eu is hy ne mn bs kk sq sw gl mr pa si km sn yo so af oc ka be tg sd gu am yi lo uz fo ht ps tk nn mt sa lb my bo tl mg as tt haw ln ha ba jw su").split(' ').toSet()

    /** Whisper language codes per engine root path, read once from `whisper/tokens.txt`. */
    private val engineLanguages = ConcurrentHashMap<String, Set<String>>()
    private val languageToken = Regex("^<\\|([a-z]{2,3})\\|>$")

    /**
     * Languages an engine can transcribe. A `tokens.txt` that lists `<|xx|>` language tokens limits
     * the engine to those. The sherpa-onnx Whisper exports hold only the BPE tokens (the language
     * tokens live in the model metadata); every accepted `recognizerModel` is a multilingual Whisper,
     * so such an engine supports every Whisper language.
     */
    internal fun whisperLanguages(engine: PackInfo): Set<String> {
        engineLanguages[engine.root.absolutePath]?.let { return it }
        // A failed read is not remembered: the next check reads the file again.
        val read = runCatching {
            engine.root.resolve("whisper/tokens.txt").useLines { lines ->
                lines.mapNotNull { languageToken.matchEntire(it.substringBefore(' '))?.groupValues?.get(1) }.toSet()
            }.ifEmpty { languages }
        }.getOrNull() ?: return emptySet()
        return engineLanguages.putIfAbsent(engine.root.absolutePath, read) ?: read
    }

    private fun forget(root: File) { engineLanguages.remove(root.absolutePath) }

    private fun wrong(message: String): Nothing = throw PackRejected(Reason.WRONG_FILE, message)
    private fun unsafe(message: String): Nothing = throw PackRejected(Reason.UNSAFE, message)
    private fun damaged(message: String): Nothing = throw PackRejected(Reason.DAMAGED, message)

    private fun readProperties(root: File): Properties {
        val file = root.resolve("pack.properties")
        if (!file.isFile || file.length() !in 1..65536) wrong("Ce fichier n'est pas un pack Phonemesh")
        val text = try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(file.readBytes())).toString()
        } catch (e: CharacterCodingException) {
            wrong("Description du pack illisible")
        }
        return Properties().apply { load(StringReader(text)) }
    }

    private fun kindOf(props: Properties): PackKind {
        if (props.getProperty("format") != "2") wrong("Ancien format de pack non pris en charge")
        return when (props.getProperty("kind")) {
            "engine" -> PackKind.ENGINE
            "voice" -> PackKind.VOICE
            else -> wrong("Type de pack inconnu")
        }
    }

    /** Validates `pack.properties` and the required files of an unpacked pack; no hashing. */
    private fun describe(root: File, props: Properties, kind: PackKind): PackInfo {
        val id = props.getProperty("id")?.takeIf { idPattern.matches(it) } ?: wrong("Identifiant de pack invalide")
        val version = props.getProperty("version")?.toIntOrNull()?.takeIf { it > 0 } ?: wrong("Version de pack invalide")
        val label = props.getProperty("label")?.takeIf { it.isNotBlank() && it.length <= 80 } ?: wrong("Nom de pack invalide")
        val info = when (kind) {
            PackKind.ENGINE -> {
                val model = props.getProperty("recognizerModel")?.takeIf { it in recognizers }
                    ?: wrong("Moteur vocal non pris en charge")
                PackInfo(root, id, version, kind, label, recognizerModel = model)
            }
            PackKind.VOICE -> {
                val tag = props.getProperty("languageTag") ?: wrong("Langue du pack manquante")
                if (runCatching { MessageCodec.canonical(tag) }.getOrNull() != tag) wrong("Langue du pack invalide")
                val whisper = props.getProperty("whisperLanguage") ?: wrong("Langue du pack manquante")
                if (whisper !in languages || tag.substringBefore('-') != whisper) wrong("Langue du pack non prise en charge")
                PackInfo(root, id, version, kind, label, languageTag = tag, whisperLanguage = whisper)
            }
        }
        val rootPath = root.canonicalPath + File.separator
        (if (kind == PackKind.ENGINE) engineFiles else voiceFiles).forEach { path ->
            val file = root.resolve(path)
            if (!file.isFile || file.length() <= 0) wrong("Fichier manquant dans le pack : $path")
            if (!file.canonicalPath.startsWith(rootPath)) unsafe("Fichier hors du pack : $path")
        }
        if (kind == PackKind.VOICE &&
            root.resolve("tts/espeak-ng-data/lang").walkTopDown().none { it.isFile && it.length() > 0 }) {
            wrong("Données de prononciation manquantes")
        }
        return info
    }

    /** Full validation of a freshly extracted archive whose entry names are [names]. */
    private fun inspect(stage: File, names: List<String>): PackInfo {
        val props = readProperties(stage)
        val kind = kindOf(props)
        names.firstOrNull { !PackFormat.allowed(it, kind) }?.let { unsafe("Fichier inattendu dans le pack") }
        val info = describe(stage, props, kind)
        val sums = stage.resolve("SHA256SUMS")
        if (!sums.isFile || sums.length() !in 1..1048576) wrong("Liste de contrôle du pack manquante")
        return info
    }

    private fun inspectInstalled(root: File): PackInfo {
        val props = readProperties(root)
        return describe(root, props, kindOf(props))
    }

    private fun sha256(file: File): String {
        val hash = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(65536)
            while (true) { val n = input.read(buffer); if (n < 0) break; hash.update(buffer, 0, n) }
        }
        return hash.digest().joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
    }

    /** The pack's `SHA256SUMS` must be the one pinned in the catalog for this id, version and kind. */
    private fun checkPin(stage: File, info: PackInfo) {
        if (allowUnknown) return
        val pinned = catalog.firstOrNull { it.id == info.id && it.version == info.version && it.kind == info.kind }
        if (pinned == null || !pinned.manifestSha256.equals(sha256(stage.resolve("SHA256SUMS")), ignoreCase = true)) {
            throw PackRejected(Reason.UNKNOWN_PACK, "Pack inconnu de cette version de l'application")
        }
    }

    /** `SHA256SUMS` must list exactly the extracted files (except itself), each with a matching digest. */
    private fun verifyChecksums(root: File) {
        val actual = root.walkTopDown().filter { it.isFile }.map { it.relativeTo(root).invariantSeparatorsPath }
            .filter { it != "SHA256SUMS" }.toSet()
        val expected = linkedMapOf<String, String>()
        root.resolve("SHA256SUMS").useLines { lines -> lines.filter { it.isNotBlank() }.forEach { line ->
            val fields = line.split("  ", limit = 2)
            if (fields.size != 2 || !Regex("[a-fA-F0-9]{64}").matches(fields[0])) damaged("Liste de contrôle invalide")
            if (fields[1] !in actual || expected.put(fields[1], fields[0].lowercase()) != null) {
                damaged("Liste de contrôle incohérente")
            }
        } }
        if (expected.keys != actual) damaged("Liste de contrôle incomplète")
        expected.forEach { (path, digest) ->
            if (sha256(root.resolve(path)) != digest) damaged("Fichier endommagé : $path")
        }
    }

    /** Extracts [input] into [stage], refusing unsafe or non-allowlisted entries before writing them. */
    private fun extract(input: InputStream, stage: File): List<String> {
        var total = 0L
        var count = 0
        val seen = HashSet<String>()
        val names = ArrayList<String>()
        val stagePath = stage.canonicalPath + File.separator
        ZipInputStream(input).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (++count > 5000) unsafe("Trop de fichiers dans le pack")
                val name = entry.name
                if (name.length > 512 || name.split('/').size > 16) unsafe("Chemin trop long dans le pack")
                if (name.isEmpty() || name.startsWith('/') || '\\' in name || '\u0000' in name ||
                    name.split('/').any { it == ".." || it == "." }) unsafe("Chemin dangereux dans le pack")
                if (!seen.add(name.trimEnd('/'))) unsafe("Fichier en double dans le pack")
                if (!PackFormat.plausible(name)) unsafe("Fichier inattendu dans le pack")
                val file = stage.resolve(name)
                if (!file.canonicalPath.startsWith(stagePath)) unsafe("Chemin hors du pack")
                names += name
                if (entry.isDirectory) {
                    if (!file.isDirectory && !file.mkdirs()) unsafe("Chemin en conflit dans le pack")
                } else {
                    val parent = file.parentFile
                    if (!parent.isDirectory && !parent.mkdirs()) unsafe("Chemin en conflit dans le pack")
                    var fileBytes = 0L
                    file.outputStream().use { out ->
                        val buffer = ByteArray(32768)
                        while (true) {
                            val n = zip.read(buffer)
                            if (n < 0) break
                            total += n; fileBytes += n
                            if (total > maxBytes || fileBytes > 256L * 1024 * 1024) unsafe("Pack trop volumineux")
                            out.write(buffer, 0, n)
                        }
                    }
                }
                zip.closeEntry()
            }
        }
        return names
    }

    private fun Throwable.isNoSpace(): Boolean = generateSequence(this) { it.cause }.take(8)
        .any { e -> e.message?.let { "No space" in it || "ENOSPC" in it } == true }

    /** Removes staging directories that no install in this process is using (left by a killed install). */
    private fun sweepStaging() {
        directory.listFiles().orEmpty()
            .filter { (it.name.startsWith(STAGING) && it.absolutePath !in activeStaging) || (it.name.startsWith(PENDING) && it.absolutePath !in activePending) }
            .forEach { it.deleteRecursively() }
    }

    /**
     * Installs [input]. With [retainOlder] the older copies of the same pack stay on disk (the new one is the one [list]
     * returns, being newer) until [retireOlder], so a caller can still go back with [discard] if the new pack does not open.
     * With [pending] the new copy is published under a name [list] never returns (`.pending-...`) until [promote]: the pack
     * in use stays the old one at every moment, also if the process dies before the new one is accepted (a store opened later
     * removes the leftover). Implies [retainOlder].
     */
    fun install(input: InputStream, retainOlder: Boolean = false, pending: Boolean = false): PackInfo {
        require(maxBytes > 0)
        val stage = directory.resolve("$STAGING${UUID.randomUUID()}")
        activeStaging += stage.absolutePath // before the directory exists, so a sweep never sees it unclaimed
        try {
            if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Cannot create model directory")
            if (!stage.mkdir()) throw IOException("Cannot create staging directory")
            val names = extract(maxArchiveBytes?.let { CappedInputStream(input, it) } ?: input, stage)
            val info = inspect(stage, names)
            checkPin(stage, info)
            verifyChecksums(stage)
            val existing = installed().filter { it.id == info.id }
            existing.firstOrNull { it.version > info.version }?.let {
                throw PackRejected(Reason.OLDER_VERSION, "Une version plus récente de ce pack est déjà installée")
            }
            val manifest = sha256(stage.resolve("SHA256SUMS"))
            existing.firstOrNull { it.version == info.version &&
                runCatching { sha256(it.root.resolve("SHA256SUMS")) }.getOrNull() == manifest }?.let { same ->
                stage.deleteRecursively()
                return same
            }
            // A same-version pack with another manifest is replaced by this verified one.
            val ready = directory.resolve((if (pending) PENDING else "") + UUID.randomUUID().toString())
            if (pending) activePending += ready.absolutePath
            if (!stage.renameTo(ready)) { activePending -= ready.absolutePath; throw IOException("Could not publish model pack") }
            if (!retainOlder && !pending) existing.forEach { forget(it.root); it.root.deleteRecursively() } // Older or superseded copies of this pack only.
            return info.copy(root = ready)
        } catch (error: Throwable) {
            stage.deleteRecursively() // Only this failed import's newly-created staging files.
            throw when {
                error is PackRejected -> error
                error.isNoSpace() -> PackRejected(Reason.NO_SPACE, "Espace de stockage insuffisant")
                error is IOException -> PackRejected(Reason.DAMAGED, "Pack incomplet ou endommagé")
                error is IllegalArgumentException -> PackRejected(Reason.DAMAGED, "Pack incomplet ou endommagé")
                else -> error
            }
        } finally {
            activeStaging -= stage.absolutePath
        }
    }

    /** Every installed pack directory that still parses, including stale duplicates of one id. */
    private fun installed(): List<PackInfo> = directory.listFiles().orEmpty()
        .filter { it.isDirectory && !it.name.startsWith('.') }
        .mapNotNull { runCatching { inspectInstalled(it) }.getOrNull() }

    /**
     * Ids of installed packs the phone must not load: the accepted update list withdraws them (U7-6). They stay on disk (to be
     * replaced or removed) and in [list]; [usable] and [readiness] leave them out.
     */
    @Volatile var blockedIds: Set<String> = emptySet()

    /** [list] without the packs in [blockedIds]. */
    fun usable(): List<PackInfo> = list().let { all -> blockedIds.let { b -> if (b.isEmpty()) all else all.filter { it.id !in b } } }

    fun list(): List<PackInfo> = installed().groupBy { it.id }.values.map { same -> same.maxBy { it.version } }
        .sortedWith(compareBy({ it.kind }, { it.label }))

    /** Removes one installed copy, for example a just-installed pack that did not open. */
    fun discard(root: File) { forget(root); activePending -= root.absolutePath; root.deleteRecursively() }

    /** Makes a [pending] copy a normal installed pack (one atomic rename); [list] returns it from then on. */
    fun promote(info: PackInfo): PackInfo {
        require(info.root.name.startsWith(PENDING)) { "not a pending pack" }
        val live = directory.resolve(UUID.randomUUID().toString())
        if (!info.root.renameTo(live)) throw IOException("Could not publish model pack")
        activePending -= info.root.absolutePath
        return info.copy(root = live)
    }

    /** Removes the other, older copies of [kept]'s pack once [kept] has been accepted for good. */
    fun retireOlder(kept: PackInfo) {
        installed().filter { it.id == kept.id && it.root != kept.root && it.version <= kept.version }.forEach { discard(it.root) }
    }

    fun remove(id: String): Boolean {
        val matching = installed().filter { it.id == id }
        matching.forEach { forget(it.root) }
        return matching.isNotEmpty() && matching.map { it.root.deleteRecursively() }.all { it }
    }

    private companion object {
        const val STAGING = ".incoming-"
        const val PENDING = ".pending-"
        /** Pending copies of installs running in this process, across every store instance. */
        val activePending: MutableSet<String> = ConcurrentHashMap.newKeySet()
        /** Staging directories of installs running in this process, across every store instance. */
        val activeStaging: MutableSet<String> = ConcurrentHashMap.newKeySet()
    }
}

/** Counts the archive bytes read and rejects the archive as soon as it is larger than [cap]. */
private class CappedInputStream(input: InputStream, private val cap: Long) : FilterInputStream(input) {
    private var count = 0L
    private fun add(n: Long) {
        if (n <= 0) return
        count += n
        if (count > cap) throw PackRejected(Reason.UNSAFE, "Pack trop volumineux")
    }
    override fun read(): Int = super.read().also { if (it >= 0) add(1) }
    override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { add(it.toLong()) }
    override fun skip(n: Long): Long = super.skip(n).also { add(it) }
    override fun markSupported() = false
}

/** Whether the installed packs can run the speech loop for one language, and what is missing if not. */
sealed interface Readiness {
    data class Ready(val engine: PackInfo, val voice: PackInfo) : Readiness
    object MissingEngine : Readiness
    data class MissingVoice(val languageTag: String) : Readiness
    data class UnsupportedLanguage(val engine: PackInfo, val voice: PackInfo) : Readiness
}

/**
 * Picks the engine with [engineId] (else Whisper Tiny, else any installed engine) and the first voice
 * for [languageTag], and checks that the engine's tokens include the voice's Whisper language.
 */
fun PackStore.readiness(engineId: String?, languageTag: String): Readiness {
    val packs = usable()
    val engines = packs.filter { it.kind == PackKind.ENGINE }
    val engine = engines.firstOrNull { it.id == engineId }
        ?: engines.firstOrNull { it.id == "engine-whisper-tiny" }
        ?: engines.firstOrNull()
        ?: return Readiness.MissingEngine
    val voice = packs.firstOrNull { it.kind == PackKind.VOICE && it.languageTag == languageTag }
        ?: return Readiness.MissingVoice(languageTag)
    return if (voice.whisperLanguage in whisperLanguages(engine)) Readiness.Ready(engine, voice) else Readiness.UnsupportedLanguage(engine, voice)
}
