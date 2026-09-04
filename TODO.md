# XX-Email — Remaining work

**2026-09-04.** Phases A–F are code. Remaining work is **family theme
align + Phase G on a device**. Do not invent IMAP/JMAP/PGP.

Package: `dev.xxemail`  
Telemetry-free Gmail REST client. BYO OAuth. `INTERNET` required;
egress is Google OAuth/Gmail only.

```
Status: Compose nav, Room, WorkManager, AppAuth, 103 JVM tests.
Receiver listens for the wrong action. Phase G unchecked except R8
sign-in which the old file marked done — re-verify on this phone.
```

---

## Locked now (2026-09-04)

| ID | Decision |
|---|---|
| E1 | **Align to the family theme contract.** Same action + permission as calculator/weather/clock. |
| E2 | Device smoke **is** the rest of v0.1.1. Drafts editor / PubSub / SQLCipher stay roadmap. |

---

## E1 — Theme sync

Today: `dev.xxemail.action.THEME_SYNC` + `dev.xxemail.permission.THEME_SYNC`.
Launcher sends `xx.launcher.THEME_CHANGED` with
`com.piercingxx.xxlauncher.permission.THEME_SYNC`. Email is in
`FAMILY_PACKAGES` and never restyles.

- [ ] `uses-permission` the launcher permission name (do **not**
  `<permission>`-declare it — mixed debug keys).
- [ ] Receiver action `xx.launcher.THEME_CHANGED`. Map preset names
  (AMOLED Night / Graphite / …) onto the in-app schemes.
- [ ] Apply persisted ground on first frame; live broadcast restyles.
- [ ] Manifest / receiver test like calculator.
- **Accept:** change theme in xx-launcher; inbox ink changes without a
  process death.

---

## Phase G — live checks

- [ ] Debug install, Android OAuth client as documented, PKCE round-trip
- [ ] First sync hydrates Inbox tabs
- [ ] HTML + multipart: readable in light **and** dark; attachments via FileProvider
- [ ] Send plain; undo inside the window cancels; after the window delivers once
- [ ] Send with a ~3MB attachment
- [ ] Reply in-thread; confirm in Gmail web
- [ ] Trash / archive / undo / spam; folders update without waiting for poll
- [ ] Snooze 1–2 minutes; INBOX restored on device + Gmail web
- [ ] Airplane mode refresh shows an error; back online recovers
- [ ] Kill app, relaunch: mailbox not Setup; tokens still valid
- [ ] Remove account while one thread snoozed: warn + best-effort restore
- [ ] Re-verify R8 release: sign-in, sync, send (Angus ServiceLoader)

Also run `androidTest` `MigrationTest` + `TokenStoreTest` on a device
that is **not** the only mail client.

**Accept:** dated notes. Then v0.1.1 may be called good to go.

---

## Housekeeping

- [ ] Notifications: README still marks grouped notifications 🚧 — either
  prove D3 on device or keep the cone.
- [ ] Drafts stay folder-only unless a new decision lands here.

---

## Stop conditions

- Play Services / Firebase / telemetry / extra OAuth scopes → reject.
- Permanent delete (beyond `gmail.modify`) → reject.
- Leaving the wrong theme action after E1 → reject.
- Roadmap features blocking G → reject.
