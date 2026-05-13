# LLM Proxy Server

Repository: `HARAmartino/Alps-Android-LLM-Proxy-Server`

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
