# Cleanroom Feature Spec — Gmail for Android (user-visible behavior)

> Produced by an independent research subagent from PUBLIC documentation/help pages and the
> public Gmail REST API reference only. No code, assets, or APK internals were used.
> Purpose: define parity targets for our ORIGINAL client (`dev.xxemail`).

## Core behavior map (feature → public API capability)
| Feature | Mapping |
|---|---|
| Inbox tabs Primary/Social/Promotions/Updates/Forums | `labelIds=[INBOX, CATEGORY_*]` filters on threads.list |
| Conversation threading | `threads.*`; RFC2822 References/In-Reply-To on send |
| Archive | `threads.modify` remove `INBOX` |
| Delete | `messages.trash` / `threads.trash` (auto-expunge ~30d) |
| Permanent delete | requires full `mail.google.com` scope — WE DELIBERATELY SKIP |
| Star | `modify` ± `STARRED` (single star; colored stars NOT exposed via API) |
| Mark read/unread | `modify` ± `UNREAD` |
| Labels CRUD/nesting/colors | `users.labels.*` |
| Move to label | `modify`: add label + remove INBOX |
| Search | `q` passthrough; operators from/to/cc/bcc/subject/after:/before:/older_than:/newer_than:/has:attachment/filename:/OR/{}/()/-/"" |
| Drafts | `users.drafts.*` |
| Send | `messages.send` (RFC822 base64url `raw`) |
| Undo send | fully client-side hold timer (5/10/20/30 s options) |
| Schedule send | NO REST support (issue 140922183 open) → client-side scheduler |
| Snooze | NO REST support for state/wake-time (issues 109952618, 287304309) → client-side scheduler |
| Batch select ops | `batchModify` |
| Incremental sync | `history.list` from stored `historyId` (404 ⇒ full resync) |
| Vacation responder | `users.settings.getVacation/updateVacation` (`gmail.settings.basic`) |
| Signature | `users.settings.sendAs.*` |
| Report spam | `modify` ± `SPAM` |
| Attachments | `messages.attachments.get` base64url |

## Smart features classification
SERVER-SIDE-ONLY (Google ML — skipped by design in xx-email): Smart Reply, Smart Compose,
summary cards, tab *assignment* ML, Priority Inbox ranking, nudges, Gemini, spam/phishing ML,
search relevance ranking.
CLIENT-SIDE REPLICABLE: threading, tab UI via label filters, undo timer, swipes, snooze/schedule
queues, offline cache, bulk ops, operator search passthrough.

## Tracking/privacy surfaces of the official app (why users want alternatives)
- Requires the Google/Play stack (Play Store distribution, GMS sign-in, FCM push).
- Pre-2017 consumer mail scanning for ads ended, but ads remain from other signals.
- Consent-gated "smart features" toggles reuse mail content/activity across Gmail/Workspace/
  other Google products (Smart features, Calendar extraction, Wallet passes, Gemini).
- Deep device integrations (Calendar/Wallet/Assistant/Gemini) over mail data.
- Product telemetry/crash reporting to Google under standard policy (inventory not public).
Sources: https://support.google.com/mail/answer/15604322 ,
https://blog.google/products-and-platforms/products/gmail/g-suite-gains-traction-in-the-enterprise-g-suites-gmail-and-consumer-gmail-to-more-closely-align/

## Timings & constants worth replicating
- Undo send: 5/10/20/30 s (default 5).
- Snooze presets: Later today (~+4 h capped 18:00), Tomorrow 08:00, Next week Mon 08:00,
  This weekend Sat 08:00, custom picker.
- Schedule presets: Tomorrow 08:00, Tomorrow 13:00, Monday 08:00, custom.
- Default sync window: 30 days; trash expunge ~30 d; vacation cadence ≤1 reply/sender/4 d.
- Swipes: per-direction assignment (Archive/Delete/Read-unread/Move/Snooze/None), default
  archive-flavored; destructive swipes show undo snackbar.
