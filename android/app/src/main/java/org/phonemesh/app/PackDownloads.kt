package org.phonemesh.app

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import org.phonemesh.core.CatalogEntry
import org.phonemesh.core.DownloadDecision
import org.phonemesh.core.DownloadPlan
import org.phonemesh.core.GeneratedPackCatalog
import org.phonemesh.core.PackInfo
import org.phonemesh.core.PackRejected
import org.phonemesh.core.PackStore
import java.io.File
import java.io.IOException

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
            .setTitle(entry.id)
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
     * downloads are left alone. The preference is dropped before installing, so a crash mid-install
     * cannot install twice, and the ZIP is deleted on every path.
     */
    fun collectFinished(): List<Result<PackInfo>> = synchronized(LOCK) {
        val results = mutableListOf<Result<PackInfo>>()
        for ((packId, id) in pending()) {
            val row = dm.query(DownloadManager.Query().setFilterById(id)).use { c ->
                if (!c.moveToFirst()) null
                else Triple(c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)),
                    c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON)),
                    c.getString(c.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI)))
            }
            val entry = catalog.firstOrNull { it.id == packId }
            if (row == null) {
                forget(packId, id)
                results += Result.failure(IOException("Téléchargement introuvable"))
                continue
            }
            val (status, reason, localUri) = row
            when (status) {
                DownloadManager.STATUS_SUCCESSFUL -> {
                    prefs.edit().remove(packId).commit()
                    val file = downloadedFile(localUri) ?: entry?.let { destination(it) }
                    try {
                        results += when {
                            entry == null || file == null ->
                                Result.failure(PackRejected(PackRejected.Reason.UNKNOWN_PACK, "Pack de langue inconnu"))
                            !DownloadPlan.sizeMatches(entry, file.length()) ->
                                Result.failure(PackRejected(PackRejected.Reason.DAMAGED, "Téléchargement incomplet"))
                            else -> try {
                                Result.success(file.inputStream().use { store.install(it) })
                            } catch (e: Exception) {
                                Result.failure(e)
                            }
                        }
                    } finally {
                        file?.delete()
                        dm.remove(id)
                    }
                }
                DownloadManager.STATUS_FAILED -> {
                    forget(packId, id)
                    results += Result.failure(IOException("Échec du téléchargement (code $reason)"))
                }
            }
        }
        results
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
        /** Shared by every instance: two instances must never collect the same download. */
        val LOCK = Any()
    }
}
