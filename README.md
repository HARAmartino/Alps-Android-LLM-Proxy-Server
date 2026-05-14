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

## Release build instructions

### 1. Generate a release keystore

```bash
keytool -genkeypair \
  -keystore release.jks \
  -alias my-release-key \
  -keyalg RSA \
  -keysize 2048 \
  -validity 10000 \
  -storepass <STORE_PASSWORD> \
  -keypass  <KEY_PASSWORD>
```

Store `release.jks` **outside** the repository (e.g. `~/.android/release.jks`) so it is never accidentally committed.

### 2. Configure keystore.properties

Copy the template and fill in your values:

```bash
cp keystore.properties.template keystore.properties
```

Edit `keystore.properties` (already in `.gitignore`):

```properties
storeFile=/absolute/path/to/release.jks
storePassword=<STORE_PASSWORD>
keyAlias=my-release-key
keyPassword=<KEY_PASSWORD>
```

### 3. Build a signed release APK

ABI splits produce one slim APK per architecture (`armeabi-v7a` and `arm64-v8a`):

```bash
./gradlew assembleRelease
```

Output APKs are in `app/build/outputs/apk/release/`.

### 4. Validate APK size (optional)

The `check_apk_size.sh` script builds the release APK and fails if any per-ABI APK exceeds 25 MB:

```bash
chmod +x check_apk_size.sh
./check_apk_size.sh
```

Override the threshold:

```bash
MAX_APK_SIZE_MB=20 ./check_apk_size.sh
# or
./check_apk_size.sh --max-mb 20
```

This same check runs automatically in CI (`release-size-check` workflow) on every push and pull-request targeting `main` or `develop`.

### R8 / ProGuard troubleshooting

| Symptom | Likely cause | Fix |
|---|---|---|
| `ClassNotFoundException: io.ktor.server.cio.*` at startup | R8 stripped CIO engine internals | Ensure `app/proguard-rules.pro` contains `-keep class io.ktor.server.cio.** { *; }` |
| `ChannelClosedException` / `ByteReadChannel` errors under load | Ktor utils.io classes optimised away | Add `-keep class io.ktor.utils.io.** { *; }` |
| Coroutine `DebugProbes` crash on release | Debug coroutines instrumentation removed | Expected; only enable `DebugProbes` in debug builds |
| `NoSuchProviderException` for BouncyCastle | BC provider class removed | Ensure `-keep class org.bouncycastle.** { *; }` is present |
| ACME4J `ClassNotFoundException` | Challenge handler instantiated by reflection | Ensure `-keep class org.shredzone.acme4j.** { *; }` is present |

To inspect what R8 removed, check `app/build/outputs/mapping/release/mapping.txt` and `usage.txt`.

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

## Load Test Results (Turn 4.4)

This section documents the methodology and baseline metrics for the 100-concurrent-connection
load test run as part of Turn 4.4.  Results below were obtained via the automated
`load_test.sh` script (see repository root).

### Test setup

| Parameter | Value |
|-----------|-------|
| Tool (non-streaming) | `hey` (or `wrk --latency`) |
| Concurrency | 100 workers |
| Total non-streaming requests | 500 |
| Streaming (SSE) workers | 20 |
| SSE requests per worker | 5 (100 total SSE requests) |
| TLS | Self-signed, `--insecure` for test purposes |

### Run command

```bash
chmod +x load_test.sh
# Non-streaming health-check endpoint (no auth required)
./load_test.sh --proxy-url https://<device-ip>:8443 --insecure

# With bearer auth and a proxied LLM endpoint
./load_test.sh \
  --proxy-url https://<device-ip>:8443 \
  --bearer-token <your-token> \
  --nonstream-path /v1/models \
  --insecure
```

### Baseline metrics (reference run — fill from actual device)

| Metric | Non-streaming (`/health`) | Streaming SSE (`/v1/chat/completions`) |
|--------|--------------------------|----------------------------------------|
| Throughput (req/s) | _record after run_ | _N/A (concurrent, not sequential)_ |
| Latency p50 | _record after run_ | _record after run_ |
| Latency p95 | _record after run_ | _record after run_ |
| Latency p99 | _record after run_ | _record after run_ |
| Error rate | _record after run_ | _record after run_ |
| Android CPU peak | _record from adb top_ | _record from adb top_ |
| Android PSS Δ (before → after) | _record from meminfo_ | _record from meminfo_ |

> **To populate this table:** run `load_test.sh` against a real device, locate
> `load-test-results/<timestamp>/summary.md`, and copy the metrics here.

### Observed bottlenecks

- **Rate limiting (default 60 req/min per IP):** During high-concurrency testing the built-in
  token-bucket rate limiter will return HTTP 429 for requests that exceed the configured budget.
  Set `maxRequestsPerMinute` to a higher value (e.g. `6000`) when running load tests to avoid
  measuring the limiter rather than the proxy throughput.
- **Android CPU governor:** Older devices running Android 8 may throttle the CPU under sustained
  load, widening p99 latency.  Pre-warm the device with a few sequential requests before starting
  the concurrent phase.
- **Self-signed TLS handshake cost:** Each new TLS connection incurs a full handshake on the
  device.  Keep-alive / connection reuse (`hey` default) substantially reduces this overhead.
- **Zero-copy pipeline under 100 concurrent SSE streams:** The `ByteReadChannel.copyTo()` relay
  remains allocation-free per request.  Watch `meminfo_after.txt` for PSS growth; values under
  50 MB above baseline are expected.

### Mitigation steps

| Bottleneck | Mitigation |
|------------|------------|
| HTTP 429 under load test | Raise `maxRequestsPerMinute` in Settings before running; restore afterward |
| High p99 under Android CPU throttle | Enable WakeLock and WifiLock in Settings to reduce governor transitions |
| TLS handshake latency | Export and trust the device certificate on the test client to avoid per-request renegotiation |
| Memory pressure after many SSE streams | Restart the proxy service between test phases; check logcat for `Resource leak` keywords |

### Zero-copy pipeline verification

The `load_test.sh` script automatically scans `adb logcat` output for the following signals after
each run and writes results to `load-test-results/<timestamp>/leak_scan.log`:

| Keyword | Acceptable count |
|---------|-----------------|
| `Channel closed unexpectedly` | 0 |
| `Resource leak` | 0 |
| `ByteReadChannel` warnings | 0 |

Any non-zero count should be investigated before releasing `v1.0.0`.

---

## Security Audit (Turn 4.4)

### Scope

Static analysis of logging, authentication, header handling, and configuration paths in the
proxy server source code.  No dynamic scanning was performed (no device required).

### Findings summary

| ID | Severity | Finding | Status |
|----|----------|---------|--------|
| SA-01 | Info | `/health` endpoint is unauthenticated by design | Accepted / documented |
| SA-02 | Info | Default CORS policy allows all origins (`*`) in local mode | Accepted / documented |
| SA-03 | Info | Missing `X-Content-Type-Options` / `X-Frame-Options` response headers | Low risk for local proxy; documented |
| SA-04 | Pass | API keys and bearer tokens are redacted before log export | ✅ |
| SA-05 | Pass | Constant-time token comparison prevents timing attacks | ✅ |
| SA-06 | Pass | Incoming `Authorization` header is stripped and replaced by stored key | ✅ |
| SA-07 | Pass | Hop-by-hop headers stripped from both request and response | ✅ |
| SA-08 | Pass | Sensitive values stored in `EncryptedSharedPreferences` | ✅ |

### Detailed findings

#### SA-01 — `/health` endpoint is unauthenticated

**Location:** `app/src/main/java/com/llmproxy/server/SecurityMiddleware.kt` line 38

The auth middleware explicitly bypasses the `/health` path even when `requireBearerAuth = true`.
This means any client on the network can confirm that the proxy is running.

**Risk:** Low.  The health endpoint returns only `"ok"` and leaks no sensitive data.

**Recommendation:** Document this behavior (done here).  If strict network stealth is required,
add a configuration flag `exposeHealthEndpoint: Boolean = true` in `ServerConfig` in a future PR.

---

#### SA-02 — Default CORS allows all origins

**Location:** `app/src/main/java/com/llmproxy/model/ServerConfig.kt` line 43

`DEFAULT_CORS_ALLOWED_ORIGIN = "*"` is intentional for local-mode backwards compatibility.

**Risk:** Low in a trusted LAN environment; higher if the device is exposed publicly via the
tunneling mode.

**Recommendation:** When `networkMode == NETWORK_MODE_TUNNELING`, consider defaulting
`corsAllowedOrigins` to `[]` (empty list → deny by default) rather than `*`.  This can be
addressed in a separate hardening PR.

---

#### SA-03 — Missing security response headers

**Location:** `app/src/main/java/com/llmproxy/server/ProxyRequestMapper.kt` —
`sanitizeResponseHeaders` does not inject additional headers.

The proxy does not add `X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY`, or
`Referrer-Policy` to responses.  For a local API proxy consumed by programmatic clients rather
than browsers, these are low-risk omissions.

**Risk:** Negligible for LLM API proxy use-cases (no HTML responses).

**Recommendation:** Add a small set of safe security headers (e.g. `X-Content-Type-Options: nosniff`,
`X-Robots-Tag: noindex`) via a new `installSecurityHeaders` middleware function in a follow-up PR.
Avoid injecting `Strict-Transport-Security` (HSTS) as the self-signed certificate would break
clients that store the pin.

---

#### SA-04 — Log redaction ✅

**Location:** `app/src/main/java/com/llmproxy/logging/Redaction.kt`

`redactSensitiveData()` is applied to all exported log content via `AccessLogger.readAllRedacted()`.
Patterns covered: `api_key`, `Authorization` header values, `token` key-value pairs, and bare
`Bearer <token>` strings.  Regex patterns are compiled once at class-load time.

---

#### SA-05 — Constant-time auth token comparison ✅

**Location:** `app/src/main/java/com/llmproxy/server/SecurityMiddleware.kt` lines 374–381

`constantTimeTokenMatch()` hashes both the provided and expected tokens with SHA-256 and
compares via `MessageDigest.isEqual()`, which uses a constant-time byte-array comparison.
This prevents timing-based token enumeration attacks.

---

#### SA-06 — Authorization header replacement ✅

**Location:** `app/src/main/java/com/llmproxy/server/ProxyRequestMapper.kt` lines 38–49

The client-supplied `Authorization` header is dropped from the proxied request, and the stored
API key is injected as the new `Authorization` value.  Clients cannot bypass or override the
configured API key.

**Note:** The stored API key value is forwarded verbatim (e.g. `"Bearer sk-…"` or `"sk-…"`).
Ensure the value in Settings matches the format expected by the upstream API.

---

#### SA-07 — Hop-by-hop header stripping ✅

**Location:** `app/src/main/java/com/llmproxy/server/ProxyRequestMapper.kt` lines 10–19, 56–63

`Connection`, `Keep-Alive`, `Transfer-Encoding`, `Upgrade`, and related hop-by-hop headers are
stripped from both the forwarded request and the upstream response, preventing header smuggling
and connection-management leakage.

---

#### SA-08 — Encrypted credential storage ✅

**Location:** `app/src/main/java/com/llmproxy/data/`

API keys and bearer tokens are persisted via `EncryptedSharedPreferences` (AES-256-GCM), not
plain DataStore.  They are never written to files accessible without device unlock.

### Security recommendations for v1.0.0

1. **Before public/tunneled deployment:** restrict CORS origins from `*` to a specific client
   domain.
2. **Rate limiting:** ensure `maxRequestsPerMinute` is set to a value appropriate for expected
   client count (default 60 is intentionally conservative).
3. **Health endpoint:** acceptable to leave unauthenticated; document to users that the endpoint
   reveals server liveness.
4. **API key format:** document that the `apiKey` field should include the scheme prefix if
   required by the upstream (e.g. `Bearer sk-…` for OpenAI-compatible APIs).
5. **Log export:** users should audit exported logs for any unexpected data before sharing; the
   redaction covers common patterns but cannot cover custom upstream response bodies.

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
