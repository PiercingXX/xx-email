# xx-email Architecture (v0.1)

Original Kotlin/Jetpack Compose client. Backend: Gmail REST API. No Play Services, no Firebase,
no analytics, no ads SDKs, no third-party servers.

## Stack
- Kotlin 2.0.x, Compose BOM, Material 3, Navigation-Compose, single Activity.
- Room (plaintext v1; relies on platform FBE + app sandbox; SQLCipher injection point planned).
- DataStore Preferences for settings; AuthState blobs encrypted via AndroidKeyStore AES/GCM.
- Retrofit/OkHttp + kotlinx.serialization for Gmail REST.
- AppAuth (net.openid:appauth) for OAuth2 PKCE; Chrome Custom Tabs; custom scheme redirect.
- Eclipse Angus Mail (jakarta.mail) for RFC822/MIME composition → base64url `raw`.
- WorkManager: periodic sync, outbox send, scheduled send, snooze wake.

## Trust boundaries / privacy posture
- Network egress ONLY to accounts.google.com / oauth2.googleapis.com / gmail.googleapis.com.
- Scope: `openid email profile gmail.modify`. Consequence: the app CANNOT permanently delete
  mail (no bypass-trash) — enforced by Google, not by our good intentions.
- No telemetry, crash reporting, ad SDKs, or analytics of any kind. Errors stay in local logs.
- Remote images in emails are blocked by default (tracking-pixel defense); per-message load.
- Tokens live only on-device, encrypted with a Keystore-wrapped key; never synced/exported.
- Bring-your-own Google Cloud OAuth client ID: the developer never holds a middleman credential;
  the user's device talks straight to Google under the user's own Cloud project quota.

## Modules (single :app module, layered packages)
```
dev.xxemail
├── XxEmailApp        Application: graph init, channels, work scheduling
├── MainActivity      Single activity; OAuth redirect intake
├── di/AppGraph       Manual DI graph (no framework)
├── data.auth         OAuthConfig, TokenStore (Keystore AES/GCM), AuthRepository
├── data.api          GmailApi (Retrofit), DTOs, GmailApiFactory (token interceptor), MimeComposer
├── data.db           Entities, DAOs, XxEmailDb (+FTS4)
├── data.repo         MailRepository (sync+actions), AccountRepository, SettingsRepository
├── domain            Folder model, category↔label mapping, pure logic
├── sync              SyncWorker, OutboxWorker, ScheduledSendWorker, SnoozeWorker, SyncScheduler
├── notify            Notifier (channels, grouped new-mail notifications)
└── ui                theme, nav, mailbox, thread, compose, search, settings, onboarding, components
```

## Sync design
- Periodic WorkManager (≥15 min floor, network-constrained) + foreground/explicit refresh.
- Delta: `history.list(startHistoryId)` (cost 2 units) → affected thread IDs → `threads.get`
  rebuild (cost 10/thread, capped per run, overflow triggers immediate follow-up sync).
- 404 on history ⇒ full resync of recent INBOX pages; `historyId` persisted per account.
- Bodies fetched lazily (`format=metadata` on sync; `format=full` on open).
- Quota math: idle account ≈ tens of units/hour vs 6,000/min/user budget.

## Outbox patterns (undo-send / schedule-send / snooze)
Single `outbox` table + named unique WorkManager jobs:
- SEND: enqueued with initialDelay = undo window (5–30 s); cancel-by-tag = undo.
- SCHEDULED_SEND: initialDelay = target time; editable/cancelable while queued.
- SNOOZE_WAKE: at wake time re-adds `INBOX` via threads.modify.
Honest failure modes documented: device off/deep Doze ⇒ late send/wake; snoozed state is
local-only (INBOX removed server-side so it leaves every client's inbox meanwhile).

## Verification status
- Deterministic gates run in CI-less env: XML well-formedness, JVM unit tests (MIME composer,
  folder/category mapping), full `assembleDebug` Gradle build.
- NOT yet verified here (requires hardware): on-device install, live OAuth round-trip,
  real-network sync. Tracked in WORKFLOW_STATE.md.
