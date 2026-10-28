package org.phonemesh.app

import android.os.Build
import org.phonemesh.core.CatalogEntry
import org.phonemesh.core.GeneratedPackCatalog
import org.phonemesh.core.PackStore
import org.phonemesh.core.update.FileTrustStore
import org.phonemesh.core.update.HubTransport
import org.phonemesh.core.update.PackActivation
import org.phonemesh.core.update.UpdateController
import org.phonemesh.core.update.UpdateRoots
import org.phonemesh.core.update.UpdateTrust
import java.io.File
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * The phone's hub pack updates (U5; docs/specs/2026-10-26-hub-updates-design.md): durable trust state in its own files, the
 * controller the Updates screen drives, and the repair of version floors at start. [problem] is a string resource when updates
 * are off because the saved state is damaged (it is never reset behind the person's back); otherwise [controller] is set.
 *
 * The transport is passed in as a function: the app gives the joined hub Wi-Fi's pinned client, so a copy can never use mobile data,
 * the radio or another network. The roots are the keys in `core/src/main/resources/update/public-keys.txt` (one file to swap).
 */
class HubUpdates(val controller: UpdateController?, val problem: Int?, internal val trust: UpdateTrust? = null) {
    /** Verified offers already held (no network): the screen of the hub shows "An update is ready." from this. */
    fun ready(): Int = controller?.pending() ?: 0

    companion object {
        private val pool: Executor by lazy { Executors.newCachedThreadPool { Thread(it, "hub-updates").apply { isDaemon = true } } }

        /**
         * Opens the saved state (first start: the packaged signed baseline) and raises version floors for packs already
         * installed ([PackActivation.reconcile]), so an install that finished just before a crash is not lost.
         * Blocking: files and a few signature checks.
         */
        fun open(
            dir: File, packs: File, store: PackStore, transport: () -> HubTransport?,
            roots: UpdateRoots = UpdateRoots.compiled, baseline: ByteArray = UpdateRoots.baselineCatalog(),
            compiled: List<CatalogEntry> = GeneratedPackCatalog.entries, executor: Executor = pool, sdk: Int = Build.VERSION.SDK_INT,
        ): HubUpdates {
            val trust = when (val opened = UpdateTrust.open(FileTrustStore(File(dir, "trust")), roots, BuildConfig.VERSION_CODE.toLong(), baseline)) {
                is UpdateTrust.Opened.Ready -> opened.trust
                is UpdateTrust.Opened.Corrupt -> return HubUpdates(null, R.string.updates_off)
                is UpdateTrust.Opened.BadBaseline -> return HubUpdates(null, R.string.updates_off)
            }
            val activation = PackActivation(packs, compiled)
            runCatching { activation.reconcile(trust) }
            val controller = UpdateController(
                trust, activation, File(dir, "transfer"), transport,
                installed = { store.list().associate { it.id to it.version.toLong() } },
                appVersion = BuildConfig.VERSION_CODE.toLong(), sdk = sdk, executor = executor,
                open = { info -> store.list().any { it.id == info.id && it.version == info.version } },
            )
            return HubUpdates(controller, null, trust)
        }
    }
}
