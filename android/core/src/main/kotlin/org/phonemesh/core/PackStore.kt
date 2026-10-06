package org.phonemesh.core

import java.io.File
import java.io.InputStream
import java.util.Properties
import java.util.UUID
import java.util.zip.ZipInputStream
import java.security.MessageDigest

data class PackInfo(val root: File, val languageTag: String, val whisperLanguage: String, val label: String,
    val recognizerModel: String = "whisper-tiny")

class PackStore(private val directory: File, private val maxBytes: Long = 512L * 1024 * 1024) {
    private val required = listOf("whisper/encoder.int8.onnx", "whisper/decoder.int8.onnx",
        "whisper/tokens.txt", "tts/model.onnx", "tts/tokens.txt", "tts/espeak-ng-data/phontab",
        "tts/espeak-ng-data/phondata", "tts/espeak-ng-data/phonindex", "tts/espeak-ng-data/intonations")
    private val languages = ("en zh de es ru ko fr ja pt tr pl ca nl ar sv it id hi fi vi he uk el ms cs ro da hu ta no th ur hr bg lt la mi ml cy sk te fa lv bn sr az sl kn et mk br eu is hy ne mn bs kk sq sw gl mr pa si km sn yo so af oc ka be tg sd gu am yi lo uz fo ht ps tk nn mt sa lb my bo tl mg as tt haw ln ha ba jw su").split(' ').toSet()
    private fun inspect(root: File): PackInfo {
        val manifest=root.resolve("pack.properties")
        require(manifest.isFile && manifest.length() in 1..65536) { "Missing pack.properties" }
        val props=Properties().apply { manifest.inputStream().use { load(it) } }
        require(props.getProperty("format")=="1") { "Unsupported model pack version" }
        val tag=props.getProperty("languageTag") ?: error("Missing languageTag")
        require(MessageCodec.canonical(tag)==tag) { "Pack language must be canonical" }
        val whisper=props.getProperty("whisperLanguage") ?: error("Missing whisperLanguage")
        require(whisper in languages && tag.substringBefore('-')==whisper) { "Unsupported Whisper language" }
        val label=props.getProperty("label",tag)
        require(label.isNotBlank() && label.length<=80) { "Invalid pack label" }
        required.forEach { path ->
            val file=root.resolve(path)
            require(file.isFile && file.length()>0) { "Missing model asset: $path" }
            require(file.canonicalPath.startsWith(root.canonicalPath+File.separator)) { "Asset escaped pack root" }
        }
        require(root.resolve("tts/espeak-ng-data/lang").walkTopDown().any { it.isFile && it.length()>0 }) {
            "Missing eSpeak language data"
        }
        val recognizer=props.getProperty("recognizerModel","whisper-tiny")
        require(recognizer in setOf("whisper-tiny","whisper-base")) { "Unsupported recognizer model" }
        return PackInfo(root,tag,whisper,label,recognizer)
    }
    private fun verifyChecksums(root:File) {
        val manifest=root.resolve("SHA256SUMS")
        require(manifest.isFile && manifest.length() in 1..1048576) { "Missing bounded SHA256SUMS manifest" }
        val actual=root.walkTopDown().filter { it.isFile }.map { it.relativeTo(root).invariantSeparatorsPath }
            .filter { it=="pack.properties" || it.startsWith("whisper/") || it.startsWith("tts/") }.toSet()
        val expected=linkedMapOf<String,String>()
        manifest.useLines { lines -> lines.filter { it.isNotBlank() }.forEach { line ->
            val fields=line.split("  ",limit=2)
            require(fields.size==2 && Regex("[a-fA-F0-9]{64}").matches(fields[0])) { "Invalid checksum entry" }
            require(fields[1] in actual && expected.put(fields[1],fields[0].lowercase())==null) { "Unknown or duplicate checksum path" }
        } }
        require(expected.keys==actual) { "Checksum manifest must cover every runtime asset" }
        expected.forEach { (path,digest) ->
            val hash=MessageDigest.getInstance("SHA-256")
            root.resolve(path).inputStream().use { input ->
                val buffer=ByteArray(65536)
                while(true) { val n=input.read(buffer); if(n<0) break; hash.update(buffer,0,n) }
            }
            val observed=hash.digest().joinToString("") { (it.toInt() and 255).toString(16).padStart(2,'0') }
            require(observed==digest) { "Model checksum mismatch: $path" }
        }
    }
    fun install(input: InputStream): PackInfo {
        require(maxBytes>0)
        check(directory.exists() || directory.mkdirs()) { "Cannot create model directory" }
        val id=UUID.randomUUID().toString()
        val stage=directory.resolve(".incoming-$id")
        check(stage.mkdir())
        try {
            var total=0L
            var count=0
            val seen=HashSet<String>()
            ZipInputStream(input).use { zip ->
                while(true) {
                    val entry=zip.nextEntry ?: break
                    require(++count<=5000) { "Too many archive entries" }
                    val name=entry.name
                    require(name.length<=512 && name.split('/').size<=16) { "Archive path is too deep or long" }
                    require(name.isNotEmpty() && !name.startsWith('/') && '\\' !in name && '\u0000' !in name &&
                        name.split('/').none { it==".." || it=="." }) { "Unsafe archive path" }
                    require(seen.add(name.trimEnd('/'))) { "Duplicate archive entry" }
                    val file=stage.resolve(name)
                    require(file.canonicalPath.startsWith(stage.canonicalPath+File.separator)) { "Archive path escaped root" }
                    if(entry.isDirectory) {
                        check(file.isDirectory || file.mkdirs())
                    } else {
                        check(file.parentFile.isDirectory || file.parentFile.mkdirs())
                        var fileBytes=0L
                        file.outputStream().use { out ->
                            val buffer=ByteArray(32768)
                            while(true) {
                                val n=zip.read(buffer)
                                if(n<0) break
                                total+=n; fileBytes+=n
                                require(total<=maxBytes && fileBytes<=256L*1024*1024) { "Model archive is too large" }
                                out.write(buffer,0,n)
                            }
                        }
                    }
                    zip.closeEntry()
                }
            }
            val checked=inspect(stage)
            verifyChecksums(stage)
            val ready=directory.resolve(id)
            check(stage.renameTo(ready)) { "Could not publish model pack" }
            return checked.copy(root=ready)
        } catch(error: Exception) {
            stage.deleteRecursively() // Only this failed import's newly-created staging files.
            throw error
        }
    }
    fun list(): List<PackInfo> = directory.listFiles().orEmpty()
        .filter { it.isDirectory && !it.name.startsWith('.') }
        .mapNotNull { runCatching { inspect(it) }.getOrNull() }
        .sortedBy { it.label }
}
