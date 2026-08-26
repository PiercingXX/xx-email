# Cleanroom Feature Spec — Thunderbird for Android / K-9 Mail ecosystem

> Produced by an independent research subagent from PUBLIC documentation only.
> No code, assets, or UI text were copied. All statements paraphrased; source URLs cited.
> Purpose: inform the design of our ORIGINAL client (`dev.xxemail`).

## Lineage facts
- Thunderbird for Android is a rebrand/continuation of K-9 Mail. Mozilla (MZLA) acquired
  K-9 Mail in June 2022 and hired its maintainer; both apps build from one repository
  (`thunderbird/thunderbird-android`) as two product flavors.
  https://blog.thunderbird.net/2022/06/revealed-thunderbird-on-android-plans-k9/
- Both are Apache-2.0 licensed (desktop Thunderbird is MPL-2.0 — different product).
  https://github.com/thunderbird/thunderbird-android/blob/main/LICENSE
- Stable TB Android 8.0 shipped Oct 2024; distributed via Play, F-Droid, GitHub.
  https://blog.thunderbird.net/2024/10/thunderbird-for-android-8-0-takes-flight/

## Feature inventory (behavioral, own words)
- **Accounts**: unlimited; IMAP/POP3/SMTP; OAuth for Gmail/Yahoo/AOL/Outlook; passwords,
  CRAM-MD5, client certs otherwise. Per-account color identity; special-folder mapping
  (Archive/Drafts/Sent/Spam/Trash). Import/export settings; QR import (TB flavor).
- **Inbox UX**: unified inbox across accounts; unread counts; folder-local threaded view
  (explicitly NOT cross-folder conversations); per-direction configurable swipe actions
  (archive/delete/spam/move/star/read-unread/select/none); density options; split-screen.
- **Message ops**: delete/archive/spam/move/copy/star/read-unread; multi-select bulk bar;
  reply variants; confirm-action toggles. NO snooze, NO scheduled send, NO undo stack.
- **Compose**: identities with plain-text signatures; BCC-to-self; drafts to Drafts folder;
  OpenPGP via OpenKeychain + Autocrypt; attachments save. No HTML signatures (top complaint).
- **Search**: local search over chosen folders; optional IMAP server-side search limited to
  subject/sender with result caps; NO operator syntax.
- **Notifications**: per-account channels; Quiet Time; lock-screen granularity; contacts-only;
  vibration/LED options; per-folder notification class.
- **Sync model**: per-folder class (display/poll/push/notify ∈ None/1st/2nd); push = IMAP IDLE,
  one connection per pushed folder, off by default, documented battery/server-cap pitfalls.
- **Widgets**: single unread-count widget family.
- **Telemetry**: K-9 zero telemetry; TB Android consent-gated telemetry with public probe
  dictionary, opt-out-as-deletion, identifier rotation. https://support.mozilla.org/en-US/kb/thunderbird-android-telemetry

## Architecture patterns worth emulating (conceptual)
1. Local-first mirror: Account → Folder → Message DB; UI reads only local store; ops queue
   offline and reconcile later.
2. Bounded mirroring: caps per folder, lazy full-body download, optional non-propagation of
   server deletions.
3. One importance dial per folder driving four behaviors (visibility/poll/push/notify).
4. Protocol abstraction layer so new backends (JMAP/EWS) plug into the same model.
5. Public ADR culture + public roadmap.

## Gaps our client should beat
1. No true conversation view (folder-local only).
2. No undo anywhere (multi-year complaint threads).
3. Weak search (no operators, buried server search).
4. Notification/sync reliability pain (dedicated 2026 roadmap program exists because of it).
5. Plain-text-only signatures.
6. No Exchange EWS on Android.
7. Same-domain avatar ambiguity in account switching.
8. Settings overwhelm (hundreds of flat options).
9. Widget poverty; no snooze/tabs/bundles for high-volume inboxes.

## Adopt list (concrete, for xx-email)
Local-first + offline op queue; undo snackbar on EVERY destructive/moving action; true
cross-folder conversations; remote-image confirmation + tracker stripping; per-account
channels with in-app quiet-hours UI; distinct colored account identities showing full address;
rich operator search day one; OpenPGP-out-of-process posture later; QR device migration later;
public ADRs.

## Avoid list
Folder-local threading masquerading as conversations; silent destructive gestures without
undo; burying sync controls; plain-text-only signatures; delegating notification tuning
entirely to OS screens; flat hundred-option settings.
