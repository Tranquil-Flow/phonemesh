package org.phonemesh.app

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.annotation.TargetApi
import org.phonemesh.core.account.AccountId
import org.phonemesh.core.account.ContactCard
import org.phonemesh.core.crypto.P256
import org.phonemesh.core.session.KeyPairBytes
import org.phonemesh.core.session.OwnKeys
import org.phonemesh.core.session.SessionState
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Seals secrets kept in the history database (software keys, session states) with an AES-256-GCM key that lives in
 * the Android Keystore and never leaves it. Each sealed value is bound to a [label] naming its row, so a sealed value
 * cannot be moved to another row. Layout: `nonce (12) | ciphertext | tag (16)`.
 */
class Vault(private val alias: String = ALIAS) {
    private fun key(): SecretKey {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).apply { init(spec) }.generateKey()
    }

    fun seal(plain: ByteArray, label: String): ByteArray {
        val cipher = Cipher.getInstance(GCM).apply { init(Cipher.ENCRYPT_MODE, key()) } // the Keystore picks the nonce
        cipher.updateAAD(label.toByteArray(Charsets.UTF_8))
        val iv = cipher.iv
        check(iv.size == NONCE) { "unexpected nonce size" }
        return iv + cipher.doFinal(plain)
    }

    /** Null when [sealed] was not sealed here under [label], or was changed. */
    fun open(sealed: ByteArray, label: String): ByteArray? = runCatching {
        require(sealed.size > NONCE + TAG)
        val cipher = Cipher.getInstance(GCM).apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG * 8, sealed, 0, NONCE)) }
        cipher.updateAAD(label.toByteArray(Charsets.UTF_8))
        cipher.doFinal(sealed, NONCE, sealed.size - NONCE)
    }.getOrNull()

    companion object {
        const val ALIAS = "phonemesh-vault"
        const val KEYSTORE = "AndroidKeyStore"
        private const val GCM = "AES/GCM/NoPadding"
        private const val NONCE = 12
        private const val TAG = 16
    }
}

/**
 * This phone's account keys and contacts' sessions (plan step 9), on top of [History] and a [Vault].
 *
 * - **Identity key:** on Android 12 and later a Keystore P-256 key for signing and key agreement, never readable by the
 *   app. On Android 10 and 11 (Keystore cannot agree keys there), or when [softwareOnly], a software key sealed by the
 *   [Vault]. Made once, on first use.
 * - **Signed prekeys:** software keys sealed by the vault. A new one every [PREKEY_DAYS] days; the one before stays
 *   [PREKEY_DAYS] more, so links and starts made with it still work (review F11).
 * - **Sessions:** sealed [SessionState]s; a state older than the stored one is refused (review F6), and a packet is
 *   stored with the state that sealed it (F3). Nothing here is ever logged.
 */
class AccountStore(
    private val history: History,
    private val vault: Vault = Vault(),
    softwareOnly: Boolean = Build.VERSION.SDK_INT < 31,
    private val now: () -> Long = System::currentTimeMillis,
    keyPrefix: String = "phonemesh",
) : OwnKeys {
    private val identityAlias = "$keyPrefix-identity"

    private val account: History.AccountRow
    private val hardware: Boolean

    init {
        account = history.accountRow() ?: create(softwareOnly)
        hardware = account.store == STORE_KEYSTORE
        rotateIfDue()
    }

    /** Where the identity key lives: [STORE_KEYSTORE] or [STORE_SEALED]. */
    val store: String get() = account.store
    val accountId: AccountId get() = AccountId.of(account.identity)

    override val identity: ByteArray get() = account.identity.copyOf()
    override val prekeys: List<ByteArray> get() = livePrekeys().map { it.public.copyOf() }

    override fun agreeIdentity(peer: ByteArray): ByteArray? {
        val theirs = P256.publicKey(peer) ?: return null
        return if (hardware) {
            val secret = KeyAgreement.getInstance("ECDH", Vault.KEYSTORE).apply { init(keystoreIdentity()); doPhase(theirs, true) }.generateSecret()
            secret.takeIf { it.size == 32 }
        } else {
            P256.agree(softIdentity(), peer)
        }
    }

    override fun agreePrekey(prekey: ByteArray, peer: ByteArray): ByteArray? {
        val row = livePrekeys().firstOrNull { it.public.contentEquals(prekey) } ?: return null
        val scalar = vault.open(row.sealed, prekeyLabel(row.public)) ?: return null
        return try {
            P256.agree(P256.privateKey(scalar), peer)
        } finally {
            scalar.fill(0)
        }
    }

    override fun signIdentity(message: ByteArray): ByteArray {
        val key = if (hardware) keystoreIdentity() else softIdentity()
        val der = Signature.getInstance("SHA256withECDSA").apply { initSign(key); update(message) }.sign()
        return checkNotNull(P256.derToRaw(der)) { "unexpected signature encoding" }
    }

    /** This phone's card, with its current prekey and the account's name and colour. */
    fun card(): ContactCard = ContactCard.create(this, history.accountRow()!!.name.ifEmpty { "?" }, history.accountRow()!!.colour)

    fun setName(name: String, colour: Int) = history.setAccountName(name, colour)

    /** Makes a new prekey when the current one is [PREKEY_DAYS] old, and drops those retired as long ago. */
    fun rotateIfDue() {
        val time = now()
        val current = history.prekeyRows().firstOrNull { it.retired == null }
        if (current == null || time - current.created >= PREKEY_DAYS * DAY) {
            val pair = KeyPairBytes.generate()
            history.rotatePrekey(pair.public, vault.seal(pair.scalar, prekeyLabel(pair.public)), time, time - PREKEY_DAYS * DAY)
            pair.scalar.fill(0)
        } else {
            history.dropPrekeysRetiredBefore(time - PREKEY_DAYS * DAY)
        }
    }

    // ---- Sessions

    fun session(contact: AccountId): SessionState? {
        val (serial, sealed) = history.sessionRow(contact.toString()) ?: return null
        val state = vault.open(sealed, sessionLabel(contact))?.let { SessionState.decode(it) } ?: return null
        return state.takeIf { it.serial == serial }
    }

    /** Stores [state] and the [outgoing] packet sealed with it, in one transaction; false when [state] is older. */
    fun storeSession(contact: AccountId, state: SessionState, outgoing: History.OutboxRow? = null): Boolean =
        history.storeSession(contact.toString(), state.serial, vault.seal(state.encode(), sessionLabel(contact)), now(), outgoing)

    // ---- Inside

    private fun livePrekeys(): List<History.PrekeyRow> {
        val cutoff = now() - PREKEY_DAYS * DAY
        return history.prekeyRows().filter { it.retired == null || it.retired >= cutoff }
    }

    private fun create(softwareOnly: Boolean): History.AccountRow {
        val time = now()
        val made = if (!softwareOnly && Build.VERSION.SDK_INT >= 31) runCatching { createKeystoreIdentity() }.getOrNull() else null
        if (made != null) {
            history.createAccount(STORE_KEYSTORE, made, null, time)
        } else {
            val pair = KeyPairBytes.generate()
            history.createAccount(STORE_SEALED, pair.public, vault.seal(pair.scalar, IDENTITY_LABEL), time)
            pair.scalar.fill(0)
        }
        return checkNotNull(history.accountRow())
    }

    @TargetApi(31)
    private fun createKeystoreIdentity(): ByteArray {
        KeyStore.getInstance(Vault.KEYSTORE).apply { load(null) }.deleteEntry(identityAlias)
        val spec = KeyGenParameterSpec.Builder(identityAlias, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_AGREE_KEY)
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .build()
        val pair = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, Vault.KEYSTORE).apply { initialize(spec) }.generateKeyPair()
        return P256.compress(pair.public as ECPublicKey)
    }

    private fun keystoreIdentity(): PrivateKey =
        KeyStore.getInstance(Vault.KEYSTORE).apply { load(null) }.getKey(identityAlias, null) as PrivateKey

    private fun softIdentity(): PrivateKey {
        val scalar = checkNotNull(vault.open(checkNotNull(account.sealed), IDENTITY_LABEL)) { "the identity key cannot be opened" }
        return try {
            P256.privateKey(scalar)
        } finally {
            scalar.fill(0)
        }
    }

    companion object {
        const val STORE_KEYSTORE = "KEYSTORE"
        const val STORE_SEALED = "SEALED"
        const val PREKEY_DAYS = 30L
        const val DAY = 86_400_000L
        private const val IDENTITY_LABEL = "identity"
        private fun prekeyLabel(public: ByteArray) = "prekey:" + public.joinToString("") { "%02x".format(it) }
        private fun sessionLabel(contact: AccountId) = "session:$contact"
    }
}
