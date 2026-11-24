# QManager Mobile 0.3.0 — Android native prototype

Independent Kotlin / Jetpack Compose client, not an official QManager release. No WebView, no modem-side installation. Uses the existing CGI-compatible API documented at https://github.com/dr-dolomite/QManager-RM520N/blob/main/docs/API-REFERENCE.md . QManager-GO compatibility and RG501Q-EU / RG520N-EU support depend on the installed backend and have not been tested on hardware. No upstream implementation code is bundled.

## Build locally
- JDK 17, Android SDK platform 35, build tools 35.0.0, Gradle 8.9. Android Studio can open this project; select a local Gradle 8.9 distribution or generate the wrapper via `gradle wrapper --gradle-version 8.9` first (wrapper binary is not included).
- Run `gradle :app:assembleDebug` from this directory or use Android Studio Build > Build APK(s).
- APK: `app/build/outputs/apk/debug/app-debug.apk`. Android 8+ (API 26).

## Build via GitHub Actions (no local Android SDK required)
1. Extract ZIP. Create a **private** GitHub repo and upload the contents (including hidden `.github/workflows/android.yml`). `git add .` includes this folder. Do not upload modem credentials.
2. In Actions, choose `Android debug APK` > `Run workflow`, or push to `main`.
3. Open the successful run, download the `QManager-Mobile-debug` artifact, extract its ZIP and install `app-debug.apk` on your Android device. Debug APK is for testing; a production release requires signing and review.

## Usage
Connect Android to the modem network (or trusted VPN with modem routing). Start app, enter e.g. `http://192.168.225.1` plus existing QManager password if required. No first-time QManager password setup is implemented. Saved profiles store addresses only. Passwords and cookies stay in memory. Switch profiles by selecting a saved address then connecting again. Polling pauses in background; last data is marked offline after request failures. AT console requires explicit confirmation. No fake live data.

HTTP is enabled for user-entered modem addresses and is **unencrypted**; use only on trusted LAN or encrypted VPN. HTTPS uses standard Android certificate validation; self-signed untrusted certs will fail. Redirects are not followed. No certificate bypass. Do not expose QManager on the public internet.

## API contract / scope
- POST `auth/login.sh` with password; session cookies in memory.
- POST `auth/logout.sh` to end session, local session closed even on failure.
- GET `at_cmd/fetch_data.sh` for dashboard, including LTE, NR, device, traffic and connectivity objects.
- POST `at_cmd/send_command.sh` with command; output displayed as returned. Some builds may differ. No automatic retry of commands.
- Missing fields displayed as dashes; API responses with differing schemas may require an adapter.
- Not included: SIM switch UI, WebSocket streaming, background alerts, shell terminal, widgets, first-time auth setup. AT commands can alter settings; test read-only presets first.

## Check API compatibility (run on a trusted LAN from a terminal)
`curl -i http://192.168.225.1/cgi-bin/quecmanager/auth/check.sh`

If the endpoint returns JSON, note its behavior; a 401 for status indicates authentication is required. Avoid sharing passwords, cookies, IMEI, IMSI, ICCID, phone numbers and IP addresses from status responses. If the URL redirects or returns HTML, identify the QManager repository/version and API prefix before modifying the app.

## Validation status
Source project authored for this request; Android SDK and Gradle are unavailable in the creation environment, so no APK has been built and no emulator/device tests have been run. GitHub Actions workflow is provided but has not run. Verify login, status, AT output and disconnect on the actual backend before relying on this client.

## Native network management (v0.2.0)
Open Network, choose Band / Cell / APN, then Baca / Refresh konfigurasi. A successful configuration read is required before editing. All writes require confirmation and are followed by a readback after a five-second settling delay. A successful backend acknowledgement is not proof of a matching modem state; compare the returned values. No automatic write retry. Dashboard polling pauses during management requests. The request client serializes requests per session.

- Band: GET `bands/current.sh`; POST `bands/lock.sh` with category `band_type` (lte/nsa_nr5g/sa_nr5g) and numeric colon-separated `bands`. Supported lists are read from dashboard device capabilities. Restore all sends the entire reported supported list; not a separate factory reset. Editing is disabled when current/supported lists are missing or inconsistent.
- Cell: GET `tower/status.sh`; POST `tower/lock.sh` with type/action, LTE `cells` (up to 3 EARFCN/PCI pairs), or NR-SA `pci`, `arfcn`, `scs`, `band`. Separate unlock actions. NR-NSA cell lock is not implemented; LTE anchor locking is available. Persistence and failover settings are unchanged.
- APN: GET/POST `cellular/apn.sh`, actions `save` and `toggle`, CID 1–6; CID 2/3 and IMS/emergency/SOS profiles are conservatively read-only. Edit name, APN, PDP type, auth and credentials. Empty PAP/CHAP password retains existing credential according to this backend. NONE clears credentials. Activation/deactivation does not guarantee a default-route switch.

Contract references: https://github.com/dr-dolomite/QManager-RM520N/blob/main/hooks/use-band-locking.ts ; https://github.com/dr-dolomite/QManager-RM520N/blob/main/hooks/use-tower-locking.ts ; https://github.com/dr-dolomite/QManager-RM520N/blob/main/docs/API-REFERENCE.md . Other forks may differ; no raw AT fallback is attempted.

## Suggested hardware smoke test
On local Wi-Fi, verify login, then read each of the three tabs without writing. Record original band, cell and APN settings. Verify the reported values against the existing web UI. Test LTE lock on a known available cell, then unlock; test a supported band selection and restore; save a known working APN on the correct data CID and check actual connectivity. If a request times out or returns an error, read back before retrying; partial changes are possible. Do not test on your only remote access connection. Android SDK/Gradle and hardware remain unavailable; compilation and runtime behavior remain unverified.


## v0.3.0 — SMS and Network Mode
- Network > Mode > Baca / Refresh konfigurasi. Uses documented GET/POST `cellular/settings.sh`, sending only `mode_pref` and `nr5g_disable_mode`. Auto (AUTO/0), LTE Only (LTE/0), LTE + 5G (LTE:NR5G/0), NSA + LTE (LTE:NR5G/1), SA Only (NR5G/2). NSA requires LTE and can fall back to LTE; it cannot guarantee a 5G connection. Reads back both values after a write. No changes to band/cell locks, APN, roaming or SIM requested. Backend may reject or override settings. Test on LAN, not your only remote connection. Record original values first.
- SMS tab > Refresh to load messages. Inbox/Sent/All, search, pagination, selectable text, compose, reply to numeric senders, forward, per-message delete. Uses GET/POST `cellular/sms.sh` with the documented `send` and storage-aware `delete` payloads. Messages are not persisted by the Android client. No Android SMS permissions; SMS travels via the modem's SIM, and operator charges apply. Sent log availability depends on backend. Deletion is permanent; fresh read checks the selected record before deleting, but concurrent modem writes remain a potential race. No bulk delete, background polling, unread synchronization, SMSC editing or automatic forwarding. Single-part message limit intentionally conservative (160 characters in a basic GSM subset, otherwise 70 UTF-16 units). No automatic resend on ambiguous failure. Refresh manually after arrival; a successful send response is not a delivery receipt.
- Verify mode read, then test Auto/LTE/NSA/SA with known network coverage, restore original configuration. Read SMS first, send to a trusted test number, verify on receiving handset and check sent log; delete only a disposable test message. On error or timeout read back before retrying. Some modem/fork combinations have different schemas or unsupported SMS transport.
- References: https://github.com/dr-dolomite/QManager-RM520N/blob/main/docs/API-REFERENCE.md ; https://github.com/dr-dolomite/QManager-RM520N/blob/main/docs/reference/sms.md ; https://github.com/dr-dolomite/QManager-RM520N/blob/main/types/sms.ts . Source only; not compiled, no Android SDK/Gradle/device available.
