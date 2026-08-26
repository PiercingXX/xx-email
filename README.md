# XX Email

An **original, open-source, telemetry-free** Android email client for Gmail.
Gmail's power features — none of the surveillance-flavored extras.

```
Status: v0.1.0 · Kotlin 2.0 · Jetpack Compose · GPL-3.0-or-later
Build:  ./gradlew assembleDebug   → app/build/outputs/apk/debug/app-debug.apk
Tests:  ./gradlew testDebugUnitTest
```

## Why

The official Gmail app requires the Google/Play stack, ships telemetry, and wires your
inbox into ad/personalization and "smart feature" data reuse. Open-source clients like
K-9/Thunderbird are excellent but lack Gmail's workflow features (tabs, snooze, undo-send,
operator search). XX Email is the middle path: **a cleanroom-built client that speaks the
official Gmail REST API directly from your device**, with no analytics SDKs, no ads, no
Firebase, no Play Services dependency.

Cleanroom discipline: features were specified from public documentation only
(see `docs/cleanroom-gmail.md`, `docs/cleanroom-thunderbird-k9.md`). All code is original.

## Feature matrix vs Gmail for Android

| Feature | Status | How |
|---|---|---|
| Inbox tabs (Primary/Social/Promotions/Updates/Forums) | ✅ | `CATEGORY_*` label filters over threads API |
| Conversation threading | ✅ | server threads + local aggregate cache |
| Archive / Trash / Report spam | ✅ | `threads.modify` / `threads.trash` (+ undo) |
| Star, mark read/unread, labels | ✅ | label modify ops |
| Multi-select bulk actions | ✅ | long-press selection bar |
| Undo send (5–30 s) | ✅ | local outbox hold + cancellable worker |
| Schedule send (presets) | ✅ | client-side scheduler (no REST support upstream) |
| Snooze (later today/tomorrow/next week) | ✅ | local scheduler; INBOX removed meanwhile |
| Swipe actions (configurable L/R) | ✅ | archive/delete/read/star/snooze |
| Search with operators (`from:`, `has:attachment`…) | ✅ | server `q` passthrough + offline FTS index |
| Multiple accounts + unified switcher | ✅ | independent OAuth grants |
| New-mail notifications (grouped) | ✅ | WorkManager poll (15-min floor) |
| Attachments (view/download/send) | ✅ | base64url media upload/download |
| Remote-image blocking (tracking pixels) | ✅ default-on | HTML sanitizer |
| Encrypted token storage | ✅ | AndroidKeyStore AES-GCM |
| No telemetry / ads / crash-reporting | ✅ | none in codebase; auditable |
| Drafts sync | 🚧 roadmap | drafts API mapped, UI pending |
| Vacation responder editor | 🚧 roadmap | settings endpoint mapped, UI pending |
| Push notifications | ❌ by design | needs Pub/Sub relay server; see docs/architecture.md |
| Smart Reply/Compose/nudges/Gemini | ❌ by design | Google-server ML; excluded intentionally |
| Permanent delete | ❌ by design | scope deliberately excludes it (trash-only) |

## Privacy guarantees (short version)

- Network egress only to `accounts.google.com`, `oauth2.googleapis.com`, `gmail.googleapis.com`.
- Scope: `gmail.modify` only — the app **cannot** permanently delete your mail.
- Zero third-party SDKs. Zero telemetry. Errors stay on-device.
- Tokens encrypted at rest (Keystore); cloud backup of credentials/cache disabled.
- See `PRIVACY.md`.

## Setup (one time)

You create your own free Google Cloud OAuth client ID so there is no middleman:
full walkthrough in **[docs/oauth-setup.md](docs/oauth-setup.md)** (~5 minutes).

Known trade-offs (honest ones):
- Polling sync means new-mail latency up to ~15 min (Android background-execution floor).
  Pull-to-refresh and app-open sync are immediate.
- Scheduled sends/snoozes fire when your device is awake enough to run them.
- While snoozed, mail leaves every client's inbox (INBOX removed server-side);
  wake state itself lives only on this device. **If you uninstall the app while mail is
  snoozed, it will not return to your inbox automatically** — it remains reachable via
  All Mail / search on any client. Unsnooze before uninstalling. The same applies when
  removing an account while offline: removal restores snoozed mail to the inbox on a
  best-effort basis and warns if it could not, and if a scheduled wake repeatedly fails
  (e.g. long offline periods) the thread stays in the Snoozed folder where you can
  unsnooze it manually.

## Roadmap

Drafts UI · vacation responder editor · per-label notification rules · SQLCipher opt-in
encryption · IMAP/JMAP backends · OpenPGP via OpenKeychain · self-hosted push relay
(Gmail watch → UnifiedPush).

## License

GPL-3.0-or-later. See `LICENSE`.
