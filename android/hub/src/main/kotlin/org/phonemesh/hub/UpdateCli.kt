package org.phonemesh.hub

import org.phonemesh.core.update.UpdateRoots
import java.io.File
import java.io.PrintStream

/**
 * The operator's and owner's commands that are not the running hub (hub updates spec 5.4, decisions 45, 61, 70):
 *
 * - `phonemesh-hub sign-catalog --key catalog-key.pem --list packs.list --out release-folder [--public-keys public-keys.txt]`
 *   (owner, offline machine). The passphrase is asked for at the terminal, or read from the first line of standard input
 *   when there is no terminal; it is never taken from an argument, never printed, never stored.
 *
 * Exit codes: 0 done, 2 refused or misused (nothing written).
 */
object UpdateCli {
    val COMMANDS = setOf("sign-catalog")

    class Io(
        val out: PrintStream, val err: PrintStream,
        /** The key passphrase, or null when none can be asked for. */
        val passphrase: () -> CharArray?,
        /** The public keys a catalog is signed under (the ones compiled into the app unless `--public-keys` names a file). */
        val roots: UpdateRoots = UpdateRoots.compiled,
    )

    private class Usage(message: String) : Exception(message)

    fun run(args: List<String>, io: Io): Int = try {
        when (args.firstOrNull()) {
            "sign-catalog" -> signCatalog(args.drop(1), io)
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

    private fun options(args: List<String>, allowed: Set<String>): Map<String, String> {
        val values = HashMap<String, String>()
        var i = 0
        while (i < args.size) {
            val name = args[i]
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
        if (roots.isTest) io.out.println("NOTE: these are TEST keys. Nothing signed with them is trusted by a real release.")
        val signed = CatalogSigner.sign(list, key, roots, File(outPath))
        io.out.println("Signed ${signed.catalog.path}: sequence ${signed.sequence}, ${list.items.size} entries, ${signed.objects} file${if (signed.objects == 1) "" else "s"} in objects/.")
        io.out.println("Check it on another computer with the public keys before it goes on a stick.")
        return 0
    }
}
