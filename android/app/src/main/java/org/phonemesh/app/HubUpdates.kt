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
 * are off (saved state damaged, build not ready, or the keys in this build are TEST keys and the build does not allow that);
 * otherwise [controller] is set. [testKeys] is true when updates run under TEST keys (debug and demo builds): the screen says so.
 *
 * The transport is passed in as a function: the app gives the joined hub Wi-Fi's pinned client, so a copy can never use mobile data,
 * the radio or another network. The roots are the keys in `core/src/main/resources/update/public-keys.txt` (one file to swap).
 * A release build refuses TEST roots outright ([UpdateTrust.Opened.NotSetUp]): nothing is read, saved or downloaded (U7-1).
 */
class HubUpdates(val controller: UpdateController?, val problem: Int?, internal val trust: UpdateTrust? = null, val testKeys: Boolean = false) {
    /** Verified offers already held (no network): the screen of the hub shows "An update is ready." from this. */
    fun ready(): Int = controller?.pending() ?: 0

    companion object {
        private val pool: Executor by lazy { Executors.newCachedThreadPool { Thread(it, "hub-updates").apply { isDaemon = true } } }

        /**
         * Opens the saved state (first start: the packaged signed baseline) and raises version floors for packs already
         * installed ([PackActivation.reconcile]), so an install that finished just before a crash is not lost.
         * Blocking: files and a few signature checks.
         *
         * [opens] really starts a new pack before it is put into use (the old one stays active until it says yes); [withdrawn] is told
         * which installed packs the accepted list withdraws (at start and after every look), by default the store stops loading them.
         */
        fun open(
            dir: File, packs: File, store: PackStore, transport: () -> HubTransport?,
            roots: UpdateRoots = UpdateRoots.compiled, baseline: ByteArray = UpdateRoots.baselineCatalog(),
            compiled: List<CatalogEntry> = GeneratedPackCatalog.entries, executor: Executor = pool, sdk: Int = Build.VERSION.SDK_INT,
            allowTestRoots: Boolean = BuildConfig.ALLOW_TEST_ROOTS,
            opens: PackOpener = PackOpener { true },
            withdrawn: (Set<String>) -> Unit = { store.blockedIds = it },
        ): HubUpdates {
            val trust = when (val opened = UpdateTrust.open(FileTrustStore(File(dir, "trust")), roots, BuildConfig.VERSION_CODE.toLong(), baseline, allowTestRoots)) {
                is UpdateTrust.Opened.Ready -> opened.trust
                is UpdateTrust.Opened.NotSetUp -> return HubUpdates(null, R.string.updates_not_set_up)
                is UpdateTrust.Opened.Corrupt -> return HubUpdates(null, R.string.updates_off)
                is UpdateTrust.Opened.BadBaseline -> return HubUpdates(null, R.string.updates_off_build)
            }
            val activation = PackActivation(packs, compiled)
            runCatching { activation.reconcile(trust) }
            fun applyWithdrawn() = runCatching { withdrawn(trust.revokedInstalled().toSet()) }
            applyWithdrawn()
            val controller = UpdateController(
                trust, activation, File(dir, "transfer"), transport,
                installed = { store.list().associate { it.id to it.version.toLong() } },
                appVersion = BuildConfig.VERSION_CODE.toLong(), sdk = sdk, executor = executor,
                // The candidate is still outside the pack list here: the check loads it from where it is.
                open = { info -> opens.opens(info) },
                installedBytes = { id -> store.list().firstOrNull { it.id == id }?.root?.walkTopDown()?.filter { it.isFile }?.sumOf { it.length() } ?: 0L },
                onTrustChanged = { applyWithdrawn() },
            )
            return HubUpdates(controller, null, trust, testKeys = roots.isTest)
        }
    }
}
