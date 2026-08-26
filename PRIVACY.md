# Privacy Policy — XX Email

Short version: **your mail never touches anyone but you and Google.**

## What leaves your device

Only what is strictly required to read and send your own email:

1. OAuth tokens exchanged directly with `accounts.google.com` / `oauth2.googleapis.com`.
2. Gmail API calls to `gmail.googleapis.com` for the mail you view or send.

With remote images off (the default), the app itself only talks to those three Google
hosts. Turning on “Load remote images” fetches HTTPS image URLs from message HTML —
that is the only optional extra destination. No analytics, no crash reporting,
no advertising identifiers, no Firebase, no Google Play Services requirement.

## What stays on your device

- OAuth tokens, encrypted with an AndroidKeyStore AES-GCM key
  (`app/src/main/java/dev/xxemail/data/auth/TokenStore.kt`).
- Cached mail metadata/bodies in a local Room database (plaintext, protected by Android
  file-based encryption + app sandbox; opt-in SQLCipher hardening is on the roadmap).
- Attachment downloads in the app-private cache directory.
- Settings in DataStore.

Cloud backup and device-transfer of credentials/mail cache are **disabled**
(`res/xml/backup_rules.xml`, `res/xml/data_extraction_rules.xml`).

## Permissions rationale

| Permission | Why |
|---|---|
| `INTERNET` | talk to Google's mail endpoints |
| `POST_NOTIFICATIONS` | notify you about new mail (asked at runtime, Android 13+) |

Nothing else. No contacts, location, storage, phone, camera.

## Anti-tracking behaviors

- Remote images in emails are NOT loaded unless you turn on "Load remote images" in
  Settings → Appearance. The default is OFF, so tracking pixels stay dead until you
  explicitly accept them.
- `<script>`, inline event handlers, and `javascript:` URLs are stripped before rendering.
- Links open via the system resolver; we add no redirectors or referrers.

## Scopes requested

`openid email profile` + `https://www.googleapis.com/auth/gmail.modify`

Consequences you should like:
- The app can read/send/archive/trash/label your mail.
- It **cannot** permanently delete messages (bypass trash) — Google enforces this for
  everyone without the full-account scope, which we deliberately do not request.
- You revoke everything at <https://myaccount.google.com/permissions> in one click.

## Verification

The entire codebase is open under GPL-3.0-or-later. Grep it:
there is no tracking SDK, and egress hosts are limited to the three Google endpoints above.
