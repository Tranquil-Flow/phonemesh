# Owner-only v0.1 publication

**Status (2026-10-25): v0.1.0 is published** following this checklist: a fresh public
history, the GitHub release with both public APKs, checksums and the source ZIP, and the
GitHub Pages add page and association. The text below is kept as the procedure.

**Preparation instructions, not permission to publish.** No commit, push, tag, release
upload or website deployment is authorized by this document. The candidate remains
unpublished until the owner approves the exact reviewed source and artifacts after
all release gates close. See [candidate notes](releases/v0.1.0.md).

## Decisions already made

- Public v0.1 and subsequent updates use the dedicated owner-controlled release key,
  not Android Debug. Its public certificate SHA-256 is:

  ```text
  ef8557c6133a62388c5263cc26552d4ed3b036acce267a0b841a57bbdf5ecdac
  ```

  The first candidate's two ABI APKs were owner-signed and independently verified.
  That does not qualify changed source: any replacement candidate needs fresh
  owner-local signing and verification. Never put private key material, keystore passwords
  or signing configuration into Git, chat, source archives or release assets. Keep
  encrypted backups under owner control.
- Preserve the existing development-signed phone line and its accounts/history.
  The public signer cannot simply update an unrelated development signer. Do not
  uninstall, clear data or replace those installs to make public signing tests pass.
  Any future migration requires a separately verified data-preserving procedure.
- `https://tranquil-flow.github.io/phonemesh/add#…` is approved for v0.1. This replaces
  the earlier permanent/purchased-domain-before-release requirement, not the need to
  deploy and verify the page and domain association.

## Gate checklist — stop on any unresolved item

1. **Freeze the complete reviewed source set.** Include accounts, Android app/core,
   `android/mesh-protocol/`, `android/radio-ble/`, `android/hub/`, `hub-ble-bridge/`,
   build scripts, lockfiles and required assets. Review new/untracked files as well as
   diffs; a commit containing only tracked changes is not necessarily a complete source
   release. Do not sweep inherited work into a commit without review.
2. **Close dependency/source obligations.** Include `LICENSE`, `docs/dependencies.md`,
   `docs/third-party-meshtastic.md` and the applicable texts/notices in `docs/licenses/`.
   Retain the verified 29-file Meshtastic/nanopb observed-revision match manifest and
   nanopb notice; original nanopb version ambiguity is documented, not guessed. Review corresponding source
   and build information for embedded native dependencies; sherpa recipe pins are not
   independent binary-rebuild proof. Do not distribute optional upscaling weights.
3. **Verify the public-source build.** Follow the documented Android build prerequisites
   and public runtime fetcher. Verify an isolated source-only build without private
   receipts, signing material or development caches as required inputs. Keep evidence
   bound to the frozen candidate. A prior development gate does not qualify changed
   source or substitute for this gate.
4. **Sign and qualify the two final APKs.** Confirm package `org.phonemesh.app`, version
   `0.1.0`, code `1`, schema `15`, ABIs `arm64-v8a` and `armeabi-v7a`, secure release
   screens, no debug/probe overrides, and the release pack catalog. Use owner-local
   signing tools; verify each final APK with Android build-tools
   `apksigner verify --verbose --print-certs <signed-apk>`. Match the certificate above,
   compute SHA-256 on the final signed bytes, and record byte sizes. Unsigned-input
   hashes are not public APK hashes. Qualify fresh installation on an authorized test
   device and applicable same-signer updates; do not use unrelated development installs.
5. **Close final physical gates.** Record actual voice, hub-only, restart/recovery and
   security journeys against the exact candidate. Separate production Wi-Fi qualification
   from BLE bridge results and local tests from field range/capacity. Skips remain skips.
6. **Review the release set.** Publish only the qualified ABI-labelled APKs, their
   checksum manifest, reviewed source/corresponding-source information, applicable
   licences/notices and honest `docs/releases/v0.1.0.md`. Exclude local receipts,
   caches/build trees, operator config/cards/logs/mail, keys, device identifiers and
   unreviewed model assets. Update candidate notes only with verified outcomes.

## Git and release actions — owner only, after the gates

The owner chose a **fresh public history** on 2026-10-25: create the future public
repository from the reviewed corresponding-source set, with a new root commit, while
keeping the existing local repository/history private and intact. Do not copy `.git`,
old objects, operator data or local receipts into that future public repository. This
supersedes the earlier plan to publish the existing history; it does not authorize any
Git mutation or publication now. No history rewrite, force-push or broad staging
(`git add .`, `git add -A`, whole-tree globs).

After separate explicit approval, prepare an isolated public repository, an explicit
source list and the approved public author identity. Inspect the staged names/content
before each commit and verify its tree against the qualified manifest. For example,
the documentation slice has these explicit paths (not the complete application set):

```sh
git add -- docs/third-party-meshtastic.md docs/dependencies.md \
  docs/releases/v0.1.0.md docs/publication.md android/hub/README.md
git diff --cached --name-status
git diff --cached
```

The owner then commits the complete approved slices, confirms the release commit
matches the qualified source, and performs an ordinary push to the explicitly chosen
remote/branch. Create the `v0.1.0` tag only on that reviewed release commit, push that
specific tag, and upload the exact qualified assets. Never move an existing published
tag to hide a mismatch. Read back the remote commit/tag and download the published
assets to verify hashes and signing certificate before announcing availability.

## GitHub Pages — two distinct deployment targets

The static staging layout contains `phonemesh/add/index.html` and
`.well-known/assetlinks.json`. These map to:

- Project page: `https://tranquil-flow.github.io/phonemesh/add`
- **Domain-root** association: `https://tranquil-flow.github.io/.well-known/assetlinks.json`

Putting the association only at `/phonemesh/.well-known/assetlinks.json` is insufficient.
The owner must deploy it through the domain-root Pages site (normally the
`tranquil-flow.github.io` site repository), while preserving unrelated site content.
Ensure the Pages publishing method serves dot-directories, for example with `.nojekyll`
when using Jekyll-free static branch publication. Do not publish staging directories
wholesale. The association must name `org.phonemesh.app`, the public signer only, and
`delegate_permission/common.handle_all_urls`; it must not authorize the development
certificate.

Both hosted endpoints returned **404 at the last recorded check**; local staging is
not deployment. After explicit owner deployment, fetch both exact HTTPS URLs: require
successful responses, valid JSON/appropriate content type for the association, no
association redirect, and the expected package/certificate/relation. Inspect the add
page's fragment handling and test a non-sensitive sample link on the final public-signed
app, including Android's verified-link state and fallback when the app is absent.
Do not use or publish a real person's contact link as test evidence. Keep publication
blocked if the root association or page remains unavailable.
