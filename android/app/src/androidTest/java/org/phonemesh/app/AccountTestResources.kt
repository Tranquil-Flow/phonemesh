package org.phonemesh.app

import android.content.Context
import android.os.Process
import java.io.ByteArrayOutputStream
import java.security.KeyStore
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Test-only ownership ledger. Never adopts a preexisting database or key. */
internal class AccountTestResources(
    private val context: Context,
    private val namespace: String = "test-accounts-${UUID.randomUUID()}",
) {
    val opened = ArrayList<History>()
    private val databases = LinkedHashSet<String>()
    private val aliases = LinkedHashSet<String>()
    val vaultAlias: String get() = alias("$namespace-vault")
    val vault by lazy { Vault(vaultAlias) }

    private fun keys() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private fun paths(name: String) = listOf("", "-wal", "-shm", "-journal")
        .map { context.getDatabasePath(name + it) }

    fun database(label: String): String {
        require(label.matches(Regex("[a-z0-9-]+")))
        val name = "$namespace-$label.db"
        if (name !in databases) {
            check(paths(name).none { it.exists() }) { "fixture database already exists" }
            databases += name
        }
        return name
    }

    private fun alias(name: String): String {
        if (name !in aliases) {
            check(!keys().containsAlias(name)) { "fixture alias already exists" }
            aliases += name
        }
        return name
    }

    fun keyPrefix(file: String): String {
        check(file in databases) { "unowned fixture database" }
        alias("$file-identity") // AccountStore deletes this exact alias before generation.
        return file
    }

    fun history(file: String): History {
        check(file in databases) { "unowned fixture database" }
        return History(context, file).also { opened += it }
    }

    fun deleteDatabase(file: String) {
        check(file in databases) { "unowned fixture database" }
        context.deleteDatabase(file)
        check(paths(file).none { it.exists() }) { "fixture database cleanup failed" }
    }

    /** Attempt every owned cleanup even when an earlier close/delete fails. */
    fun clean(close: (History) -> Unit = { it.close() }) {
        var failure: Throwable? = null
        fun attempt(action: () -> Unit) {
            try { action() } catch (error: Throwable) {
                if (failure == null) failure = error else failure!!.addSuppressed(error)
            }
        }
        opened.forEach { history -> attempt { close(history) } }
        opened.clear()
        databases.forEach { file -> attempt { deleteDatabase(file) } }
        aliases.forEach { name -> attempt {
            val store = keys()
            store.deleteEntry(name)
            check(!store.containsAlias(name)) { "fixture alias cleanup failed" }
        } }
        failure?.let { throw it }
    }

    companion object {
        private const val MAX_LOG_BYTES = 1024 * 1024

        fun logCommand(since: String) = listOf(
            "logcat", "-d", "-v", "brief", "--pid=${Process.myPid()}", "-T", since,
        )

        /** Same PID, test-start time, bounded bytes and wall time; never prints inspected content. */
        fun readProcessLog(
            since: String,
            start: (List<String>) -> java.lang.Process = { ProcessBuilder(it).redirectErrorStream(true).start() },
        ): String {
            val process = try { start(logCommand(since)) } catch (_: Exception) {
                throw AssertionError("process log read could not start")
            }
            val worker = Executors.newSingleThreadExecutor()
            try {
                val read = worker.submit<String> {
                    val bytes = ByteArrayOutputStream()
                    process.inputStream.use { input ->
                        val buffer = ByteArray(4096)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            check(bytes.size() + count <= MAX_LOG_BYTES) { "process log read exceeded bound" }
                            bytes.write(buffer, 0, count)
                        }
                    }
                    check(process.waitFor() == 0) { "process log read failed" }
                    bytes.toString("UTF-8")
                }
                return read.get(10, TimeUnit.SECONDS)
            } catch (_: Exception) {
                // Do not attach a cause: command output/secret material must never reach a receipt.
                throw AssertionError("process log read failed or exceeded bounds")
            } finally {
                process.destroyForcibly()
                worker.shutdownNow()
            }
        }
    }
}
