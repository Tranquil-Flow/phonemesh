package org.phonemesh.app

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.util.Log
import org.phonemesh.core.CatalogEntry
import org.phonemesh.core.DownloadDecision
import org.phonemesh.core.DownloadPlan
import org.phonemesh.core.GeneratedPackCatalog
import org.phonemesh.core.PackInfo
import org.phonemesh.core.PackKind
import org.phonemesh.core.PackRejected
import org.phonemesh.core.PackStore
import java.io.File
import java.io.IOException
import java.util.Locale

/**
 * The app's only network use: language packs fetched by the system [DownloadManager] from
 * [baseUrl] plus a built-in catalog file name, into the app-specific external `downloads`
 * directory, then verified and installed by [store] and deleted.
 *
 * This is the only class that builds URLs or touches [DownloadManager]. Download ids are kept in
 * preferences so a download finished while the app was closed is collected by the next instance,
 * exactly once.
 *
 * Preferences are written with `commit()` on purpose: the id must be durable before an install
 * starts, or a crash could collect the same download twice.
 */
@SuppressLint("ApplySharedPref")
class PackDownloads(private val context: Context, private val store: PackStore,
    private val baseUrl: String = BuildConfig.PACK_BASE_URL) {
    private val dm = context.getSystemService(DownloadManager::class.java)
    private val prefs = context.getSharedPreferences("pack-downloads", Context.MODE_PRIVATE)
    private val catalog get() = GeneratedPackCatalog.entries

    private fun downloadsDir(): File = checkNotNull(context.getExternalFilesDir(DIR)) { "Stockage indisponible" }
    private fun destination(entry: CatalogEntry) = File(downloadsDir(), entry.fileName)

    /**
     * Starts the download if there is room for the ZIP, the unpacked pack and some headroom.
     * Plain free space is deliberate: the check must not count cache the system may never clear.
     */
    @SuppressLint("UsableSpace")
    fun start(entry: CatalogEntry): DownloadDecision = synchronized(LOCK) {
        require(entry in catalog) { "Pack de langue inconnu" }
        val decision = DownloadPlan.decide(entry, downloadsDir().usableSpace)
        if (decision != DownloadDecision.Start) return@synchronized decision
        pending()[entry.id]?.let { forget(entry.id, it) }
        destination(entry).delete()
        val request = DownloadManager.Request(Uri.parse(baseUrl + entry.fileName))
            .setDestinationInExternalFilesDir(context, DIR, entry.fileName)
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(false)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setTitle(PackDownloadText.title(entry))
        val id = dm.enqueue(request)
        prefs.edit().putLong(entry.id, id).commit()
        decision
    }

    /** Pack id → [DownloadManager] id for every download not yet collected. */
    fun pending(): Map<String, Long> = prefs.all.mapNotNull { (k, v) -> (v as? Long)?.let { k to it } }.toMap()

    /** Bytes downloaded so far and the total (-1 until the server reports it), or null if none is pending. */
    fun progress(packId: String): Pair<Long, Long>? {
        val id = pending()[packId] ?: return null
        dm.query(DownloadManager.Query().setFilterById(id)).use { c ->
            if (!c.moveToFirst()) return null
            return c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)) to
                c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
        }
    }

    /**
     * Installs every successfully finished download once and reports every failed one; running
     * downloads are left alone. The preference is durably dropped before installing (a download
     * whose preference cannot be dropped stays pending for the next call), so a crash mid-install
     * cannot install twice. Every collected download has its ZIP deleted and its system row removed,
     * and a failure in one download becomes that download's result without stopping the others.
     */
    fun collectFinished(): List<Result<PackInfo>> = synchronized(LOCK) {
        val results = mutableListOf<Result<PackInfo>>()
        for ((packId, id) in pending()) {
            try {
                collect(packId, id)?.let { results += it }
            } catch (e: Exception) {
                results += Result.failure(e)
            }
        }
        results
    }

    /** One download's result, or null while it is still running or could not be claimed. */
    private fun collect(packId: String, id: Long): Result<PackInfo>? {
        val row = dm.query(DownloadManager.Query().setFilterById(id)).use { c ->
            if (!c.moveToFirst()) null
            else Triple(c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)),
                c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON)),
                c.getString(c.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI)))
        }
        if (row == null) {
            forget(packId, id)
            return Result.failure(PackDownloadFailure("Échec du téléchargement"))
        }
        val (status, reason, localUri) = row
        return when (status) {
            DownloadManager.STATUS_SUCCESSFUL -> {
                if (!prefs.edit().remove(packId).commit()) return null
                val entry = catalog.firstOrNull { it.id == packId }
                var file: File? = null
                try {
                    file = downloadedFile(localUri) ?: entry?.let { destination(it) }
                    when {
                        entry == null || file == null ->
                            Result.failure(PackRejected(PackRejected.Reason.UNKNOWN_PACK, "Pack de langue inconnu"))
                        !DownloadPlan.sizeMatches(entry, file.length()) ->
                            Result.failure(PackRejected(PackRejected.Reason.DAMAGED, "Téléchargement incomplet"))
                        else -> Result.success(file.inputStream().use { store.install(it) })
                    }
                } catch (e: Exception) {
                    Result.failure(e)
                } finally {
                    runCatching { (file ?: entry?.let { destination(it) })?.delete() }
                    dm.remove(id)
                }
            }
            DownloadManager.STATUS_FAILED -> {
                Log.w(TAG, "download of $packId failed, reason $reason")
                forget(packId, id)
                Result.failure(PackDownloadFailure(PackDownloadText.failure(reason)))
            }
            else -> null
        }
    }

    /** Whether any pending download has finished (successfully or not) and is waiting to be collected. */
    fun anyFinished(): Boolean = pending().values.any { id ->
        dm.query(DownloadManager.Query().setFilterById(id)).use { c ->
            !c.moveToFirst() || c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)) in
                setOf(DownloadManager.STATUS_SUCCESSFUL, DownloadManager.STATUS_FAILED)
        }
    }

    /** Stops a download and deletes whatever it fetched. */
    fun cancel(packId: String): Unit = synchronized(LOCK) {
        pending()[packId]?.let { forget(packId, it) }
    }

    private fun forget(packId: String, id: Long) {
        prefs.edit().remove(packId).commit()
        dm.remove(id)
        catalog.firstOrNull { it.id == packId }?.let { destination(it).delete() }
    }

    /** The file DownloadManager wrote, accepted only inside the app's downloads directory. */
    private fun downloadedFile(localUri: String?): File? {
        val path = localUri?.let { Uri.parse(it) }?.takeIf { it.scheme == "file" }?.path ?: return null
        val file = File(path).canonicalFile
        return file.takeIf { it.parentFile == downloadsDir().canonicalFile }
    }

    private companion object {
        const val DIR = "downloads"
        const val TAG = "PackDownloads"
        /** Shared by every instance: two instances must never collect the same download. */
        val LOCK = Any()
    }
}

/** A download-level failure whose message is already a short plain-French sentence for the screen. */
class PackDownloadFailure(message: String) : IOException(message)

/** Short plain-French wording for download notifications and failures (no ids or codes). */
internal object PackDownloadText {
    fun title(entry: CatalogEntry): String = when (entry.kind) {
        PackKind.ENGINE -> "Pack de reconnaissance vocale"
        PackKind.VOICE -> "Pack de langue " + language(entry)
    }

    /** The pack's name in the pack list: the language in its own words, or the recognition model. */
    fun name(entry: CatalogEntry): String = when (entry.kind) {
        PackKind.ENGINE -> "Reconnaissance vocale (" +
            entry.id.removePrefix("engine-whisper-").replaceFirstChar { it.titlecase(Locale.ROOT) } + ")"
        PackKind.VOICE -> language(entry)
    }

    private fun language(entry: CatalogEntry): String {
        val locale = Locale(entry.id.removePrefix("voice-").substringBefore('-'))
        return locale.getDisplayLanguage(locale).replaceFirstChar { it.titlecase(locale) }
    }

    fun failure(reason: Int): String = when (reason) {
        DownloadManager.ERROR_INSUFFICIENT_SPACE -> "Pas assez d'espace"
        DownloadManager.ERROR_HTTP_DATA_ERROR, DownloadManager.ERROR_UNHANDLED_HTTP_CODE,
        DownloadManager.ERROR_TOO_MANY_REDIRECTS, DownloadManager.ERROR_CANNOT_RESUME,
        in 400..599 -> "Problème de connexion. Réessayez."
        else -> "Échec du téléchargement"
    }
}
