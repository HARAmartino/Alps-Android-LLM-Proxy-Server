# LLM Proxy Server

Repository: `HARAmartino/Alps-Android-LLM-Proxy-Server`

古いAndroidスマートフォン（Android 8.0+）を、ローカルネットワーク上で利用できる HTTPS 対応・ストリーミング転送可能な汎用 LLM API プロキシサーバーとして活用するためのアプリケーションです。

Android 8.0+ application skeleton that turns an older phone into a local-network HTTPS proxy for upstream LLM APIs.

## MVP scope

This PR establishes a local-only foundation with:

- Jetpack Compose dashboard + settings UI
- Foreground service to host the proxy lifecycle
- Self-signed TLS certificate generation and export
- Ktor CIO HTTPS proxy server bound to `0.0.0.0:8443` by default
- Streaming request/response forwarding skeleton using Ktor channels
- DataStore + encrypted preferences persistence for runtime settings

Explicitly **not** included yet:

- Let's Encrypt / ACME
- Public tunneling
- Rate limiting / IP allowlists / CORS
- Advanced observability and battery optimization guidance

## Architecture overview

```text
MainActivity
  -> MainViewModel (StateFlow / UDF)
      -> SettingsRepository (DataStore + EncryptedSharedPreferences)
      -> ServerLifecycleManager
          -> ProxyForegroundService
          -> ProxyServerFactory (Ktor CIO server)
          -> UpstreamHttpClientFactory (Ktor CIO client)
          -> SslCertGenerator / SslContextLoader
```

## Package layout

```text
app/src/main/java/com/llmproxy/
├── client/   Upstream Ktor client configuration
├── data/     DataStore repository + encrypted preferences wrapper
├── model/    App, server, and UI state models
├── server/   SSL context loader, proxy routing, header/url mapping
├── service/  ForegroundService + server lifecycle management
├── ui/       Compose navigation, dashboard, settings, certificate card
├── util/     Certificate generation, logging, network helpers
└── MainActivity.kt
```

## Build requirements

- Java 17
- Android SDK 34
- Android build-tools 34+

## Build and test

```bash
./gradlew test
./gradlew assembleDebug
```

## Run instructions

1. Install the debug build on an Android 8.0+ device.
2. Open the app and grant notification permission on Android 13+.
3. In **Settings**, configure:
   - Upstream URL (for example `https://api.openai.com/v1`)
   - API key
   - Listen port (defaults to `8443`)
   - Bind address (defaults to `0.0.0.0`)
4. Export the generated `.crt` file and trust it on client devices if you want to avoid TLS warnings.
5. Return to **Dashboard** and start the local proxy service.
6. Send HTTPS requests to the displayed local endpoint; requests are forwarded to the configured upstream.

## Streaming design notes

- Incoming bodies are consumed from `call.receiveChannel()`.
- Upstream responses are streamed back with `OutgoingContent.ReadChannelContent` backed by `bodyAsChannel()`.
- The implementation avoids full-body buffering so the MVP is ready for streamed token responses.

## Security notes

- API keys are stored in encrypted shared preferences.
- Non-secret runtime configuration is stored in DataStore.
- The generated certificate is self-signed and local-only by design.

## Known MVP limitations

- Certificate trust/import is manual.
- The proxy rewrites `Authorization` with the stored value exactly as entered.
- Running server configuration changes require restarting the foreground service.
- Active connection counts are in-memory only.
- The current certificate SAN covers `localhost` and `127.0.0.1`; LAN IP trust depends on client acceptance of the exported certificate.
- Tunnel reconnection may take 5-15s after network switch.
- Latency metrics are approximate and reset on app restart.

## 72-hour soak test execution (Turn 3.5)

Turn 3.5 validates long-running stability with realistic traffic, periodic network transitions,
hourly memory/CPU snapshots, and daily certificate-renewal checks.

### Scope and acceptance criteria

- Runtime duration: **72 hours** on physical Android 8.0 / 10 / 14 representative devices.
- Traffic profile: **1 request every 5 minutes**, alternating non-streaming and streaming requests.
- Network transitions: **Wi-Fi ↔ Mobile every 6 hours**.
- Renewal checks: **forced every 24 hours** and normal auto-renew trigger validation when expiry is `<30 days` (approximately `<720 hours`).
- Memory alert rule: flag if heap/PSS grows by **>10% over 24h** with no recovery.
- Log-based leak detection for the zero-copy proxy path: no coroutine channel/stream leak signals such as `"Channel closed unexpectedly"`, `"Resource leak"`, or suspicious `ByteReadChannel` warnings.
- Renewal/restart checks: restart drain window completes within **30s** (`DRAIN_CONNECTION_TIMEOUT_MS = 30_000`), no active-connection downtime.

Before each run, confirm the connected device API level:

```bash
adb -s <adb-serial> shell getprop ro.build.version.release
adb -s <adb-serial> shell getprop ro.build.version.sdk
```

### Automated soak harness

Run from repository root:

```bash
chmod +x ./soak_test.sh
./soak_test.sh --serial <adb-serial> --proxy-url https://<device-ip>:8443 --duration-hours 72
```

Optional flags:

- `--request-interval-sec` (default: `300`)
- `--network-switch-interval-sec` (default: `21600`)
- `--renewal-interval-sec` (default: `86400`)
- `--sample-interval-sec` (default: `3600`)
- `--output-dir` (default: `./soak-results`)

The script collects:

- `meminfo_hourly.csv` + `meminfo_raw/` (`adb shell dumpsys meminfo com.llmproxy`)
- `cpu_hourly.csv` + `cpu_raw/` (`adb shell top -n 1 -p <pid>`)
- `request_log.csv` (request success/failure, HTTP codes, latency)
- `renewal_events.log` (daily renewal trigger attempts and trigger method)
- `network_switch.log` (Wi-Fi/Mobile toggles every 6h)
- `leak_scan.log` + `logcat_app.log` (leak/warning keyword scans)
- `alerts.log` (heap growth >10% over 24h without recovery heuristic)
- `summary.md` (per-run markdown summary for README/PR copy)

### Certificate-renewal verification points

During or after each 24h renewal trigger, verify:

1. Renewal starts and succeeds (UI snackbar / logs).
2. Auto-renew condition is evaluated at `<30 days` remaining.
3. Graceful restart does not exceed the 30s drain window.
4. No forced-closure spike for normal traffic (forced count should remain 0 unless overload).

### Soak Test Results

Record one block per device run (Android 8.0 / 10 / 14) using `soak-results/**/summary.md`.

#### Result table (fill after each run)

| Device / Android | Duration | Requests | Renewal checks | Heap Δ (24h max) | CPU spike max | Leak warnings | Restart drain | Downtime |
| --- | --- | ---: | ---: | ---: | ---: | ---: | --- | --- |
| Device-A / 8.0 | _pending_ | _pending_ | _pending_ | _pending_ | _pending_ | _pending_ | _pending_ | _pending_ |
| Device-B / 10 | _pending_ | _pending_ | _pending_ | _pending_ | _pending_ | _pending_ | _pending_ | _pending_ |
| Device-C / 14 | _pending_ | _pending_ | _pending_ | _pending_ | _pending_ | _pending_ | _pending_ | _pending_ |

#### Memory/CPU graphs (if available)

Generate graphs from CSV outputs (example):

```bash
python3 - <<'PY'
import csv, pathlib
from statistics import mean
root = pathlib.Path("soak-results")
for csv_path in root.glob("**/meminfo_hourly.csv"):
    rows = list(csv.DictReader(csv_path.open()))
    if not rows:
        continue
    pss = [int(r["total_pss_kb"]) for r in rows if r["total_pss_kb"].isdigit()]
    print(f"{csv_path}: samples={len(pss)} avg_pss_kb={int(mean(pss)) if pss else 'n/a'}")
PY
```

Attach generated chart images to the PR and reference them here once available.

#### Observed issues and mitigation tracking

- **Minor latency spike during renewal restart (expected):** brief handshake delay during drain window.
  - **Mitigation:** keep drain window at 30s and schedule renewals in low-traffic windows.
- **OEM network toggle quirks (vendor-dependent):** some devices delay Wi-Fi/mobile state transitions.
  - **Mitigation:** add 15-30s settling delay after each toggle and track per-vendor behavior in run notes.
- **Potential forced close under heavy streaming concurrency:** restart can force-close if active streams exceed drain budget.
  - **Mitigation:** reduce concurrent streams during planned renewal windows; investigate adaptive drain only in a separate fix PR if needed.
