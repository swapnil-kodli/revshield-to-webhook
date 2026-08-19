# RevShield Probe → Webhook

A standalone Android app that records **how the carrier / built-in caller ID labels an incoming
call** ("Spam", "Airtel Warning: SPAM", "Fraud risk", …), auto-rejects the call, and POSTs the
observation as JSON to **any webhook URL you configure**.

It is fully self-contained: no backend, no account, no LAN dependency. It needs only internet
access (cellular or any Wi-Fi) and a webhook URL you paste into Settings at runtime.

- **Application id:** `com.revshield.spamprobe` · **version** `1.0.0`
- **Min Android:** 9.0 (API 28 — required for `TelecomManager.endCall()`) · **Target/compile SDK:** 34

---

## A. Overview

```
   ┌────────────────────┐
   │  Incoming call     │
   └─────────┬──────────┘
             │  native dialer renders the call screen
             ▼
   ┌──────────────────────────────────────────────────────┐
   │ AccessibilityService (CallCaptureService)            │
   │  • native dialer AND Truecaller are read separately  │
   │  • full-screen call UI *and* heads-up banners        │
   │  • scrapes the on-screen node tree                   │
   │  • classifies the carrier's spam label               │
   │  • collapses the event burst into ONE record         │
   └─────────┬────────────────────────────────────────────┘
             │  waits ~1.8 s for the label to settle, then finalises
             ▼
   ┌──────────────────────────────────────────────────────┐
   │ Record saved to Room (syncState = PENDING)           │
   └─────────┬────────────────────────────────────────────┘
             │  capture is complete — only now
             ▼
   ┌──────────────────────────────────────────────────────┐
   │ Auto-reject the call (TelecomManager.endCall)        │
   └─────────┬────────────────────────────────────────────┘
             │  WorkManager (immediate + 15-min safety net)
             ▼
   ┌──────────────────────────────────────────────────────┐
   │ POST JSON → your webhook  (one record per request)   │
   │   HTTP 2xx  → syncState = SYNCED                     │
   │   anything else → FAILED + reason, retried           │
   └──────────────────────────────────────────────────────┘
```

**Capture fully, then reject — never before.** The record is written first; hang-up happens only
after the observation is final.

**Two sources, reported separately.** The **native OS dialer** (AOSP/Google, Samsung, Xiaomi/MIUI,
OnePlus/Oppo/Realme, Vivo) carries the carrier / built-in caller-ID verdict → `airtel_status`.
**Truecaller** renders its own crowdsourced verdict in a separate window → `truecaller_status`.
Either can flag a call the other did not.

**Staying alive.** Aggressive OEM cleaners kill background apps — MIUI's was observed killing this
one mid-session, after which Android marks the accessibility service crashed and never rebinds it.
A foreground service plus a watchdog (which re-arms capture via `WRITE_SECURE_SETTINGS` when granted)
keeps the probe running; if it ever cannot recover, the notification says so rather than failing
silently. See section G.

---

## B. Build from source

### Prerequisites

| Requirement | Version | Notes |
|---|---|---|
| JDK | **21** | Used by the Gradle daemon (`gradle/gradle-daemon-jvm.properties` → `toolchainVersion=21`). Gradle can auto-provision it. |
| Android SDK | **API 34** platform + build-tools | Install via Android Studio's SDK Manager or `sdkmanager`. |
| Gradle | 9.4.1 | **Do not install** — the wrapper (`gradlew` / `gradlew.bat`) downloads it. |

The build itself uses AGP 9.2.1, Kotlin 2.2.10, KSP 2.3.2, Compose BOM 2024.06.00, Room 2.7.2,
WorkManager 2.9.1, OkHttp 4.12.0. Java/Kotlin bytecode target is **17** (the JDK-21 daemon compiles
to 17 — this is expected, not a mismatch).

### Clone

```bash
git clone https://github.com/swapnil-kodli/revshield-to-webhook.git
cd revshield-to-webhook
```

### Point the build at your Android SDK

`local.properties` is **gitignored** (it holds a machine path). Create it in the project root:

```
sdk.dir=C:\\Users\\<you>\\AppData\\Local\\Android\\Sdk
```

macOS/Linux:

```
sdk.dir=/Users/<you>/Library/Android/sdk
```

You can skip this if the `ANDROID_HOME` environment variable is already set.

### Build the APK

```bash
./gradlew.bat :app:assembleDebug
```

(macOS/Linux: `./gradlew :app:assembleDebug`)

Output APK:

```
app/build/outputs/apk/debug/app-debug.apk
```

### Build and install onto a connected device

```bash
./gradlew.bat :app:installDebug
```

### Release builds

**Debug-only today.** No signing config or keystore is committed (and none should be). `assembleRelease`
will produce an *unsigned* APK. To ship a signed release, add your own keystore outside the repo and a
`signingConfigs` block in `app/build.gradle.kts` — keep every keystore and password out of git.

### Wireless ADB (no USB cable required)

On the phone: **Settings → Developer options → Wireless debugging → on**.

```bash
adb pair <phone-ip>:<pairing-port>
adb connect <phone-ip>:<debug-port>
adb devices
```

`adb pair` is a one-time step per machine (use the pairing code shown on the phone). Afterwards only
`adb connect` is needed. Then `./gradlew.bat :app:installDebug` installs over Wi-Fi.

### Watch the logs

```bash
adb logcat -s RevShieldNet:* RevShield:* AndroidRuntime:*
```

- `RevShield` — capture events (record captured, service connected, auto-reject)
- `RevShieldNet` — **every HTTP request and response**, full body, in debug builds

---

## C. Install & configure on the phone

> **Handset requirement — read this first.** Airtel's spam alerts are only delivered to a
> **VoLTE-enabled** handset (per Airtel's own terms). On a phone whose IMS never registers, the
> carrier sends no caller name at all, every call arrives with `name=NULL`, and **no app or dialer
> can display a label that was never sent**. Verify before blaming the probe:
> ```bash
> adb shell dumpsys telephony.registry | grep registrationState   # want HOME, not NOT_REG_OR_SEARCHING
> adb shell content query --uri call_log/calls --projection name:number:type
> ```
> A budget handset that fails this will silently report `NOT SPAM` for every call.



1. **Sideload the APK.** Either `./gradlew.bat :app:installDebug`, or copy
   `app-debug.apk` to the phone and open it. Allow "install unknown apps" for the installing app
   (Files/Chrome) if prompted.
2. **Play Protect** may warn about an unrecognised app — choose *Install anyway* / *More details →
   Install anyway*. (It is an unsigned debug build of an accessibility app; the warning is expected.)
3. **Restricted settings (Android 13+).** Sideloaded apps are blocked from enabling accessibility
   until you allow it: **Settings → Apps → RevShield Probe → ⋮ (top-right) → Allow restricted
   settings**.
4. **Grant the permissions.** On first launch the app requests `ANSWER_PHONE_CALLS` (to auto-reject
   after capture) and `READ_CALL_LOG` (to recover a caller number the carrier masked on screen).
   Accept both.
5. **Enable the accessibility service:** **Home → Open Accessibility settings → RevShield Probe →
   On**. Return to the app; Home flips to **"Capture service: ON"** by itself.
6. **Keep it alive on aggressive OEMs (Vivo/Oppo/Realme/Xiaomi).** Battery managers freeze
   background workers and accessibility services:
   - Settings → Battery → **Background power consumption management** → RevShield Probe →
     **Allow background running** (turn *off* the high-consumption restriction)
   - Settings → Apps → RevShield Probe → Battery → **Don't optimise / Allow**
   - Enable **Auto-start** for the app
7. **Set the Webhook URL** (empty by default — see below).

### Getting a test webhook URL

1. Open <https://webhook.site> in any browser.
2. Copy **"Your unique URL"** — it looks like `https://webhook.site/<your-unique-id>`.
3. In the app: **Settings → Webhook URL → paste → Save**, then **Test connection**.

> The free webhook.site URL is temporary — it expires after roughly **7 days** of inactivity.
> Use it for testing only; point production at a permanent endpoint you control.

**Requirements for the URL:** must be a well-formed **`https://`** URL with a host. `http://` is
rejected at save time (the app ships with cleartext traffic disabled). Leaving the field **empty is
allowed** — that is the unconfigured state.

The probe needs **only internet access**. Cellular or any Wi-Fi both work; there is no LAN, no
same-network requirement, and no firewall or IP configuration.

---

## D. Probe outputs — the three screens

### Home
Capture-service status (ON/OFF + a shortcut to Accessibility settings), the webhook status, counts
(**Total / Pending / Synced / Failed**), the last call, and the **Sync now**, **Export pending**,
**Export all** buttons (export writes NDJSON — one JSON record per line — via the system file picker).

### Records
Every captured call, newest first. Tap one for the full detail, including the complete
pretty-printed `raw_accessibility_tree`.

### Settings
**Webhook URL** (validated on save), **Test connection**, and an optional single custom header
(name + value) for future auth — off by default, sent on every request when set.

### Status meanings

| Status | Meaning |
|---|---|
| **No webhook URL configured** | Shown on Home when the URL is empty. Records are still captured and stored, held as Pending. This is a normal state, **not** an error — nothing is sent and nothing fails. |
| **Pending** | Stored on the device, not yet confirmed delivered. Awaiting the next upload attempt (or a webhook URL). |
| **Synced ✓** | The webhook returned a verified **HTTP 2xx**. This is the *only* way a record becomes Synced. |
| **Failed: `<reason>`** | The upload was attempted and did not return 2xx. The reason is visible in the UI and in logcat (e.g. `HTTP 500: …`, `network: timeout`). It is retried automatically. |

### syncState lifecycle

```
PENDING ──(HTTP 2xx)──────────────► SYNCED   (terminal)
   │                                   ▲
   │                                   │
   └──(non-2xx / network error)──► FAILED ───┘  retried with exponential backoff
                                              until 2xx, or 200 attempts
```

**The honesty contract.** A record reaches `SYNCED` only on a *verified* HTTP 2xx response — never
on enqueue, never because "the worker didn't throw". Records are never deleted after upload, only
marked. Uploads run in batches of 50, immediately on capture, when connectivity returns, and via a
15-minute periodic safety net that survives app death.

---

## E. The webhook payload

Each observation is delivered as **one POST per call**:

- **Method:** `POST` · **Content-Type:** `application/json`
- **Body:** exactly the four fields below — nothing else
- **Headers:** none required. If you configure the optional custom header in Settings, it is added.

```json
{
  "phone_number": "+917965854235",
  "airtel_status": "SPAM | Airtel Warning: SPAM",
  "call_received_time": "07:23 pm",
  "truecaller_status": "NOT SPAM | Mana Projects"
}
```

| Field | Meaning |
|---|---|
| `phone_number` | The number that called the probe. `null` only if the carrier masked it on screen **and** it could not be recovered from the call log. |
| `airtel_status` | What the **native dialer** showed — i.e. the carrier / built-in caller-ID verdict. |
| `call_received_time` | Local 12-hour clock, e.g. `07:23 pm`. No date — records are expected to be consumed the same day. |
| `truecaller_status` | What **Truecaller's** own banner showed. Independent of `airtel_status`. |

### Status format

Both status fields read `VERDICT | what that source displayed`:

```
SPAM | Airtel Warning: SPAM      carrier flagged it, with its exact wording
SPAM | Likely Spam               Truecaller flagged it
NOT SPAM | Mana Projects         Truecaller identified a legitimate business
NOT SPAM | Sapna Kodliwadmath    a known contact
NOT SPAM                         nothing identifying was displayed
```

- **`SPAM`** when that source showed spam, suspected-spam or fraud wording.
- **`NOT SPAM`** otherwise — including unknown/unidentified callers.
- The text after `|` is the **verbatim on-screen wording**, so the raw carrier/Truecaller phrasing is never lost.

The two sources are **independent**: Truecaller can flag a call the carrier did not, and vice versa.

### Carrier-masked numbers

Airtel replaces the caller number with its warning, so the number never appears on screen. The probe
then recovers it from the **call log** after the call ends (requires `READ_CALL_LOG`), so
`phone_number` is still populated on exactly the spam calls that matter most.

### Full-screen and banner calls

Both are captured. A call arriving while the phone is in use renders as a **heads-up banner** drawn
by SystemUI rather than a full-screen dialer window; the probe monitors both surfaces, gated on the
phone actually ringing so ordinary notifications are never mistaken for calls.

## F. Receiving & integrating

### Inspect first (webhook.site)
Paste your webhook.site URL into Settings and receive a call — the JSON appears in the browser tab
instantly. Use this to confirm the payload shape before writing any integration.

### Forward to a real destination
webhook.site **Custom Actions** can forward each request onward without you hosting anything:
add an action (e.g. *Send request to URL*) on your webhook.site page to relay to your backend,
a Google Sheet via Apps Script, a Slack incoming webhook, or a Zapier/Make hook. For production,
point the probe directly at your own endpoint instead.

### Your receiver's contract
> **Return HTTP 2xx.** Anything else — 4xx, 5xx, timeout, TLS failure — marks the record **Failed**
> with the reason and the probe will retry it later.

Practical guidance:
- Respond 2xx **as soon as you have durably stored the body**; do slow processing asynchronously.
- **Deduplicate on `id`** — a retried record keeps the same `id`, and retries are expected after any
  non-2xx or network blip.
- Accept a body up to a few hundred KB: `raw_accessibility_tree` can be large.
- The endpoint must be **HTTPS** with a certificate valid to the phone.
- Records are queued while offline and flushed automatically when connectivity returns, so expect
  bursts and out-of-order arrival. Order by `observed_at`, not arrival time.
- **Handle bursts.** A backlog drains in a continuous loop — measured at **150 records in ~103 s** —
  so your endpoint must tolerate sustained POSTs. Free tiers of inspection services (webhook.site
  included) rate-limit around ~50 requests and will start returning 429, which the probe correctly
  records as `Failed` and retries. Use a real endpoint for production.
- Ignore or route `type: "heartbeat"` bodies separately (see above).

Minimal receiver:

```js
app.post('/revshield', express.json({ limit: '5mb' }), async (req, res) => {
  await store.upsert(req.body.id, req.body);   // idempotent on id
  res.sendStatus(200);                         // 2xx ⇒ the probe marks it SYNCED
});
```

---

## G. Troubleshooting

| Symptom | Cause / fix |
|---|---|
| Home shows **"Capture service: OFF"** | The accessibility service is off. Tap **Open Accessibility settings** and enable RevShield Probe. On Android 13+ sideloads you must first do **App info → ⋮ → Allow restricted settings**. The badge re-checks itself whenever you return to the app. |
| Service enabled but **no records** | The call screen must come from a **native** dialer (see the supported list). Confirm with `adb logcat -s RevShield:*` during a call — unmatched packages are logged, and the package name can be added to `DialerPackages.NATIVE`. |
| Records stuck at **Pending**, no errors | No webhook URL configured (Home says so explicitly), or the device is offline. Set a URL, or tap **Sync now**. |
| **Failed: HTTP 4xx/5xx** | Your receiver did not return 2xx. Check its logs; the exact status and response body are in the record's reason and in `adb logcat -s RevShieldNet:*`. |
| **Failed: network: …** | DNS/TLS/timeout. Verify the URL is reachable from the phone's browser. Remember `http://` is rejected — the endpoint must be HTTPS. |
| **Test connection** says OFFLINE | The phone can't reach the host at all: no internet, wrong host, or a blocked/expired webhook.site URL (free URLs expire after ~7 days). |
| Uploads stop when the screen is off | OEM battery manager killed the worker. Re-apply the Vivo/Oppo/Xiaomi steps in section C.6. Pending records are durable and will flush on the next successful run. |
| Call isn't auto-rejected | `ANSWER_PHONE_CALLS` was denied. Grant it in **Settings → Apps → RevShield Probe → Permissions**. Capture still works without it; only the hang-up is skipped. |

---

## Privacy & scope

The app reads the incoming-call screen **only** while a monitored native dialer is in the
foreground, and stores records **locally** plus at the webhook you configure. It sends data nowhere
else — there is no analytics, telemetry, or third-party SDK. The webhook URL you enter is kept in
the app's private storage on the device and is never committed to this repository.

Deploy it only on devices you own or administer, and where recording and auto-rejecting incoming
calls is lawful for you.
