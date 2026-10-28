package org.phonemesh.hub

import org.phonemesh.core.update.UpdateMetadata
import org.phonemesh.core.update.UpdateRoots
import org.phonemesh.core.update.UpdateTrust
import java.io.File
import java.io.PrintStream

/**
 * The operator's and owner's commands that are not the running hub (hub updates spec 5.4, decisions 45, 61, 70):
 *
 * - `phonemesh-hub sign-catalog --key catalog-key.pem --list packs.list --out release-folder [--public-keys public-keys.txt]`
 *   (owner, offline machine). The passphrase is asked for at the terminal, or read from the first line of standard input
 *   when there is no terminal; it is never taken from an argument, never printed, never stored.
 *
 * - `phonemesh-hub sign-baseline --public-keys public-keys.txt --key catalog-key.pem --list update-baseline.list --out <folder>
 *   [--packs <folder with the pack archives>]` (owner, offline): the one step of the real-key swap. It checks the owner's public keys,
 *   signs the baseline catalog of the packs this build knows, proves the pair opens a fresh phone state, and writes `public-keys.txt` and
 *   `baseline-catalog.pmup` together into the folder (the app's `core/src/main/resources/update`). TEST keys are refused unless
 *   `--allow-test-keys` is given.
 *
 * - `phonemesh-hub sign-recovery --key recovery-key.pem --public-keys public-keys.txt --new-catalog-key <66 hex> --new-key-id N
 *   --epoch E --min-sequence S --out <new folder>` (owner, offline, only after a catalog key is lost or stolen): writes `recovery.pmup`,
 *   checked the way a phone checks it.
 *
 * - `phonemesh-hub import-updates <stick folder> [hub.conf]` (operator): checks a stick's `catalog.pmup`, optional
 *   `recovery.pmup` and `objects/` and puts what verifies into the hub's data folder. A running hub serves it at once.
 * - `phonemesh-hub update-status [hub.conf]` (operator): the release held, files here or missing, room used, last import.
 *
 * Exit codes: 0 done, 2 refused or misused (nothing written).
 */
object UpdateCli {
    val COMMANDS = setOf("sign-catalog", "sign-baseline", "sign-recovery", "import-updates", "update-status")

    /** The line printed beside any output that depends on TEST keys; the text is shared with `update-status`. */
    private fun testNote(roots: UpdateRoots, io: Io) {
        if (roots.isTest) io.out.println("NOTE: " + UpdateStatus.TEST_KEYS_WARNING)
    }

    private fun lightKeyNote(key: KeyFile.Loaded, io: Io) {
        if (key.lightlyProtected) io.out.println("WARNING: this key file is only lightly protected by its passphrase (${key.iterations} PBKDF2 rounds${if (key.sha1) ", SHA-1" else ""}). Use a long passphrase and keep the file offline.")
    }

    class Io(
        val out: PrintStream, val err: PrintStream,
        /** The key passphrase, or null when none can be asked for. */
        val passphrase: () -> CharArray?,
        /** The public keys a catalog is signed under (the ones compiled into the app unless `--public-keys` names a file). */
        val roots: UpdateRoots = UpdateRoots.compiled,
        val now: () -> Long = System::currentTimeMillis,
        /** The hub config used when a command is not given one. */
        val defaultConfig: File = File("hub.conf"),
        /** The packs the baseline must name (the ones this program was built with). */
        val compiledPacks: List<org.phonemesh.core.CatalogEntry> = org.phonemesh.core.GeneratedPackCatalog.entries,
    )

    private class Usage(message: String) : Exception(message)

    fun run(args: List<String>, io: Io): Int = try {
        when (args.firstOrNull()) {
            "sign-catalog" -> signCatalog(args.drop(1), io)
            "sign-baseline" -> signBaseline(args.drop(1), io)
            "sign-recovery" -> signRecovery(args.drop(1), io)
            "import-updates" -> importUpdates(args.drop(1), io)
            "update-status" -> updateStatus(args.drop(1), io)
            else -> throw Usage("commands: ${COMMANDS.joinToString(", ")}")
        }
    } catch (e: Usage) {
        io.err.println("phonemesh-hub: ${e.message}"); 2
    } catch (e: CatalogSigner.Refused) {
        io.err.println("refused: ${e.message}"); 2
    } catch (e: KeyFile.Rejected) {
        io.err.println("refused: ${e.message}"); 2
    } catch (e: java.io.IOException) {
        io.err.println("refused: ${e.message}"); 2
    } catch (e: IllegalArgumentException) {
        io.err.println("refused: ${e.message}"); 2
    }

    private fun options(args: List<String>, allowed: Set<String>, flags: Set<String> = emptySet()): Map<String, String> {
        val values = HashMap<String, String>()
        var i = 0
        while (i < args.size) {
            val name = args[i]
            if (name in flags) { values[name] = "yes"; i += 1; continue }
            if (!name.startsWith("--") || name !in allowed) throw Usage("unexpected '$name'")
            if (i + 1 >= args.size) throw Usage("$name needs a value")
            if (values.put(name, args[i + 1]) != null) throw Usage("$name is given twice")
            i += 2
        }
        return values
    }

    private fun signCatalog(args: List<String>, io: Io): Int {
        val o = options(args, setOf("--key", "--list", "--out", "--public-keys"))
        val keyPath = o["--key"] ?: throw Usage("sign-catalog needs --key <encrypted catalog key file>")
        val listPath = o["--list"] ?: throw Usage("sign-catalog needs --list <pack list>")
        val outPath = o["--out"] ?: throw Usage("sign-catalog needs --out <new folder>")
        val roots = o["--public-keys"]?.let { path ->
            val f = File(path)
            if (!f.isFile) throw CatalogSigner.Refused("$path is not a file")
            try { UpdateRoots.parse(f.readText()) } catch (e: IllegalArgumentException) { throw CatalogSigner.Refused("$path: ${e.message}") }
        } ?: io.roots
        val keyFile = File(keyPath)
        if (!keyFile.isFile) throw CatalogSigner.Refused("$keyPath is not a file")
        if (keyFile.length() > 16 * 1024) throw CatalogSigner.Refused("$keyPath is too large to be a key")
        // The list and packs are checked before the passphrase is asked for, so a typo costs no typing.
        val list = CatalogSigner.readList(File(listPath))
        val passphrase = io.passphrase() ?: throw Usage("no passphrase: run this in a terminal, or pipe the passphrase in on standard input")
        val key = try { KeyFile.read(keyFile.readText(), passphrase) } finally { passphrase.fill(' ') }
        testNote(roots, io)
        lightKeyNote(key, io)
        val signed = CatalogSigner.sign(list, key, roots, File(outPath))
        io.out.println("Signed ${signed.catalog.path}: sequence ${signed.sequence}, ${list.items.size} entries, ${signed.objects} file${if (signed.objects == 1) "" else "s"} in objects/.")
        io.out.println("Check it on another computer with the public keys before it goes on a stick.")
        return 0
    }

    /** The hub's data folder from its config file (the same file the running hub reads). */
    private fun hubDir(config: String?, io: Io): File {
        val file = config?.let { File(it) } ?: io.defaultConfig
        if (!file.isFile) throw CatalogSigner.Refused("${file.path} is not a file; name the hub's config file")
        return HubConfig.read(file).dir
    }

    private fun importUpdates(args: List<String>, io: Io): Int {
        if (args.isEmpty() || args.size > 2) throw Usage("import-updates <stick folder> [hub.conf]")
        val stick = File(args[0])
        val dir = hubDir(args.getOrNull(1), io)
        testNote(io.roots, io)
        val importer = UpdateImport(dir, io.roots.recoveryKey, io.roots.catalogKey, io.roots.catalogKeyId, now = io.now, burnt = io.roots.burnt)
        if (importer.recordsAreFromOtherKeys) io.out.println("NOTE: this hub's update records were made under other keys. A successful import replaces them.")
        fun files(n: Int) = "$n file${if (n == 1) "" else "s"}"
        return when (val r = importer.import(stick)) {
            is UpdateImport.Result.Imported -> {
                io.out.println("Imported catalog number ${r.sequence}: ${files(r.objects)} copied, ${r.missing} missing.")
                if (r.freshStart) io.out.println("The records made under other keys were replaced.")
                if (r.pruned > 0) io.out.println("${files(r.pruned)} of older releases removed to keep room.")
                if (r.missing > 0) io.out.println("${r.missing} listed file${if (r.missing == 1) " is" else "s are"} not on this stick. Import a stick that carries them later; phones get what is here.")
                io.out.println("A running hub serves this at once; no restart is needed."); 0
            }
            is UpdateImport.Result.AlreadyHave -> {
                io.out.println("Already have this catalog (number ${importer.state.sequence}): ${files(r.objects)} copied, ${r.missing} missing."); 0
            }
            is UpdateImport.Result.Refused -> { io.err.println("refused: ${r.why}"); 2 }
        }
    }

    private fun updateStatus(args: List<String>, io: Io): Int {
        if (args.size > 1) throw Usage("update-status [hub.conf]")
        val report = UpdateStatus.read(hubDir(args.firstOrNull(), io), io.roots, now = io.now)
        report.render().forEach(io.out::println)
        return if (report.kind == UpdateStatus.Kind.DAMAGED) 2 else 0
    }

    private fun readRoots(path: String): UpdateRoots {
        val f = File(path)
        if (!f.isFile) throw CatalogSigner.Refused("$path is not a file")
        if (f.length() > 4096) throw CatalogSigner.Refused("$path is too large to be a public key file")
        return try { UpdateRoots.parse(f.readText()) } catch (e: IllegalArgumentException) { throw CatalogSigner.Refused("$path: ${e.message}") }
    }

    private fun readKey(path: String, io: Io): KeyFile.Loaded {
        val keyFile = File(path)
        if (!keyFile.isFile) throw CatalogSigner.Refused("$path is not a file")
        if (keyFile.length() > 16 * 1024) throw CatalogSigner.Refused("$path is too large to be a key")
        val passphrase = io.passphrase() ?: throw Usage("no passphrase: run this in a terminal, or pipe the passphrase in on standard input")
        return try { KeyFile.read(keyFile.readText(), passphrase) } finally { passphrase.fill(' ') }
    }

    /** A trust store in memory: the trial open of a baseline and the trial of a policy touch no disk. */
    private class TrialStore : org.phonemesh.core.update.TrustStore {
        private val slots = arrayOfNulls<ByteArray>(2)
        override fun load(slot: Int) = slots[slot]?.copyOf()
        override fun save(slot: Int, bytes: ByteArray) { slots[slot] = bytes.copyOf() }
    }

    private fun signBaseline(args: List<String>, io: Io): Int {
        val o = options(args, setOf("--public-keys", "--key", "--list", "--packs", "--out"), flags = setOf("--allow-test-keys"))
        val keysPath = o["--public-keys"] ?: throw Usage("sign-baseline needs --public-keys <the owner's public-keys.txt>")
        val keyPath = o["--key"] ?: throw Usage("sign-baseline needs --key <encrypted catalog key file>")
        val listPath = o["--list"] ?: throw Usage("sign-baseline needs --list <update-baseline.list>")
        val outPath = o["--out"] ?: throw Usage("sign-baseline needs --out <folder>, normally android/core/src/main/resources/update")
        val roots = readRoots(keysPath)
        if (roots.isTest && o["--allow-test-keys"] == null) {
            throw CatalogSigner.Refused("$keysPath holds TEST keys, which anyone can sign with. Give the owner's own public-keys.txt (a developer refreshing the test files adds --allow-test-keys)")
        }
        val out = File(outPath)
        if (out.exists() && !out.isDirectory) throw CatalogSigner.Refused("$outPath is not a folder")
        // The list and packs are checked before the passphrase is asked for.
        val list = CatalogSigner.readList(File(listPath), packs = o["--packs"]?.let(::File))
        val missing = baselineMismatch(list, io.compiledPacks)
        if (missing != null) throw CatalogSigner.Refused(missing)
        val key = readKey(keyPath, io)
        testNote(roots, io)
        lightKeyNote(key, io)
        val envelope = CatalogSigner.envelope(list, key, roots)
        // The pair must open a fresh install exactly as the app does it, under these roots.
        val trial = UpdateTrust.open(TrialStore(), roots, maxOf(1L, list.minReader), envelope, allowTestRoots = true)
        if (trial !is UpdateTrust.Opened.Ready) throw CatalogSigner.Refused("the new baseline would not open a fresh phone state")
        if (!out.isDirectory && !out.mkdirs()) throw CatalogSigner.Refused("cannot create $outPath")
        val keysTmp = File(out, ".public-keys.txt.tmp"); val baseTmp = File(out, ".baseline-catalog.pmup.tmp")
        try {
            keysTmp.writeBytes(File(keysPath).readBytes())
            baseTmp.outputStream().use { it.write(envelope); it.fd.sync() }
            java.nio.file.Files.move(keysTmp.toPath(), File(out, "public-keys.txt").toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            java.nio.file.Files.move(baseTmp.toPath(), File(out, "baseline-catalog.pmup").toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        } finally { keysTmp.delete(); baseTmp.delete() }
        // Read both back as the app will.
        val back = readRoots(File(out, "public-keys.txt").path)
        if (!back.id.contentEquals(roots.id) || UpdateMetadata.verify(File(out, "baseline-catalog.pmup").readBytes(), mapOf(back.catalogKeyId to back.catalogKey), null) == null) {
            throw CatalogSigner.Refused("the written pair does not verify; do not use it")
        }
        io.out.println("Wrote ${File(out, "public-keys.txt").path} and ${File(out, "baseline-catalog.pmup").path} together.")
        io.out.println("Baseline: epoch ${list.epoch}, catalog number ${list.sequence}, ${list.items.size} packs, signed by catalog key id ${roots.catalogKeyId}.")
        io.out.println(if (roots.isTest) "These are TEST keys: a release build still refuses updates." else "Next: rebuild the app and the hub, run the tests, and build the release (docs/update-keys.md, step 4).")
        return 0
    }

    /** Null when [list] names exactly the packs this build carries (same id, version, size and manifest); else what differs. */
    private fun baselineMismatch(list: CatalogSigner.CatalogList, compiled: List<org.phonemesh.core.CatalogEntry>): String? {
        val packs = list.items.map { it.entry }.filter { it.kind != UpdateMetadata.APK }
        if (packs.size != compiled.size || list.items.size != packs.size) return "the baseline list must name exactly the ${compiled.size} packs this build carries, not ${list.items.size} entries"
        for (c in compiled) {
            val e = packs.firstOrNull { it.id == c.id } ?: return "the baseline list does not name ${c.id}"
            val sums = e.sums.joinToString("") { "%02x".format(it) }
            if (e.version != c.version.toLong() || e.bytes != c.zipBytes || !sums.equals(c.manifestSha256, ignoreCase = true)) {
                return "${c.id} in the list is not the pack this build carries (version, size or manifest differ)"
            }
        }
        return null
    }

    private fun signRecovery(args: List<String>, io: Io): Int {
        val o = options(args, setOf("--key", "--public-keys", "--new-catalog-key", "--new-key-id", "--epoch", "--min-sequence", "--min-reader", "--out"))
        val keyPath = o["--key"] ?: throw Usage("sign-recovery needs --key <encrypted recovery key file>")
        val outPath = o["--out"] ?: throw Usage("sign-recovery needs --out <new folder>")
        val newKeyHex = o["--new-catalog-key"]?.takeIf { Regex("[0-9a-fA-F]{66}").matches(it) } ?: throw Usage("sign-recovery needs --new-catalog-key <66 hex digits, the compressed public key>")
        fun number(name: String, default: Long? = null): Long {
            val v = o[name] ?: return default ?: throw Usage("sign-recovery needs $name")
            return v.takeIf { Regex("[0-9]{1,18}").matches(it) }?.toLong() ?: throw Usage("$name must be a whole number")
        }
        val epoch = number("--epoch"); val keyId = number("--new-key-id"); val minSequence = number("--min-sequence"); val minReader = number("--min-reader", 1)
        val roots = o["--public-keys"]?.let(::readRoots) ?: io.roots
        if (epoch < 2) throw CatalogSigner.Refused("--epoch must be 2 or more: the first epoch is the one the app starts with")
        if (keyId !in 1..0xFFFF || keyId.toInt() == UpdateMetadata.RECOVERY_KEY_ID) throw CatalogSigner.Refused("--new-key-id must be 1 to 65535 and not ${UpdateMetadata.RECOVERY_KEY_ID} (the recovery key's id)")
        if (minSequence < 1) throw CatalogSigner.Refused("--min-sequence starts at 1")
        if (minReader < 1 || minReader > 0xFFFFFFFFL) throw CatalogSigner.Refused("--min-reader is 1 or more")
        val newCompressed = newKeyHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val point = org.phonemesh.core.crypto.P256.decompress(newCompressed) ?: throw CatalogSigner.Refused("--new-catalog-key is not a point of P-256")
        val newKey = byteArrayOf(4) + org.phonemesh.core.crypto.Bytes.fixed(point.affineX, 32) + org.phonemesh.core.crypto.Bytes.fixed(point.affineY, 32)
        if (newKey.contentEquals(roots.recoveryKey)) throw CatalogSigner.Refused("the new catalog key is the recovery key; use a new key")
        if (newKey.contentEquals(roots.catalogKey)) throw CatalogSigner.Refused("the new catalog key is the one it replaces; make a new key")
        if (roots.burnt.any { it.contentEquals(newKey) }) throw CatalogSigner.Refused("the new catalog key was burnt before; make a new key")
        val out = File(outPath)
        if (out.exists() && (!out.isDirectory || out.list().orEmpty().isNotEmpty())) throw CatalogSigner.Refused("$outPath is not a new, empty folder")
        val key = readKey(keyPath, io)
        if (!key.publicKey.contentEquals(roots.recoveryKey)) throw CatalogSigner.Refused(
            if (key.publicKey.contentEquals(roots.catalogKey)) "this is the catalog key; a recovery policy is signed by the recovery key" else "this key is not the recovery key named in the public key file")
        testNote(roots, io)
        lightKeyNote(key, io)
        val payload = java.nio.ByteBuffer.allocate(8 + 2 + 65 + 8 + 4).putLong(epoch).putShort(keyId.toShort()).put(newKey).putLong(minSequence).putInt(minReader.toInt()).array()
        val bytes = UpdateMetadata.sign(UpdateMetadata.RECOVERY, UpdateMetadata.RECOVERY_KEY_ID, payload, key.privateKey)
        // Checked the way a phone checks it: the signature, the policy fields, and that the state it would start can be read back.
        val env = UpdateMetadata.verify(bytes, emptyMap(), roots.recoveryKey) ?: throw CatalogSigner.Refused("the signed policy does not verify")
        val policy = UpdateMetadata.recovery(env.payload) ?: throw CatalogSigner.Refused("the policy would not pass a phone's own checks")
        val next = org.phonemesh.core.update.TrustState(2, policy.epoch, policy.catalogKeyId, policy.catalogKey, policy.minSequence - 1, null, bytes, emptyMap(), roots.id)
        if (org.phonemesh.core.update.TrustState.decode(next.encode()) == null) throw CatalogSigner.Refused("a phone could not keep the state this policy starts")
        out.mkdirs()
        val target = File(out, "recovery.pmup")
        target.outputStream().use { it.write(bytes); it.fd.sync() }
        io.out.println("Signed ${target.path}: trust epoch $epoch, catalog key id $keyId, first catalog number $minSequence.")
        io.out.println("Put it beside catalog.pmup on the stick, then sign the first catalog of the new epoch with the new key (epoch $epoch, sequence $minSequence or more).")
        io.out.println("The next app version needs the new catalog key in public-keys.txt, and the old catalog key as a 'burnt public_key=' line (docs/update-keys.md).")
        return 0
    }
}
