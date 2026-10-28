# Update signing keys: the owner's steps

Hub pack updates (decisions 45, 61, 62, 70) are signed by two keys only the owner holds. This page is what the owner does, in order,
when the real keys exist. Until then the app carries **TEST keys**, and a release build refuses them.

## Where things stand until the real keys arrive

- `android/core/src/main/resources/update/public-keys.txt` holds TEST keys (`# status: TEST`). Their private parts follow from a public
  label, so anyone can sign with them.
- **Release builds** (the `release` build type) refuse TEST keys: updates are off, the Updates screen says "Updates are not set up in this
  version.", and nothing can be downloaded. Debug, minified debug and demo builds trust them (for tests); their Updates screen shows
  "TEST keys. For testing only."
- The demo's own keys are made fresh on the phone at every start. No private key is derived from a public label in any file that is built
  into an app or the hub. The deterministic TEST keys exist only in test sources.
- A signed release can therefore go out before the real keys arrive. The only thing it cannot do is receive hub updates.

## When the real keys arrive

1. **Make the keys** on an offline computer with `make-update-keys.sh` (see `phonemesh-update-keys/README.md`). Bring back only
   `public-keys.txt`. The script writes the key files with 2048 PBKDF2 rounds (macOS's openssl also uses SHA-1): protect each with a long
   passphrase (five random words or more) and keep the folder offline on two sticks.
2. **Swap, in one step.** On a computer with the repository, the encrypted catalog key (`catalog-key.pem`) and its passphrase:

   ```sh
   cd android
   ./gradlew :hub:installDist
   hub/build/install/phonemesh-hub/bin/phonemesh-hub sign-baseline \
     --public-keys /path/to/public-keys.txt \
     --key /path/to/catalog-key.pem \
     --list hub/update-baseline.list \
     --packs model-packs \
     --out core/src/main/resources/update
   ```

   It asks for the passphrase at the terminal. It refuses TEST keys, a key that is not the catalog key in `public-keys.txt`, and a list that
   does not name exactly the packs this build carries. It writes `public-keys.txt` and a re-signed `baseline-catalog.pmup` into
   `core/src/main/resources/update` together, after proving that the pair opens a fresh phone state. Nothing else needs editing: no
   Gradle flag, no source change.
3. **Check and commit.** Run `./gradlew :core:test :hub:test :app:testReleaseUnitTest`. The tests that name the TEST keys step aside
   by themselves once the file holds other keys; the others check that the pair opens a fresh state. Commit `public-keys.txt` and
   `baseline-catalog.pmup` together and build the release. In a release build updates now run.
4. **Phones and hubs that held the TEST keys** need nothing from the owner. A phone records which keys its update state was made under;
   when the app starts with other keys it sets the old state aside and starts from the new baseline (the TEST catalog key is gone from it;
   version floors are rebuilt from the packs installed). A hub does the same at its next `import-updates`, and `update-status` says so
   before that. If the baseline does not match the keys, the phone says "Updates are off. This build is not ready." and the hub tool
   refuses; neither says "Saved data is damaged".
5. **First release under the real keys.** Write a pack list (`hub/update-baseline.list` shows the format) with `epoch = 1` and a
   `sequence` above 1, sign it with `sign-catalog`, and bring it to hubs on a stick.

## If the catalog key is lost or stolen (rotation)

1. Make a new catalog key pair (run the script again; give the new catalog key id 2, 3, ... in `public-keys.txt`).
2. Sign a recovery policy with the **recovery** key, offline:

   ```sh
   phonemesh-hub sign-recovery --key recovery-key.pem --public-keys public-keys.txt \
     --new-catalog-key <66 hex digits of the new catalog key> --new-key-id 2 --epoch 2 --min-sequence 1 --out recovery-1
   ```

   `--epoch` must be higher than any epoch used before. It writes `recovery-1/recovery.pmup`.
3. Sign the first catalog of the new epoch with the new key (`epoch = 2`, `sequence` at least `--min-sequence`) and put `recovery.pmup`
   beside `catalog.pmup` on the stick. Hubs and phones that see the policy first adopt the new key; catalogs of the old key stop.
4. In the next app version put the new catalog key in `public-keys.txt` and add the old catalog key as a line
   `burnt public_key=<66 hex digits>`. Run `sign-baseline` again (the baseline's epoch is then 2). A burnt key is never adopted again, so an
   old recovery policy served by a hub that withholds the newer one cannot move a phone back onto it. Phones that miss the newer policy
   still follow the older one until they see the newer; this is the withholding limit the spec accepts.

## What each file is

- **Catalog key (id 1):** signs `catalog.pmup`, the list of packs a hub may offer. `sign-catalog` and `sign-baseline` use it.
- **Recovery key (id 100):** signs only `recovery.pmup`. Keep it apart from the catalog key and use it rarely. `sign-recovery` uses it.
- **`public-keys.txt`:** `catalog key_id=1 public_key=...`, `recovery key_id=100 public_key=...`, optional `burnt public_key=...` lines, and
  `# status: TEST` comment lines. The app treats a file as TEST when the comment says so or when it holds any of the known TEST keys,
  so deleting the comment changes nothing.
- **`baseline-catalog.pmup`:** the catalog of the four packs every build carries, signed by the catalog key. A fresh install trusts it first.
