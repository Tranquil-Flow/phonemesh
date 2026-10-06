package org.phonemesh.app

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import org.phonemesh.core.PackFormat
import org.phonemesh.core.PackInfo
import org.phonemesh.core.PackRejected
import org.phonemesh.core.PackStore
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Shares the installed app and packs phone to phone through the share sheet, with no network. */
object PackSharing {
    private const val FIXED_TIME = 1577836800000L // 2020-01-01
    private const val MARGIN = 50L * 1024 * 1024
    private val sumLine = Regex("^[a-fA-F0-9]{64}  (.+)$")

    fun authority(context: Context) = "${context.packageName}.share"

    /**
     * The files a rebuilt archive holds: `SHA256SUMS` plus exactly the files it lists, each one
     * allowed by [PackFormat], a regular file (never a link) that stays inside the pack root.
     * The catalog pins `SHA256SUMS`, so the receiving phone accepts only this exact set.
     */
    private fun packFiles(pack: PackInfo): List<Pair<String, File>> {
        val root = pack.root.canonicalFile
        val rootPath = root.path + File.separator
        val sums = File(root, "SHA256SUMS")
        if (!sums.isFile || Files.isSymbolicLink(sums.toPath())) throw PackRejected(PackRejected.Reason.DAMAGED, "Pack incomplet")
        val listed = sums.readLines().filter { it.isNotBlank() }.map {
            sumLine.matchEntire(it)?.groupValues?.get(1) ?: throw PackRejected(PackRejected.Reason.DAMAGED, "Pack incomplet")
        }
        return (listed + "SHA256SUMS").distinct().sorted().map { path ->
            if (!PackFormat.allowed(path, pack.kind) || path.split('/').any { it.isEmpty() || it == ".." || it == "." } || '\\' in path) {
                throw PackRejected(PackRejected.Reason.UNSAFE, "Fichier inattendu dans le pack")
            }
            val file = File(root, path)
            // Every component must be a real directory or file: a link anywhere is refused.
            var walk = file
            while (walk != root) {
                if (Files.isSymbolicLink(walk.toPath())) throw PackRejected(PackRejected.Reason.UNSAFE, "Lien dans le pack")
                walk = walk.parentFile ?: throw PackRejected(PackRejected.Reason.UNSAFE, "Chemin hors du pack")
            }
            if (!file.isFile || !file.canonicalPath.startsWith(rootPath)) throw PackRejected(PackRejected.Reason.DAMAGED, "Pack incomplet")
            path to file
        }
    }

    /** Deterministic ZIP of one installed pack: sorted entries, fixed time, DEFLATED level 9. */
    fun rebuild(pack: PackInfo, out: File) {
        val files = packFiles(pack)
        ZipOutputStream(out.outputStream().buffered()).use { zip ->
            zip.setLevel(Deflater.BEST_COMPRESSION)
            zip.setMethod(ZipOutputStream.DEFLATED)
            for ((path, file) in files) {
                val entry = ZipEntry(path)
                entry.time = FIXED_TIME
                zip.putNextEntry(entry)
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }

    /**
     * Copies the installed APK and rebuilds each installed pack into `cacheDir/share`, returning
     * their content URIs. [freeSpace] reports usable bytes for a directory (replaceable in tests).
     */
    fun prepare(context: Context, store: PackStore, freeSpace: (File) -> Long = { it.usableSpace }): List<Uri> {
        val share = File(context.cacheDir, "share")
        share.deleteRecursively() // Also clears leftovers of an interrupted earlier run.
        try {
            val apk = File(context.applicationInfo.sourceDir)
            val packs = store.list().map { it to packFiles(it) }
            val needed = apk.length() + packs.sumOf { (_, files) -> files.sumOf { it.second.length() } } + MARGIN
            if (freeSpace(context.cacheDir) < needed) throw PackRejected(PackRejected.Reason.NO_SPACE, "Espace insuffisant")
            if (!share.mkdirs()) throw IOException("Cannot create share directory")
            val names = ArrayList<String>()
            fun publish(name: String, write: (File) -> Unit) {
                val tmp = File(share, "$name.tmp")
                write(tmp)
                if (!tmp.renameTo(File(share, name))) throw IOException("Cannot publish $name")
                names += name
            }
            publish("Phonemesh.apk") { apk.copyTo(it, overwrite = true) }
            for ((pack, _) in packs) publish("phonemesh-${pack.id}-${pack.version}.zip") { rebuild(pack, it) }
            return names.map { Uri.parse("content://${authority(context)}/$it") }
        } catch (error: Throwable) {
            share.deleteRecursively()
            val noSpace = generateSequence(error) { it.cause }.take(8)
                .any { e -> e.message?.let { "No space" in it || "ENOSPC" in it } == true }
            throw when {
                error is PackRejected -> error
                noSpace -> PackRejected(PackRejected.Reason.NO_SPACE, "Espace insuffisant")
                // Not a download failure: keep IOException away from the download sentence.
                error is IOException -> IllegalStateException("Share failed", error)
                else -> error
            }
        }
    }

    fun shareIntent(context: Context, uris: List<Uri>): Intent {
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE)
        intent.type = "*/*"
        intent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        intent.clipData = ClipData.newRawUri("Phonemesh", uris.first()).also { clip ->
            uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
        }
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return intent
    }
}
