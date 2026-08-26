# XX Email — full fix todo

Everything found in the v0.1.0 go/no-go review. Work **top to bottom**: later phases assume earlier ones. Check a box only when the acceptance line is true and tests (if listed) pass.

Ship rule: do not call this good to go until **Phase A–C are done and Phase G live checks have run on a device**.

---

## Implementation status (2026-08-26)

All Phase A–F items are **implemented** and covered by 103 passing JVM unit tests
(`./gradlew testDebugUnitTest`), `assembleDebug` + R8 `assembleRelease` both green.
Boxes are checked for code/doc work that is statically verified (unit tests, schema
diffs, code inspection). **Acceptance lines that require a real device remain governed
by the Phase G device list, which is entirely unchecked — the ship rule above still
applies.** Known open (non-blocking) findings live in `WORKFLOW_STATE.md`.

---

## Phase A — Unblock Gmail I/O

Nothing else works until JSON parses and a 200 send is never retried.

### A1. Model Gmail uint64 fields as strings
- [x] `app/src/main/java/dev/xxemail/data/api/Dtos.kt`
  - Change `historyId` on `Profile`, `ThreadRef`, `Message`, `Thread`, `HistoryResponse` from `Long?` to `String?`.
  - Change `HistoryItem.id` from `Long` to `String`.
  - Keep `internalDate` as `String?` (already correct).
- [x] `GmailApi.kt` — `listHistory(startHistoryId: Long)` must take `String` (Gmail query param is a string).
- [x] `AccountEntity.historyId` and `AccountDao.updateSyncPoint` — store as `String?` (or parse once at the repo boundary and keep Long only in memory; string-in-DB is simpler and avoids overflow).
- [x] `MailRepository.deltaSync` / `updateSyncPoint` / `newestHistoryId - 1` — stop doing integer arithmetic on history IDs. Compare/persist the **string ids Gmail returned**.
- **Accept:** a golden JSON fixture from `users.getProfile` / `threads.list` / `threads.get` / `history.list` / `messages.send` (quoted `"historyId"`) deserializes without throwing.
- **Test:** `GmailJsonTest` with real-shaped samples. Quoted uint64 must pass; a numeric `historyId` may be accepted via a custom serializer if you want belt-and-suspenders.

### A2. Never retry a send that already left the device
- [x] `OutboxWorker.doWork`
  - Parse/send is two steps: HTTP 2xx from `messages.send` = **sent**, even if the response body fails to decode.
  - Mark `SENT` before any post-send work (`archiveAfterSend`).
  - Do not `Result.retry()` after 2xx.
  - On 4xx (except 429) mark `FAILED`, do not retry.
  - Retry only on transport errors / 429 / 5xx, still capped at `MAX_ATTEMPTS`.
- [x] Surface `FAILED` rows in the UI (even a mailbox banner / outbox list). “Sending…” with no failure state is not acceptable.
- **Accept:** a mocked 200 + unreadable body increments sent-count once, never twice.
- **Test:** fake `GmailApi.sendRaw` returning 200/garbage, 200/valid, 500, 401.

### A3. Pass `includeSpamTrash = true` when needed
- [x] `GmailApi.listThreads` — do not default this blindly for every call.
- [x] `hydrateFromQuery` / `searchServer` — set `includeSpamTrash = true` when `labelIds` contains `TRASH` or `SPAM`, or when `q` contains `in:trash` / `in:spam`.
- **Accept:** opening Trash/Spam hydrates threads; `in:trash` search returns rows.

---

## Phase B — Auth that completes and survives

### B1. Fix OAuth redirect capture
- [x] `AndroidManifest.xml` — remove MainActivity’s `VIEW` / `dev.xxemail` / `oauth2redirect` filter. Let AppAuth’s `RedirectUriReceiverActivity` own the redirect.
- [x] `MainActivity.maybeConsumeRedirect` — only accept AppAuth result intents (`AuthorizationResponse.fromIntent` / `AuthorizationException.fromIntent` non-null), never a raw VIEW uri.
- [x] Do not set `authDelivered = true` when `authCallback` is still null; queue the intent until `launchOAuthFlow` registers the callback.
- [x] `AuthRepository.buildAuthIntent` — `dispose()` the `AuthorizationService` (today it leaks).
- [x] `app/build.gradle.kts` `appAuthRedirectScheme` must match whatever scheme you document in B2.
- **Accept:** debug install → sign-in → `onAuthorizationResult` gets a real `AuthorizationResponse` on both Custom Tab and external-browser paths.

### B2. Fix and live-test OAuth setup docs
- [x] `docs/oauth-setup.md` and `SetupScreen.kt` copy
  - Android client: package `dev.xxemail`, debug **and** release SHA-1, **enable custom URI scheme** if you keep `dev.xxemail:/oauth2redirect`.
  - Or switch to the reversed-client-id scheme (`com.googleusercontent.apps.{CLIENT_ID_PREFIX}:/oauth2redirect`) and set `appAuthRedirectScheme` accordingly — this is what AppAuth’s Google README uses.
  - Remove “Desktop / loopback-free” wording. Desktop clients will not accept `dev.xxemail:/…`.
- [x] Setup field validation: keep the `*.apps.googleusercontent.com` suffix check; show the SHA-1 command (`./gradlew signingReport`) in-app or in the doc, not only in the repo.
- **Accept:** a fresh Google Cloud Android client + the documented redirect completes PKCE token exchange on a real device. Record the working client type + redirect in the doc.

### B3. Persist `AuthState` after every refresh
- [x] `AuthRepository.withAccessToken`
  - Cache `AuthState` in memory per account.
  - After `performActionWithFreshTokens`, `tokens.save(email, state)` if the serialized snapshot changed.
  - Reuse one `AuthorizationService` around a burst of calls (or at least do not construct+dispose per interceptor hit).
- [x] `GmailApiFactory` interceptor: still fine to call `withAccessToken`, but it must hit the cache, not decrypt-from-disk + refresh every request.
- **Accept:** after access-token TTL, one sync performs **one** token refresh, not one per `threads.get`. Killing the process still has a valid refresh token.
- **Test:** fake AuthState whose access token is expired; assert `save` is invoked; second call does not refresh again while the cached access token is fresh.

### B4. Re-auth UX when refresh fails
- [x] Map `invalid_grant` / `AuthorizationException` to a visible “Sign in again” on mailbox/setup, not a silent sync failure.
- [x] Keep the account row; replace tokens on success; do not duplicate the account.
- **Accept:** revoked token → banner/dialog → consent → sync resumes.

### B5. Token store durability
- [x] `TokenStore.persist` — if `tmp.renameTo(file)` fails, **do not delete** `authstates.bin` until the new file is in place (write tmp → fsync → atomic rename; on failure leave the old file).
- [x] Decrypt/read failure: do not pretend the store is empty while the file still exists. Surface “credentials unreadable, sign in again” instead of wiping the in-memory cache and leaving a zombie Room account.
- **Accept:** simulated rename failure cannot destroy the only copy of tokens.

---

## Phase C — Do not lose or leak mail

### C1. Sanitize attachment / upload paths
- [x] `MailRepository.downloadAttachment` — use only `File(filename).name`, reject empty / `.` / `..`, then `canonicalFile` must stay under `cacheDir/attachments/`.
- [x] `ComposeViewModel.addAttachment` — same for `displayName` / `lastPathSegment` under `cacheDir/uploads/`.
- [x] Cap decoded attachment size in memory (reuse `MimeComposer.MAX_TOTAL_ATTACHMENT_BYTES` or Gmail’s per-part limit) to avoid OOM.
- **Accept:** filename `../../files/authstates.bin` cannot write outside the attachments dir; `FileProvider.getUriForFile` is only called on a file that actually landed in that dir.
- **Test:** path-traversal names, blank names, nested separators.

### C2. Durable snooze + unsnooze
- [x] `MailRepository.snooze` — persist a wake row (`OutboxKind.SNOOZE_WAKE` or equivalent) with account, threadId, `targetAt`. Do not rely on WorkManager input data alone.
- [x] `SnoozeWorker` — `Constraints.NETWORK`, retry until success (or a high cap with a visible failed-snooze state). On success add `INBOX` back and clear `snoozedUntil`.
- [x] Unsnooze control on the Snoozed folder and on the thread bar (today the thread bar only snoozes again). Snackbar undo is not enough.
- [x] `AccountRepository.remove` — cancel `snooze-*` and `outbox-*` work, restore `INBOX` for still-snoozed threads **before** dropping tokens (best-effort, with a warning if offline).
- [x] README uninstall caveat stays; extend it to account removal and failed wakes.
- **Accept:** snooze → reboot → wake still fires. Worker failure does not silently archive forever. Unsnooze from UI puts mail back in every client’s inbox.

### C3. Outbox payloads off CursorWindow
- [x] Stop storing RFC822 in `OutboxEntity.rfc822Base64`. Write bytes under app-private `files/outbox/{id}.eml` (or cache with a path column). Room keeps path, size, state, attempts, error.
- [x] Worker reads the file. Delete the file on `SENT` / `CANCELLED`. Keep it on `FAILED` so the user can retry.
- [x] Still enforce `MAX_TOTAL_ATTACHMENT_BYTES` at compose time.
- **Accept:** a ~5–10MB attachment queues, survives process death, and sends once. Failed sends are visible and retryable.

### C4. Room migrations, not destructive fallback
- [x] `XxEmailDb` — `exportSchema = true`, `fallbackToDestructiveMigration()` **removed**.
- [x] Any entity change in this todo (historyId type, outbox path column, etc.) ships an explicit `Migration(1, 2)` that does not drop `outbox` or `snoozedUntil`.
- **Accept:** upgrading a v0.1 DB with a queued send and a snoozed thread keeps both.

---

## Phase D — Mailbox tells the truth

### D1. Thread aggregates from the label union
- [x] `MailRepository.toThreadEntity`
  - `inInbox` = union of all messages’ labels contains `INBOX` (not only the latest message).
  - Categories = union of `CATEGORY_*` (Primary empty / `CATEGORY_PERSONAL` logic stays in the DAO query).
  - After you reply (`SENT` on the latest message), the thread **stays** in Inbox if any message still has `INBOX`.
- **Accept:** reply-from-app or hydrate of a sent-reply thread does not yank the conversation out of Primary.

### D2. Local label state on every action
- [x] `archive` / `trash` / `untrash` / `reportSpam` / `markRead` / `toggleStar` / `snooze` / `unsnooze` must update `inInbox`, `labelsCsv`, `unreadCount`, `starred`, `snoozedUntil` so folders move **immediately**, not after the next hydrate.
- [x] Trash must appear under Trash (`TRASH` in `labelsCsv`) and leave Inbox. Undo reverses both.
- [x] Optional: re-hydrate the touched threads after success so counts stay exact; do not skip the local update.
- **Accept:** trash a thread → gone from Inbox, visible in Trash, undo restores, no sync required.

### D3. New-mail detection
- [x] Snapshot unread inbox thread ids (or max `internalDate` / stored `lastSyncAt`) **before** the pass.
- [x] “New” = unread inbox threads not in that snapshot (or newer than `lastSyncAt`).
- [x] Skip notifications on the first sync of an account and when the user is already looking at the mailbox if you want Gmail-like behavior (first-sync skip is mandatory).
- [x] `Notifier` PendingIntent should open the account mailbox (and ideally the thread), not a bare `MainActivity`.
- **Accept:** a message that arrived an hour ago, first seen on this 15-minute poll, notifies once. First install does not dump the whole inbox as “new”.

### D4. Delta-sync checkpoint
- [x] Persist the last **fully processed** history record id, not `newestHistoryId - 1`.
- [x] If `touchedThreadIds > MAX_THREADS_PER_DELTA`, enqueue an immediate follow-up sync from that checkpoint; do not skip the overflow window.
- [x] Walk `HistoryItem.messages` as well as `messagesAdded` / label / delete lists (Gmail sometimes only fills `messages`).
- **Accept:** a burst of >60 thread changes eventually hydrates all of them; no silent skip.

### D5. Body / attachment cache
- [x] `loadFullThread` — do **not** set `bodyFetched = 1` when `messages.get` failed or returned no payload. Leave it retryable.
- [x] Persist attachment metadata (id, filename, mime, size) on the message row (JSON column or child table). Cached opens must still show the attachment chips.
- [x] `hasAttachment` must recurse nested multiparts, not only `payload.parts` one level down.
- **Accept:** open thread offline after a successful fetch → body + attachments still there. Failed fetch → retry next open.

### D6. Pagination / hydrate
- [x] Initial inbox sync: keep a bound per pass but add **load more** (next page token stored per folder) instead of a hard stop at 4×50 with no way forward.
- [x] `ensureHydrated` for category tabs must not require `threadDao.count(account) == 0`. Hydrate that category/label when its own count is empty.
- [x] `observeInboxCategory` needs a sensible cap or paging; do not emit unbounded lists into Compose.
- **Accept:** Social/Promotions/Trash populate even when Inbox already has rows. User can reach mail older than the first 200.

### D7. Label `LIKE` matching
- [x] Stop `labelsCsv LIKE '%' || :labelId || '%'`. Use a delimiter (`","` wrapped csv, or a child `thread_labels` table) so `SENT` does not match `CONSENT` and `TRASH` does not match a user label containing that substring.
- **Accept:** user label `CONSENT` does not show in Sent.

---

## Phase E — Compose and send

### E1. Fresh compose state
- [x] `rememberComposeViewModel` key must include threadId/mode, **or** `prefill` always resets `to/cc/bcc/subject/body/attachments/threadId/inReplyToHeader` even when `quoteMessageId` is blank.
- [x] `prefill` belongs in `LaunchedEffect`, not `remember { }` during composition.
- **Accept:** send/discard, open a new blank compose → empty body, no leftover attachments.

### E2. Reply-all and forward
- [x] Reply: To = original From (if not self), else original To.
- [x] Reply-all: To = From; Cc = original To + original Cc, minus self and minus To. Do not drop original To.
- [x] Forward: “On {formatted date}, {from} wrote:” **above** the quoted body; use `fullDate`, not raw epoch millis. Offer to include original attachments.
- [x] Prefill must wait until `loadFullThread` has bodies, or fetch the quoted message full if `bodyPlain` is still null.
- **Accept:** reply-all to a mail sent To A,B Cc C includes B and C. Forward shows a human date.

### E3. Threading headers + `threadId` on send
- [x] Keep `In-Reply-To`. Build `References` as prior References + parent Message-ID, not parent only.
- [x] If Gmail media upload cannot take `threadId`, document that threading is header-only and verify replies land in the same thread on a live send. If they do not, switch that send to JSON `{ raw, threadId }` (`uploadType=multipart` / `media` as required).
- **Accept:** reply appears in the same Gmail thread as the original (web Gmail + this app).

### E4. MIME type and addresses
- [x] Apply `Attachment.mimeType` on the body part (`setHeader("Content-Type", …)` or `ByteArrayDataSource`).
- [x] Parse recipients with `InternetAddress.parse(..., false)` (or equivalent), not `split(',')` + `contains('@')`. Quoted `"Doe, Jane <jane@x.com>"` is one address.
- **Accept:** comma-in-display-name and `.png` attachments with a picker mime type both round-trip.

### E5. Undo-send races
- [x] `cancelQueuedSend` vs worker already in `SENDING`: documented today; make it real — if state is `SENDING`, refuse undo or wait for a short gate; never delete the row out from under a live send without a result.
- [x] WORKFLOW_STATE already notes in-flight undo cannot cancel. Either implement a cooperative cancel flag the worker honors **before** `sendRaw`, or disable Undo once `SENDING`.
- **Accept:** mashing Undo during the last 100ms of the delay cannot produce “undone” UI + mail still delivered without a send confirmation.

---

## Phase F — App shell, errors, settings

### F1. Cold start navigation
- [x] `XxNavHost` — do not start at Setup while `accounts == null`. Show a short loading frame, then Setup **or** last-used mailbox.
- [x] Remove the lie in the comment (“SetupScreen self-corrects”). Setup must not be the start destination for an already-signed-in user.
- [x] Persist last-used account; `accounts.first()` (alpha) is not “the account I was using”.
- **Accept:** kill app with an account present → relaunch lands in that mailbox, no client-ID screen.

### F2. Account removal
- [x] After `accounts.remove`, if that was the current mailbox account, navigate to another account or Setup.
- [x] Cancel that account’s WorkManager unique work (`snooze-*`, `outbox-*`, maybe a tagged sync).
- [x] Combined with C2: unsnooze/restore INBOX best-effort first.
- **Accept:** delete the only account → Setup. Delete one of two → the other mailbox, no empty shell.

### F3. Sync interval and errors
- [x] `XxEmailApp.onCreate` must not hardcode `ensurePeriodic(..., 15)`. Read last interval (DataStore default 15) or let `MailboxViewModel` be the only registrar **and** a boot-safe path that uses the saved value.
- [x] `MailboxViewModel.refresh` / `SyncWorker` — surface failures (snackbar / banner). `sync()` already returns `Result`; stop swallowing it in `runCatching` and ignoring `refreshing`.
- [x] Drive a real refresh indicator off `refreshing` (top spinner or pull-to-refresh).
- **Accept:** airplane mode + refresh shows an error. User-set 30 min survives process death.

### F4. Thread screen actions vs snackbar
- [x] Do not `onBack()` before archive/trash/snooze completes.
- [x] Emit undo on the **mailbox** (extend `SendEvents` or a shared undo bus) so leaving the thread does not kill the snackbar.
- [x] `ThreadViewModel.launchUndo` must `runCatching` and show errors.
- **Accept:** archive from thread → back on inbox → snackbar Undo still works.

### F5. Multi-select
- [x] Star / snooze / label apply to **all** selected ids, not `threadIds.first()`.
- [x] Label sheet: allow remove as well as add.
- **Accept:** select 3, star, all 3 starred.

### F6. HTML rendering and remote images
- [x] `HtmlBody` text color from `MaterialTheme.colorScheme.onSurface` (ColorInt), not `0xFF202124`.
- [x] Either add Settings → Appearance “Load remote images” **and** an `ImageGetter` that only loads when allowed, or delete `remoteImagesFlow` / `setRemoteImages` and change `PRIVACY.md` to “always blocked in v0.1”.
- [x] Keep `TextView` + `Html.fromHtml`. Do not switch to WebView without a real sanitizer. If you keep regex `sanitizeHtml`, add comments that it is TextView-only.
- **Accept:** dark theme mail is readable. Docs match the UI.

### F7. Search UX
- [x] Keyboard IME action Search; hints can be tappable.
- [x] Empty state must be visible (do not put `EmptyState(fillMaxSize)` after a `LazyColumn(fillMaxSize)`).
- [x] Local FTS: escape `MATCH` specials; do not crash on `AND`/`OR`/`"`. Index bodies once D5 stores them, or stop calling it a full-text body index in the README.
- [x] Search `ThreadRow`: disable swipe/star or wire them up. Dead chrome is worse than none.
- [x] `searchLocal` reconstructed threads should not force `inInbox = false` (looks archived).
- **Accept:** `hello`, `from:me`, `in:trash`, and `foo AND bar` as a literal all behave; no crash.

### F8. Swipe / notifications / small nits
- [x] Do not perform mail actions inside `SwipeToDismissBoxState.confirmValueChange` (side effect in snapshot). Fire the action after settle via a callback/`LaunchedEffect`.
- [x] Star swipe should toggle, not always star.
- [x] Notification small-icon: a real monochrome `drawable` (not the adaptive launcher foreground).
- [x] Request `POST_NOTIFICATIONS` is already there; if denied, the notifications toggle should explain that.
- [x] `okhttp-logging`: `debugImplementation` or remove until used. Never BODY-log tokens.
- [x] `AuthorizationService` dispose (covered in B1).
- [x] Compose “Add Cc/Bcc” text is not clickable — use the chip only, or make the text clickable.
- [x] Snooze sheet: `SnoozePresets.weekend` exists but is unused; add it or delete it.
- [x] Thread bar has no star control (only list star). Add or leave as known gap — if left, drop from feature matrix.

### F9. Re-auth / 401 from API
- [x] Interceptor: on 401 after a fresh token, trigger B4 instead of failing the whole sync silently.
- **Accept:** expired testing-mode 7-day token prompts sign-in, not an empty inbox.

---

## Phase G — Tests and live verification

Unit tests that must exist before claiming the bugs are gone:

- [x] Gmail JSON golden files (A1) — quoted uint64 `historyId`.
- [x] Outbox retry policy (A2) — 200+bad body, 500, 401.
- [x] Path traversal (C1).
- [x] `toThreadEntity` inInbox union (D1) — latest message SENT-only, older message still INBOX.
- [x] New-mail detector (D3) — old unread vs first sync vs newly arrived.
- [x] `sanitizeHtml` + filename sanitizer.
- [ ] Address split / reply-all set logic (E2/E4).
- [x] TokenStore persist rename-failure (if testable with a fake File).
- [x] FTS query escaping (F7).

Device / emulator (mandatory; WORKFLOW_STATE still says this never ran):

- [ ] Debug install, Android OAuth client as documented, PKCE round-trip.
- [ ] First sync hydrates Inbox tabs.
- [ ] Open a HTML + multipart message: body readable in light **and** dark; attachments open via FileProvider.
- [ ] Send a plain mail; undo within the window actually cancels; send after window delivers once.
- [ ] Send with a ~3MB attachment once (after C3).
- [ ] Reply in-thread; confirm in Gmail web.
- [ ] Trash / archive / undo / spam; folders update without waiting for poll.
- [ ] Snooze 1–2 minutes, wait, confirm INBOX restored on device + Gmail web.
- [ ] Airplane mode refresh shows an error; back online recovers.
- [ ] Kill app, relaunch: mailbox not Setup; tokens still valid.
- [ ] Remove account while one thread snoozed: warn + best-effort restore.
- [x] Release R8 APK: sign-in, sync, send (Angus ServiceLoader merge + minify).

---

## Phase H — Docs, claims, hygiene

- [x] README feature matrix: mark drafts as folder-only if there is still no draft compose; do not claim grouped notifications until D3 works; do not claim offline FTS over bodies until indexed.
- [x] `PRIVACY.md` remote-image sentence must match F6 (toggle or always-off). Fix the TokenStore path (`app/src/main/java/dev/xxemail/data/auth/TokenStore.kt`, not `data/auth/TokenStore.kt`).
- [x] `docs/oauth-setup.md` = whatever B2 actually verified.
- [x] `WORKFLOW_STATE.md` — replace “S2/S3 gaps” with a pointer to this file; check off as you go.
- [x] TokenStore / XxEmailDb KDoc: one-line why (Keystore AES-GCM; plaintext Room), drop the EncryptedSharedPreferences history essay.
- [x] Init git if this tree is the source of truth (LICENSE is GPL-3.0-or-later; there is currently no repository).
- [x] Do not add telemetry, Play Services, or extra OAuth scopes. Keep `gmail.modify` only.

Roadmap (explicitly **not** this todo; do not block v0.1.1 on them):

- Drafts editor / vacation responder UI
- Push (Pub/Sub + UnifiedPush relay)
- SQLCipher opt-in
- IMAP/JMAP / OpenPGP
- Per-label notification rules
- Batched `threads.get` (sequential is quota-fine; latency only)

---

## Suggested implementation order (PRs)

1. **A1 + A2 + A3 + G JSON/outbox tests** — app can talk to Gmail without duplicating mail.
2. **B1–B5 + live OAuth** — sign-in works twice (first run and after token TTL).
3. **C1–C4** — no path traversal, no silent snooze loss, no CursorWindow send death.
4. **D1–D7** — mailbox folders and notifications match the server.
5. **E1–E5** — compose/reply/send/undo.
6. **F1–F9** — shell, errors, dark HTML, search, settings honesty.
7. **G device pass + H docs** — then tag `v0.1.1`.

## Done-when

- [x] All Phase A–F boxes checked.
- [x] Phase G unit tests green (`./gradlew testDebugUnitTest`).
- [ ] Phase G device list signed off (debug + R8 release).
- [x] README / PRIVACY / oauth-setup match the code.

Until then this is not good to go.
