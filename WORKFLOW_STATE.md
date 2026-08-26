# WORKFLOW_STATE — xx-email

Original, telemetry-free Android email client ("XX Email") speaking Gmail REST API.
Package: `dev.xxemail`. License target: GPL-3.0-or-later. minSdk 26 / target 35.

## Decisions locked (from cleanroom research, see docs/)
- Auth: AppAuth + PKCE, custom scheme `dev.xxemail`, NO Play Services, BYO client ID.
- Scopes: `openid email profile gmail.modify` ONLY. No `mail.google.com` => no permanent delete (trash-only by design).
- Tokens: serialized AuthState JSON encrypted with AndroidKeyStore AES/GCM (security-crypto is deprecated; Tink deferred).
- Sync: WorkManager poll (15-min floor) + history.list delta (404 => full resync). No push v1 (needs Pub/Sub relay).
- Snooze / scheduled send / undo-send: local outbox table + OneTimeWorkRequest; cancel-on-undo.
- MIME: Eclipse Angus Mail (jakarta.mail) -> base64url `raw`, sent via uploadType=media.
- Local DB: plaintext Room v3 with explicit migrations (relies on FBE + sandbox); SQLCipher hook point documented.
- Remote images blocked by default in message view (tracking-pixel defense).

## Status log
- [x] Env probe: JDK17, SDK platforms 34-36, network OK, empty project dir.
- [x] Cleanroom research x3 (TB/K-9, Gmail app, architecture) -> docs/.
- [x] Project scaffold + full v0.1 implementation (35 Kotlin files, 10 XML resources).
- [x] Gates: XML validation PASS · 14/14 JVM unit tests PASS · `assembleDebug` PASS
      (19.5 MB APK) · `assembleRelease` R8 PASS (2.75 MB unsigned) · review-code pass:
      S0 proguard file created, S1 attachment size cap added + regression test,
      S1 snooze-uninstall caveat documented.
- [x] Docs: README (+feature matrix), PRIVACY.md, docs/oauth-setup.md, GPL-3.0 LICENSE.
- [x] Phases A–F implemented (historyId strings + delta checkpoints, send-retry policy,
      AppAuth redirect rework, token cache/durability, safe paths, durable snooze,
      file-backed outbox, Room v3 explicit migrations, label-union threads, compose/send
      fixes, shell/error UX, remote-image gate); JVM unit tests green.
      Phase G device checks remain pending.
- [x] Phase G unit tests consolidated: 103 tests / 0 failures; `assembleDebug` +
      R8 `assembleRelease` green. TokenStore rename-failure path not JVM-testable
      (AndroidKeyStore-bound) — needs instrumentation.
- [x] Phase H docs honesty pass: README matrix matches code (drafts folder-only,
      notifications device-pending, FTS = subject/snippet/from/to only), PRIVACY
      TokenStore path fixed, oauth-setup verified against B2 implementation.
- [x] Post-implementation review (cumulative diff) — fixed: atomic outbox claim
      (`claimIfQueued`) closing the undo-vs-send race; SENDING rows stranded by process
      death now recover to FAILED("send interrupted") + banner retry;
      `CancellationException` no longer strands QUEUED rows; delta checkpoint never
      advances past unprocessed items or failed `threads.get`/`messages.get` lookups
      (rewind + follow-up sync).
- [x] Post-review residual findings fixed: label filters switched from SQLite `LIKE`
      to byte-exact `instr()` (case-fold + `%`/`_` wildcard cross-match gone);
      `MailRepository.fullCache` mutex-guarded against concurrent IO mutation;
      `TokenCache.clear()` bumps a per-account generation so a stale in-flight refresh
      neither persists nor recaches over fresh tokens (regression test added);
      MainActivity validates redirect OAuth `state` against the outstanding request —
      forged AppAuth-shaped intents are logged and dropped.
- [x] Phase G androidTest surface added: `MigrationTest`
      (v1→2 and v1→3 via `MigrationTestHelper`; asserts historyId INTEGER→TEXT,
      queued-send rfc822Base64 survival, snoozedUntil survival, snooze_wakes +
      folder_pages + messages.attachmentsJson) and `TokenStoreTest` (round-trip,
      remove isolation, failed-persist durability with read-only dir). Schemas wired
      into androidTest assets; runner + room-testing/test deps in catalog.
      `compileDebugAndroidTestKotlin` + `testDebugUnitTest` PASS. Device run pending.
- [x] Post-TODO re-review (v0.1.1): 200+undecodable send now marks SENT (no duplicate
      retry); cancellation mid-send marks FAILED not QUEUED; multipart send uses raw
      RFC822 octets not base64; Forward passes the quoted message id; applyLabel is
      local-first; token persist clears the unreadable flag; remote images HTTPS-only
      + size cap; historyId accepts numeric JSON.

## Known gaps / next work

Everything still open — ship blockers through nits, ordered — lives in
**[TODO.md](TODO.md)**. Do not call v0.1.1 good to go until the Phase G live checks
have run on a device.

Leftover notes not duplicated there:
- Thread hydration is sequential `threads.get` calls (quota-fine; latency only;
  listed as non-blocking roadmap in TODO.md).

