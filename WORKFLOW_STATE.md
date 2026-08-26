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
- Local DB: plaintext Room v1 (relies on FBE + sandbox); SQLCipher hook point documented for v2.
- Remote images blocked by default in message view (tracking-pixel defense).

## Status log
- [x] Env probe: JDK17, SDK platforms 34-36, network OK, empty project dir.
- [x] Cleanroom research x3 (TB/K-9, Gmail app, architecture) -> docs/.
- [x] Project scaffold + full v0.1 implementation (35 Kotlin files, 10 XML resources).
- [x] Gates: XML validation PASS · 14/14 JVM unit tests PASS · `assembleDebug` PASS
      (19.5 MB APK) · `assembleRelease` R8 PASS (2.75 MB unsigned) · review-code pass:
      S0 proguard file created, S1 attachment size cap added + regression test,
      S1 snooze-uninstall caveat documented; S2/S3 items tracked below.
- [x] Docs: README (+feature matrix), PRIVACY.md, docs/oauth-setup.md, GPL-3.0 LICENSE.

## Known gaps / roadmap (S2/S3 from review)
Full fix list (ship blockers through nits, ordered for implementation): **[TODO.md](TODO.md)**.
Do not call v0.1.1 good to go until Phase A–C are done and Phase G live checks have run.

Leftover notes not duplicated there:
- Thread hydration is sequential `threads.get` calls (quota-fine; latency only; not a v0.1.1 blocker).
- Notification small-icon uses the adaptive foreground (covered in TODO F8).

