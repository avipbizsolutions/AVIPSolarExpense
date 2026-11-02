# AVIP Solar Expense

Android app for the AVIP Solar team (AVIP Business Solutions LLP): expense claims with bill photos,
approvals, payments and advances, on one Google Sheet. Screens in Hindi-English or English
(switch on the login and home screens).

**Download the latest APK:**
https://github.com/avipbizsolutions/AVIPSolarExpense/releases/latest/download/AVIP-Solar-Expense.apk

## Who does what
Roles come from the **Expense App Role** column of the AVIP Team Directory.

| Role | Can do |
|---|---|
| **Member** | Add claims with a bill photo (GPS camera or gallery), see own claims, advances and balance |
| **Approver** | Also approve or reject other people's claims (never their own); reject needs a reason |
| **Accounts** | Also record advances and mark approved claims as paid (Cash / UPI / Bank Transfer, reference) |
| **Admin** | Everything above |

Claim status: Pending → Approved → Paid (Reimbursed), or Rejected.
"Paid by" on a claim: **Own money** (office pays it back), **From advance** (deducted from the
person's advance once approved), **Paid by company** (nothing to pay back).

Claims are saved on the phone first. Without signal they wait and are sent when the internet is back.

## One login for all AVIP Solar apps
People, PINs and roles live in the private Google Sheet **AVIP Team Directory (Confidential)**.
Its script, **AVIP Auth** (`auth-service/Code.gs`), checks every login for AVIP Solar Sales,
Installation and Expense. Each app's office script talks to it through `auth-service/AuthClient.gs`.

- The office types a temporary 4-digit PIN in the directory's **PIN** column. At first login the
  person sets their own PIN; only a salted, scrambled code (starting with `#`) is kept.
- Forgot PIN: type a new temporary PIN in that cell. Five wrong PINs lock the number for 15 minutes.
- **Status = Disabled** blocks the person in every app within 5 minutes. A blank app role blocks that app.
- A login lasts 30 days on a phone. A new PIN ends the old logins. `logoutEveryone` (run in the
  AVIP Auth editor) logs every phone out at once.

## Setup (office, once)
Do these in order, from the AVIP Google account that owns the sheets.

**1. AVIP Auth (Team Directory)**
1. Open **AVIP Team Directory (Confidential)** → Extensions → Apps Script → paste `auth-service/Code.gs` → Save.
2. Run **setup** and allow access. Copy the **SERVER KEY** from the log (View → Logs). Keep it private.
3. Deploy → New deployment → Web app → Execute as **Me**, Who has access **Anyone** → Deploy. Copy the `/exec` URL.

**2. Expense backend (Team Expense Tracker)**
1. Open **AVIP Solar – Team Expense Tracker** → Extensions → Apps Script.
2. Paste `apps-script/Code.gs` as **Code.gs**, add a file **AuthClient** and paste `apps-script/AuthClient.gs` → Save.
3. Project Settings → Script properties → add `AUTH_URL` (step 1's URL) and `AUTH_KEY` (step 1's server key).
4. Run **setup** and allow access. Deploy as a web app (Me / Anyone). Put the `/exec` URL in `config.json` → `"endpoint"`.

**3. AVIP Solar Installation and AVIP Solar Sales** – see their READMEs: the same AuthClient file and
the same two script properties, then a new deployment version.

After changing any script: Deploy → Manage deployments → Edit → Version: **New version** (the URL stays the same).

## Updates are compulsory
Every push to `main` builds a signed APK with GitHub Actions and publishes it as the latest release.
On launch the app checks the latest release and installs a newer version before it opens.
Keep `app/release.keystore` in the repo: every update must be signed with it.

## Code map
| File | What it does |
|---|---|
| `auth-service/Code.gs` | AVIP Auth: common login for all apps, on the Team Directory |
| `auth-service/AuthClient.gs` | Pasted into each app's office script to use AVIP Auth |
| `apps-script/Code.gs` | Expense backend on the tracker sheet: claims, bill photos, approvals, payments, advances |
| `Api.kt` | Calls the web app; session; roles |
| `Data.kt` | Claims, statuses, money format, bill photos, the offline outbox |
| `SplashActivity.kt`, `Updater.kt` | Compulsory update |
| `LoginActivity.kt` | Login and first-login PIN |
| `HomeActivity.kt` | Advance balance, totals, role tiles, own claims |
| `ClaimFormActivity.kt` | New claim |
| `ClaimActivity.kt` | Claim details, approve / reject, mark paid |
| `TeamClaimsActivity.kt` | Approve or pay several claims, all team claims |
| `AdvancesActivity.kt` | Own advances; team balances and giving advances |
| `CameraActivity.kt`, `Stamp.kt`, `MapTiles.kt`, `Geocoding.kt`, `Compass.kt` | GPS camera, same stamp as AVIP GPSCam |
