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

## How to run the long-term stability (soak) test

The 72-hour soak test validates zero-downtime certificate renewal, graceful restart behaviour
and memory/CPU stability over an extended run.

### Prerequisites

- Android device (or emulator) with the debug build installed.
- `adb` available on the test machine.
- A load-generator such as [hey](https://github.com/rakyll/hey) or `curl` in a loop.

### Step 1 – Start continuous traffic

```bash
# Keep a stream of HTTPS requests going throughout the test.
# Adjust the endpoint to match your device's local IP and port.
while true; do
  curl -sk https://192.168.1.42:8443/v1/models -o /dev/null
  sleep 5
done
```

### Step 2 – Trigger a manual certificate renewal

From the Dashboard, tap **Settings → Request Let's Encrypt Certificate**.
Alternatively, fire the renewal via `adb`:

```bash
# Force the WorkManager job to run immediately (requires work-testing artifact in debug builds).
adb shell am broadcast \
  -a androidx.work.diagnostics.REQUEST_DIAGNOSTICS \
  --es request run_all com.llmproxy
```

Watch the snackbar on the dashboard for the following sequence:
1. `Renewing certificate…`
2. `Certificate renewed. No restart required for new connections.`

### Step 3 – Monitor memory and CPU

Run the following commands at 15-minute intervals (or pipe to a file for offline analysis):

```bash
# Heap allocation — watch for unbounded growth over 72 h.
adb shell dumpsys meminfo com.llmproxy | grep -E "TOTAL|Native Heap|Java Heap"

# CPU usage — should stay below 5 % at idle between requests.
adb shell top -n 1 | grep com.llmproxy

# Battery stats delta since last charge.
adb shell dumpsys batterystats --charged com.llmproxy | grep -E "Foreground|CPU"
```

### Step 4 – Verify log rotation

The access log and system log are rotated automatically.  Confirm files do not grow
without bound:

```bash
# Access log size (should stay under ~10 MB with default rotation).
adb shell run-as com.llmproxy ls -lh files/logs/

# Tail the system log for ERROR-level entries.
adb shell run-as com.llmproxy cat files/logs/system.log | grep ERROR | tail -20
```

### Step 5 – Inspect graceful-restart metrics

After each renewal-triggered restart, open the Dashboard and verify:

- **Last restart — graceful** count increases.
- **Last restart — forced** count remains 0 under normal load (≤ 5 concurrent streaming connections).

### Known issues

- **Minor latency spike during restart (expected)**: The graceful drain window pauses new
  TLS handshakes for up to 30 s while existing connections finish.  Clients experience
  a single request delay; streaming connections are unaffected until they complete.
- On heavily loaded devices, the drain window may expire before all connections close,
  causing `forced close` counts > 0.  This is a known trade-off between latency and
  zero-downtime and is documented in the `ServerLifecycleManager` drain-window state
  machine comments.
