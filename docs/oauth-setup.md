# OAuth setup (bring your own client ID)

XX Email talks **directly** to Google from your device. There is no developer middleman
server — which also means there is no pre-baked credential. You create your own OAuth
client ID once (~5 minutes); your quota, your project, your control.

## Why this design?

- Google requires a per-app OAuth client for the Gmail API.
- If we shipped ONE central client, we would need Google's "restricted scope" verification,
  including an annual third-party security assessment (CASA) — impractical for a free
  open-source sideloaded app, and it would put a company in between you and your mail.
- With your own client ID, tokens are issued to *your* project; nobody else can query them.

## Steps

1. Go to <https://console.cloud.google.com/> and create a project (any name, e.g. `xx-email`).
2. **APIs & Services → Library → search "Gmail API" → Enable.**
3. **APIs & Services → OAuth consent screen**:
   - User type: **External**
   - App name: anything; add your own account as a **test user**, OR publish the app
     ("In production") without verification to avoid 7-day token expiry (you'll see a
     one-time "unverified app" warning — expected for self-signed FOSS apps).
4. **APIs & Services → Credentials → Create credentials → OAuth client ID**:
   - Application type: **Android** (this is the only type that works with the app's
     native AppAuth flow — do NOT use Desktop or Web)
   - Package name: `dev.xxemail` (exactly, case-sensitive)
   - SHA-1 fingerprints: run `./gradlew signingReport` and add **both** the `debug`
     variant's SHA-1 and your release key's SHA-1 if you build release builds.
5. On the same Android client, **enable/allow the custom URI scheme** `dev.xxemail`
   so Google accepts the app's redirect URI `dev.xxemail:/oauth2redirect`.
   XX Email always redirects to that scheme — no loopback or https redirect is used.
6. Copy the **Client ID** (looks like `1234567890-abc123.apps.googleusercontent.com`)
   and paste it into XX Email's setup screen.

## Notes & gotchas

- The redirect URI is fixed to `dev.xxemail:/oauth2redirect`. The browser hands the
  response to AppAuth's `RedirectUriReceiverActivity`; the client ID is *not* used to
  derive the scheme, so one static scheme serves every user's client ID.
- While your consent screen is in **Testing** status, refresh tokens expire after **7 days**
  (Google policy for restricted scopes). You'll be asked to sign in weekly — flip the consent
  screen to production to stop this.
- If refresh tokens expire or get revoked, the mailbox shows a "Sign in again" banner;
  signing in again replaces the tokens for that account without losing any local mail.
- The app requests ONLY: `openid email profile` + `gmail.modify`.
  It cannot permanently delete mail (Google enforces this server-side for that scope).
- To revoke access at any time: <https://myaccount.google.com/permissions>
