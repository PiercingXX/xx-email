# XX-Email

> Gmail without the proprietary Google blob.

Tabs, snooze, undo-send, operator search. Speaks the Gmail REST API from the
device. No Play Services, no Firebase, no analytics, no ads.

Cleanroom — specified from public docs, original code.
See `docs/cleanroom-gmail.md`.

Network only to `accounts.google.com`, `oauth2.googleapis.com`,
`gmail.googleapis.com`. Scope is `gmail.modify` — this app **cannot**
permanently delete mail. Tokens in the Android Keystore.

You bring your own OAuth client ID. Walkthrough:
[docs/oauth-setup.md](docs/oauth-setup.md).

Polling, not push — new mail can take up to ~15 minutes. Pull-to-refresh is
immediate. **If you uninstall while mail is snoozed, it will not return to
the inbox by itself.** Unsnooze first.

No Smart Reply. No Gemini. No tracking pixels by default.

```
v0.1.1 · Compose · GPL-3.0-or-later
```

## Build

```sh
./gradlew assembleDebug
./gradlew testDebugUnitTest
```

See `PRIVACY.md`.
