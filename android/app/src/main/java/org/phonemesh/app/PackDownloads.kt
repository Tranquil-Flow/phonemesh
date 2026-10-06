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
import java.io.InputStream
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
    private val baseUrl: String = BuildConfig.PACK_BASE_URL,
    private val install: (InputStream) -> PackInfo = { store.install(it) }) {
    private val dm = context.getSystemService(DownloadManager::class.java)
    private val prefs = context.getSharedPreferences("pack-downloads", Context.MODE_PRIVATE)
    private val catalog get() = GeneratedPackCatalog.entries

    private fun downloadsDir(): File = checkNotNull(context.getExternalFilesDir(DIR)) { "Stockage indisponible" }
    private fun destination(entry: CatalogEntry) = File(downloadsDir(), entry.fileName)

    /**
     * Starts the download if there is room for the ZIP, the unpacked pack and some headroom.
     * Plain free space is deliberate: the check must not count cache the system may never clear.
     * Only takes [LOCK] briefly: installs run outside it, so this never waits for one.
     */
    @SuppressLint("UsableSpace")
    fun start(entry: CatalogEntry): DownloadDecision = synchronized(LOCK) {
        require(entry in catalog) { "Pack de langue inconnu" }
        // Its previous download is being installed from the same file name.
        if (entry.id in installing.keys) throw PackDownloadFailure(UiText(R.string.wait_for_operation))
        val decision = DownloadPlan.decide(entry, downloadsDir().usableSpace)
        if (decision != DownloadDecision.Start) return@synchronized decision
        pending()[entry.id]?.let { forget(entry.id, it) }
        destination(entry).delete()
        val request = DownloadManager.Request(Uri.parse(baseUrl + entry.fileName))
            .setDestinationInExternalFilesDir(context, DIR, entry.fileName)
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(false)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setTitle(PackDownloadText.title(entry).resolve(context))
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
     * downloads are left alone. Claiming happens under [LOCK]: the preference is durably dropped
     * before installing (a download whose preference cannot be dropped stays pending for the next
     * call), so another collector never sees a claimed download and a crash mid-install cannot
     * install twice. Installing (unzip and hashing, which can take tens of seconds on a slow phone)
     * runs outside [LOCK], so [start] and [cancel] stay quick. Every collected download has its ZIP
     * deleted and its system row removed, and a failure in one download becomes that download's
     * result without stopping the others. Files in the downloads directory that belong to no
     * pending download (left by a crash mid-install) are deleted.
     */
    fun collectFinished(): List<Result<PackInfo>> {
        val results = mutableListOf<Result<PackInfo>>()
        val claimed = mutableListOf<Outcome.Claimed>()
        synchronized(LOCK) {
            for ((packId, id) in pending()) {
                try {
                    when (val outcome = claim(packId, id)) {
                        is Outcome.Claimed -> { claimed += outcome; installing[packId] = outcome.file?.name }
                        is Outcome.Done -> results += outcome.result
                        null -> Unit
                    }
                } catch (e: Exception) {
                    results += Result.failure(e)
                }
            }
            runCatching { sweepOrphans() }.onFailure { Log.w(TAG, "could not sweep downloads", it) }
        }
        for (claim in claimed) {
            results += try { installClaimed(claim) } finally { synchronized(LOCK) { installing -= claim.packId } }
        }
        return results
    }

    private sealed interface Outcome {
        /** A finished download whose preference has been dropped: only this collector installs it. */
        class Claimed(val packId: String, val id: Long, val entry: CatalogEntry?, val file: File?) : Outcome
        class Done(val result: Result<PackInfo>) : Outcome
    }

    /** What to do with one download, or null while it is still running or could not be claimed. Under [LOCK]. */
    private fun claim(packId: String, id: Long): Outcome? {
        val row = dm.query(DownloadManager.Query().setFilterById(id)).use { c ->
            if (!c.moveToFirst()) null
            else Triple(c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)),
                c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON)),
                c.getString(c.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI)))
        }
        if (row == null) {
            forget(packId, id)
            return Outcome.Done(Result.failure(PackDownloadFailure(UiText(R.string.download_failed))))
        }
        val (status, reason, localUri) = row
        return when (status) {
            DownloadManager.STATUS_SUCCESSFUL -> {
                if (!prefs.edit().remove(packId).commit()) return null
                val entry = catalog.firstOrNull { it.id == packId }
                Outcome.Claimed(packId, id, entry, runCatching { downloadedFile(localUri) ?: entry?.let { destination(it) } }.getOrNull())
            }
            DownloadManager.STATUS_FAILED -> {
                Log.w(TAG, "download of $packId failed, reason $reason")
                forget(packId, id)
                Outcome.Done(Result.failure(PackDownloadFailure(PackDownloadText.failure(reason))))
            }
            else -> null
        }
    }

    private fun installClaimed(claim: Outcome.Claimed): Result<PackInfo> {
        val (entry, file) = claim.entry to claim.file
        return try {
            when {
                entry == null || file == null ->
                    Result.failure(PackRejected(PackRejected.Reason.UNKNOWN_PACK, "Pack de langue inconnu"))
                !DownloadPlan.sizeMatches(entry, file.length()) ->
                    Result.failure(PackRejected(PackRejected.Reason.DAMAGED, "Téléchargement incomplet"))
                else -> Result.success(file.inputStream().use { install(it) })
            }
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            runCatching { (file ?: entry?.let { destination(it) })?.delete() }
            runCatching { dm.remove(claim.id) }
        }
    }

    /** Deletes files in the downloads directory that belong to no pending or installing download. Under [LOCK]. */
    private fun sweepOrphans() {
        val pending = pending()
        val keep = HashSet<String>()
        (pending.keys + installing.keys).forEach { id -> catalog.firstOrNull { it.id == id }?.let { keep += it.fileName } }
        installing.values.forEach { name -> name?.let { keep += it } }
        // The file DownloadManager is writing, in case it chose another name than the catalog's.
        pending.values.forEach { id ->
            runCatching {
                dm.query(DownloadManager.Query().setFilterById(id)).use { c ->
                    if (c.moveToFirst()) downloadedFile(c.getString(c.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI)))?.let { keep += it.name }
                }
            }
        }
        downloadsDir().listFiles().orEmpty()
            .filter { file -> keep.none { file.name == it || file.name.endsWith(it) } }
            .forEach { it.deleteRecursively() }
    }

    /** Deletes leftover downloads (see [collectFinished]) without collecting anything. */
    fun sweep(): Unit = synchronized(LOCK) { sweepOrphans() }

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
        /** Pack id → file name of each download claimed and being installed by some instance; guarded by [LOCK]. */
        val installing = HashMap<String, String?>()
    }
}

/** A download-level failure whose text is already a short sentence for the screen. */
class PackDownloadFailure(val text: UiText) : IOException("pack download failure ${text.id}")

/** Plain wording for download notifications and failures (no ids or codes). */
internal object PackDownloadText {
    fun title(entry: CatalogEntry): UiText = when (entry.kind) {
        PackKind.ENGINE -> UiText(R.string.pack_title_engine)
        PackKind.VOICE -> UiText(R.string.pack_title_voice, listOf(language(entry)))
    }

    /** The pack's name in the pack list: the language in its own words, or the recognition model. */
    fun name(entry: CatalogEntry): UiText = when (entry.kind) {
        PackKind.ENGINE -> UiText(R.string.pack_engine_name,
            listOf(entry.id.removePrefix("engine-whisper-").replaceFirstChar { it.titlecase(Locale.ROOT) }))
        PackKind.VOICE -> UiText(R.string.plain, listOf(language(entry)))
    }

    private fun language(entry: CatalogEntry): String {
        val locale = Locale(entry.id.removePrefix("voice-").substringBefore('-'))
        return locale.getDisplayLanguage(locale).replaceFirstChar { it.titlecase(locale) }
    }

    fun failure(reason: Int): UiText = UiText(
        when (reason) {
            DownloadManager.ERROR_INSUFFICIENT_SPACE -> R.string.no_space
            DownloadManager.ERROR_HTTP_DATA_ERROR, DownloadManager.ERROR_UNHANDLED_HTTP_CODE,
            DownloadManager.ERROR_TOO_MANY_REDIRECTS, DownloadManager.ERROR_CANNOT_RESUME,
            in 400..599 -> R.string.connection_problem
            else -> R.string.download_failed
        },
    )
}
