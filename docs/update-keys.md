# Update signing keys: the owner's steps

For v0.1.2 the owner chose this offline round-trip before APK signing (accounts decision 77).
A staged APK kit with TEST roots is provisional: it must refuse signing and be replaced after
both public resources land together in their own tested commit.

Hub pack updates (decisions 45, 61, 62, 70) are signed by two keys only the owner holds. This page is what the owner and the people who
prepare the release do, in order, when the real keys exist. Everything that needs a private key is done by the owner, offline. Until then the app carries **TEST keys**, and a release build refuses them.

## Where things stand until the real keys arrive

- `android/core/src/main/resources/update/public-keys.txt` holds TEST keys (`# status: TEST`). Their private parts follow from a public
  label, so anyone can sign with them.
- **Release builds** (the `release` build type) refuse TEST keys: updates are off, the Updates screen says "Updates are not set up in this
  version.", and nothing can be downloaded. Debug, minified debug and demo builds trust them (for tests); their Updates screen shows
  "TEST keys. For testing only."
- The demo's own keys are made fresh on the phone at every start. No private key is derived from a public label in any file that is built
  into an app or the hub. The deterministic TEST keys exist only in test sources.
- The software can run with updates off, but the owner's v0.1.2 choice requires real update keys before APK signing.

## When the real keys arrive

The private keys, their passphrases and the signing never leave the owner's offline computer. The owner never sends a `.pem` file,
keystore or passphrase to an agent, a chat or a repository. An agent or developer works only with public material.

1. **Make the keys** on an offline computer with `make-update-keys.sh` (see `phonemesh-update-keys/README.md`). The script checks that the
   machine is offline and takes the modes `both`, `catalog` and `recovery`. Key protection depends on the installed `openssl`:
   - **OpenSSL 3 or newer:** the key files use PBKDF2-HMAC-SHA256 with 600,000 rounds.
   - **Otherwise (for example macOS's LibreSSL):** 2,048 rounds, and the script prints a warning. The hub tool refuses fewer than 2,048
     rounds and says "lightly protected" for fewer than 100,000 rounds or SHA-1, but it still works.

   Either way, protect each key with a long passphrase (five random words or more) and keep the folder offline on two sticks. A split run
   (one mode at a time) leaves one public line per file. `sign-baseline` needs one file with exactly one `catalog key_id=1` line and one
   `recovery key_id=100` line, so combine **only the public lines**, never the `.pem` files.
2. **The developer prepares an offline kit** from the exact release source: the hub distribution (`phonemesh-hub.zip`, from
   `./gradlew :hub:distZip`), `hub/update-baseline.list`, the four public speech packs that the list names (exactly those four), a
   `SHA256SUMS` file and a one-page owner instruction. The kit holds no private key.
3. **The owner signs the baseline offline**, in Terminal on the offline computer, with Java 17 (the owner installs it before going
   offline; no Gradle or Android SDK is needed):

   ```sh
   cd ~/Desktop/update-keys-kit
   shasum -a 256 -c SHA256SUMS
   unzip -q phonemesh-hub.zip
   export JAVA_HOME="$(/usr/libexec/java_home -v 17)"
   KEYS="$HOME/Desktop/phonemesh-update-keys"   # the offline key folder
   sh phonemesh-hub/bin/phonemesh-hub sign-baseline \
     --public-keys "$KEYS/public-keys.txt" \
     --key "$KEYS/catalog-key.pem" \
     --list hub/update-baseline.list \
     --packs model-packs \
     --out signed
   ```

   It asks for the catalog passphrase at a hidden `Key passphrase:` prompt, so it must run in a terminal, never through an agent. It
   refuses TEST keys, a key that is not the catalog key in `public-keys.txt`, and a list that does not name exactly the packs this
   build carries. It writes `public-keys.txt` and a re-signed `baseline-catalog.pmup` into `signed/` together, after proving that the
   pair opens a fresh phone state.
4. **Only two public files come back:** `signed/public-keys.txt` and `signed/baseline-catalog.pmup` (plus the `shasum` lines the kit
   prints for them). Two public key lines on their own are not enough: the baseline has to be signed by the offline catalog key.
5. **Check and commit, with no partial swap.** Before anything is committed, check that each key line has the right id (1 and 100) and
   exactly 66 hex characters starting `02` or `03`, that both are valid P-256 points, and that the returned pair verifies with the
   product's own parsers. Copy both files to `android/core/src/main/resources/update` and commit them together, alone. Run
   `./gradlew :core:test :hub:test :app:testReleaseUnitTest` and `scripts/check.sh`. The tests that name the TEST keys step aside by
   themselves once the file holds other keys; the others check that the pair opens a fresh state. In a release build updates now run.
6. **Rebuild everything that was already frozen.** An unsigned APK built before the swap does not contain the new baseline. Rebuild the
   release inputs from the new commit, give the owner those to sign, and never sign an older kit. Then check that updates are available
   in the release build.
7. **Phones and hubs that held the TEST keys** need nothing from the owner. A phone records which keys its update state was made under;
   when the app starts with other keys it sets the old state aside and starts from the new baseline (the TEST catalog key is gone from it;
   version floors are rebuilt from the packs installed). A hub does the same at its next `import-updates`, and `update-status` says so
   before that. If the baseline does not match the keys, the phone says "Updates are off. This build is not ready." and the hub tool
   refuses; neither says "Saved data is damaged".
8. **First release under the real keys.** Write a pack list (`hub/update-baseline.list` shows the format) with `epoch = 1` and a
   `sequence` above 1, sign it with `sign-catalog` (offline, with the same passphrase rules), and bring it to hubs on a stick.

If the real pair has not arrived, release builds stay as they are: updates off, with the "Updates are not set up in this version."
screen.

## If the catalog key is lost or stolen (rotation)

1. The owner makes a new catalog key pair offline (run the script again; give the new catalog key id 2, 3, ... in `public-keys.txt`).
2. The owner signs a recovery policy with the **recovery** key, offline:

   ```sh
   phonemesh-hub sign-recovery --key recovery-key.pem --public-keys public-keys.txt \
     --new-catalog-key <66 hex digits of the new catalog key> --new-key-id 2 --epoch 2 --min-sequence 1 --out recovery-1
   ```

   `--epoch` must be higher than any epoch used before. It writes `recovery-1/recovery.pmup`.
3. Sign the first catalog of the new epoch with the new key (`epoch = 2`, `sequence` at least `--min-sequence`) and put `recovery.pmup`
   beside `catalog.pmup` on the stick. Hubs and phones that see the policy first adopt the new key; catalogs of the old key stop.
4. In the next app version put the new catalog key in `public-keys.txt` and add the old catalog key as a line
   `burnt public_key=<66 hex digits>`. The owner runs `sign-baseline` again from a fresh kit (the baseline's epoch is then 2). A burnt key is never adopted again, so an
   old recovery policy served by a hub that withholds the newer one cannot move a phone back onto it. Phones that miss the newer policy
   still follow the older one until they see the newer; this is the withholding limit the spec accepts.

## What each file is

- **Catalog key (id 1):** signs `catalog.pmup`, the list of packs a hub may offer. `sign-catalog` and `sign-baseline` use it.
- **Recovery key (id 100):** signs only `recovery.pmup`. Keep it apart from the catalog key and use it rarely. `sign-recovery` uses it.
- **`public-keys.txt`:** `catalog key_id=1 public_key=...`, `recovery key_id=100 public_key=...`, optional `burnt public_key=...` lines, and
  `# status: TEST` comment lines. The app treats a file as TEST when the comment says so or when it holds any of the known TEST keys,
  so deleting the comment changes nothing.
- **`baseline-catalog.pmup`:** the catalog of the four packs every build carries, signed by the catalog key. A fresh install trusts it first.
