# Scout Mesh Navigation

Android navigation client with an embedded WireGuard transport and separate Google-services-independent and Play distribution variants.

**Status: draft engineering checkpoint, not a production release.** Secure enrollment, password-protected device identities, recurring payment verification, and paid API enforcement are not implemented. Preview and release builds deliberately block the legacy enrollment path. The privacy issues below must be resolved before public distribution.

## Included in this checkpoint

- Android source in `navigation/android`, including the shared `frontend-ui` module.
- `foss` and `play` flavors, Android 8.0/API 26 minimum, compile/target API 36.
- FOSS runtime dependency checks excluding GMS, Firebase, Play Billing/Core, and Android Auto. Play-only Android Auto sources and manifest are isolated under `app/src/play`.
- Non-debuggable, debug-signed previews with separate `.preview` application IDs; unsigned release outputs unless the operator supplies all required signing variables.
- Embedded WireGuard GoBackend, Scout-only split routes (`10.66.0.0/16`), user VPN disclosure, and serialized terminal teardown. No fake TUN-only connected state.
- HTTPS-only preview/release networking, developer controls disabled, tracking/analytics off by default, and a reserved default server (`https://mesh.invalid`).
- Initial consent-revision request fencing, guarded callback/executor handoffs, and stale location-listener checks.
- Endpoint, distribution, Activity, request-scope, tunnel configuration, and session-state regression tests.

This is an Android-only import from the working tree of [the original Scout repository](https://github.com/scout-hazard-system/routing-scouting-app-to-be-named), whose base was `cb86b2bc`. Existing backend, Linux hub/peer, Windows administration, model, and map-server work is **not included** in this checkpoint. No SDKs, private keys, keystores, APKs, or AABs are committed. Original licensing and attribution are retained in `LICENSE`, `NOTICE`, and `LICENSES/README.md`; references there to other Scout subsystems do not mean those subsystems are bundled here.

## Validation status

Latest source validation on 2026-09-07:

- **98 unit tests passed**: 49 FOSS preview and 49 Play preview tests; Android-dependent cases cover Robolectric API 26 and 36.
- FOSS dependency audit passed for preview and release runtime graphs.
- FOSS preview lint passed with warnings.
- **Play preview lint fails** with `ForegroundServiceType` at `ScoutMeshVpnService.onStartCommand`, although the merged VPN service declares `specialUse`. Diagnose the service association and add a regression; do not hide it with a blanket lint baseline.
- Independent read-only review identified the three remaining privacy issues below. This is not a production security sign-off.
- Earlier APK/AAB builds predate the latest edits. They are not verified checkpoint artifacts and are not included in this repository.
- No Google-free emulator/device, real VPN transport, Android Auto host, Play upload, or payment-provider sandbox validation has been completed.

## Build and test

Run from the repository root. Use JDK 21 (the validated runtime), the checked-in Gradle 8.13 wrapper, and an installed Android SDK with platform 36 and build-tools 36.0.0. AGP is pinned to 8.13.2. Set `ANDROID_HOME` to that SDK and `GRADLE_USER_HOME` to a local cache. Install official SDK command-line tools and accept the applicable licenses interactively; these tools are not vendored.

```bash
./navigation/android/gradlew --project-dir navigation/android \
  --no-daemon --max-workers=2 --console=plain \
  :app:testFossPreviewUnitTest :app:testPlayPreviewUnitTest \
  :app:verifyFossDependencies

# Play lint currently has the documented foreground-service error.
./navigation/android/gradlew --project-dir navigation/android \
  --no-daemon --max-workers=2 --console=plain --continue \
  :app:lintFossPreview :app:lintPlayPreview

./navigation/android/gradlew --project-dir navigation/android \
  --no-daemon --max-workers=2 --console=plain \
  :app:assembleFossPreview :app:assemblePlayPreview
```

Preview outputs, after a successful build:

- `navigation/android/app/build/outputs/apk/foss/preview/scout-foss-preview.apk`
- `navigation/android/app/build/outputs/apk/play/preview/scout-play-preview.apk`

Release tasks are `:app:assembleFossRelease` and `:app:bundlePlayRelease`. Release signing requires **all** of `SCOUT_KEYSTORE_PATH`, `SCOUT_KEYSTORE_PASSWORD`, `SCOUT_KEY_ALIAS`, and `SCOUT_KEY_PASSWORD`; partial configuration fails and missing configuration does not fall back to debug signing. Supply secrets through a secure environment, never command literals or committed files. Preview signing is only for testing and is not a production signing identity.

The obsolete `launch_android_builds.sh` was intentionally not imported: it targeted removed flavors and overwrote device preferences to enable tracking/analytics without user consent. Use the direct Gradle commands above until a safe replacement exists. Debug-only lab opt-ins (`scoutDevControls`, `scoutLabMesh`) are not a secure enrollment implementation and must remain disabled in consumer builds.

## Next steps / TODO

### P0 — Close confirmed privacy gaps before distribution

- [ ] **Guard complete Android Auto root-screen operations.** In `RouteMapScreen`, capture scope before queued route/voice jobs read any fix. Replace raw `lastFix` reads with the consent-aware accessor, retain the original scope through search and catalog upsert, and guard UI/destination publication after revocation or destruction.
  - [ ] Add deterministic capture → revoke → first-request, search → revoke → upsert, and queued-UI → destroy tests.
- [ ] **Publish parsed scenes and routes atomically within the original privacy scope.** `Map3dView.setSceneJson` currently parses and then writes scene/cluster/camera fields off-thread; MainActivity's separate checks cannot prevent a stale result from repopulating a cleared map. Parse into a detached result off-thread, then publish via a guarded main-thread task; apply the same approach to route points.
  - [ ] Pause between callback admission and publication, revoke/change server, resume, and prove the map stays clear and cannot refetch the old coordinates.
- [ ] **Prevent stale assistant cache publication.** Guard or revision-tag `cachedAlertClusterSummary` and context-deque updates. Use one synchronization model for clearing, reading, and writing; a new-server request must not reuse old-server response text.
  - [ ] Test old response → server switch/cache clear → late completion → fresh assistant request.
- [ ] Extend request-scope tests beyond application-interceptor fakes to exercise network-interceptor and actual transport timing, response closure, and cancellation.
- [ ] Audit coordinate-bearing logs, transient UI state, request identifiers, and personalization storage; ensure consent covers any third-party web lookup.

### P1 — Finish Android build and runtime verification

- [ ] Resolve the Play `ForegroundServiceType` lint error with evidence; keep both flavors' merged-manifest assertions.
- [ ] Add a checksum-pinned SDK bootstrap, explicit license-acceptance flow, build/verification scripts, and a safe launcher that never presets consent or account credentials; test the scripts.
- [ ] Rebuild both previews, FOSS release APK, and Play release AAB from the final revision; record checksums, signing state, and manifest/dependency separation.
- [ ] Check APK ZIP alignment (`zipalign -c -P 16 4`), every packaged 64-bit ELF `PT_LOAD` alignment, and AAB 16-KiB delivery configuration. Earlier library/package checks must be repeated on final artifacts.
- [ ] Run the FOSS app on a Google-free API 36 emulator and a real supported device; cover missing location/TTS services, denied permissions, rotation, revocation, and pause/resume.
- [ ] Exercise real GoBackend startup, permission revocation, notification denial, disconnect/reconnect, and destruction races. State-machine tests alone do not prove native service ordering.
- [ ] Verify Android Auto behavior, host validation, and lifecycle independently of phone UI tests.
- [ ] Add CI for both flavors, privacy regressions, lint, dependency/license checks, and reproducible artifact verification.

### P2 — Implement secure device enrollment and recurring authorization

- [ ] Bring the required backend/Linux control-plane work into this repository as a separately reviewed change. Treat the existing lab shared-token, hub-generated-key, plaintext-profile, and subscription-metadata code as insecure scaffolding, not working production enforcement.
- [ ] Generate WireGuard and P-256 authentication keys locally. An entry token authorizes enrollment; it must not be used as a private key or password.
- [ ] Use expiring, single-use, account/order-bound enrollment tokens and transactional replay-safe device registration. Device IDs alone must not authorize replacement or recovery.
- [ ] Add a versioned Argon2id + AES-GCM password vault, Android Keystore device binding, bounded KDF parameters, password changes, and explicit lock/unlock/recovery flows. Require unlock after process restart; keep unlocked credentials in memory only.
- [ ] Implement proof-bound challenge/reauthentication and DPoP-bound short-lived credentials, revocation, renewal backoff/freshness limits, and lock-triggered cancellation/tunnel teardown.
- [ ] Verify Stripe signatures and canonical paid state; LINE Pay authenticated capture/refund state; and ETH/USDC wallet ownership plus expected chain, asset, recipient, amount, finality, and deduplication. Reauthentication must never trigger charges.
- [ ] Enforce identity, entitlement, and revocation centrally on paid HTTP and streaming APIs; close LAN/mesh/direct-service bypasses and bind private profile access to the authenticated device/account.
- [ ] Migrate Linux peers to local keys and password-encrypted storage with revocable migration tokens; preserve a separate HTTPS-only, VPN-free Windows administration boundary.
- [ ] Test tampering, replay, concurrent redemption, takeover attempts, copied credentials, billing expiry, provider failures, refunds, and stream revocation. Sandbox/test payments must never grant production access.

### P3 — Release and operations

- [ ] Confirm package ownership, production signing/upload keys, secure backups, key rotation, and versioning.
- [ ] Complete privacy/data-deletion disclosures, third-party notices/source obligations, VPN and foreground-service declarations, and store review requirements.
- [ ] Keep Play consumption-only unless a separately reviewed compliant billing flow is implemented; do not add external payment steering.
- [ ] Distinguish Google-free OS compatibility from official F-Droid catalog inclusion, and a buildable AAB from Play acceptance.
- [ ] Configure production HTTPS origins and provider accounts only after the security gates pass. No live charges, hub/firewall changes, deployment activation, or publishing without separate approval.
