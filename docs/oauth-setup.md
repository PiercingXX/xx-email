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
   - Application type: **Android**
   - Package name: `dev.xxemail`
   - SHA-1: run `./gradlew signingReport` and copy the `debug` variant's SHA-1
     (or your release key's SHA-1 if you build release).
5. Copy the **Client ID** (looks like `1234567890-abc123.apps.googleusercontent.com`)
   and paste it into XX Email's setup screen.

## Notes & gotchas

- While your consent screen is in **Testing** status, refresh tokens expire after **7 days**
  (Google policy for restricted scopes). You'll be asked to sign in weekly — flip the consent
  screen to production to stop this.
- The app requests ONLY: `openid email profile` + `gmail.modify`.
  It cannot permanently delete mail (Google enforces this server-side for that scope).
- To revoke access at any time: <https://myaccount.google.com/permissions>
