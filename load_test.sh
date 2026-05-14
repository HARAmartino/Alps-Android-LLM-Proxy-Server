#!/usr/bin/env bash
# load_test.sh — 100-concurrent-connection load test for the LLM Proxy Server.
#
# Requires:
#   - hey  (https://github.com/rakyll/hey) or wrk (https://github.com/wg/wrk)
#   - curl ≥ 7.68 (streaming / SSE)
#   - adb  (Android Platform Tools) connected to the target device
#
# Usage:
#   ./load_test.sh --proxy-url https://<device-ip>:8443 [options]
#
# Quick start (insecure TLS for self-signed cert):
#   ./load_test.sh --proxy-url https://192.168.1.42:8443 --insecure
#
set -euo pipefail

# ── Defaults ────────────────────────────────────────────────────────────────
PROXY_URL=""
SERIAL=""
OUTPUT_DIR="./load-test-results"
CONCURRENCY=100
TOTAL_REQUESTS=500            # Non-streaming: total requests sent by hey/wrk
STREAM_WORKERS=20             # Streaming: parallel curl SSE workers
STREAM_REQUESTS_EACH=5        # Streaming requests per worker
NONSTREAM_PATH="/health"      # Default health-check endpoint (no auth required)
STREAM_PATH="/v1/chat/completions"
STREAM_MODEL="gpt-4o-mini"
BEARER_TOKEN=""
CA_CERT_PATH=""
ALLOW_INSECURE_TLS=0
SKIP_ADB=0
USE_WRK=0
WRK_DURATION_SEC=30

PACKAGE_NAME="com.llmproxy"

usage() {
  cat <<'EOF'
Usage:
  ./load_test.sh --proxy-url https://<device-ip>:8443 [options]

Options:
  --proxy-url <value>          Proxy base URL  (required)
  --serial <value>             ADB device serial (optional)
  --concurrency <n>            Concurrent workers for non-streaming test (default: 100)
  --total-requests <n>         Total non-streaming requests (hey mode, default: 500)
  --stream-workers <n>         Parallel SSE/streaming workers (default: 20)
  --stream-requests-each <n>   SSE requests per worker (default: 5)
  --nonstream-path <path>      Non-streaming endpoint path (default: /health)
  --stream-path <path>         Streaming endpoint path (default: /v1/chat/completions)
  --stream-model <model>       Model field in streaming body (default: gpt-4o-mini)
  --bearer-token <token>       Bearer token for Authorization header
  --ca-cert <path>             PEM certificate for TLS verification
  --insecure                   Use curl -k / hey --insecure (skip TLS verification)
  --output-dir <path>          Results directory (default: ./load-test-results)
  --skip-adb                   Skip adb device monitoring
  --wrk                        Use wrk instead of hey for non-streaming phase
  --wrk-duration <sec>         wrk test duration in seconds (default: 30)
  -h, --help                   Show this help
EOF
}

# ── Argument parsing ─────────────────────────────────────────────────────────
while [[ $# -gt 0 ]]; do
  case "$1" in
    --proxy-url)            PROXY_URL="${2:-}"; shift 2 ;;
    --serial)               SERIAL="${2:-}"; shift 2 ;;
    --concurrency)          CONCURRENCY="${2:-}"; shift 2 ;;
    --total-requests)       TOTAL_REQUESTS="${2:-}"; shift 2 ;;
    --stream-workers)       STREAM_WORKERS="${2:-}"; shift 2 ;;
    --stream-requests-each) STREAM_REQUESTS_EACH="${2:-}"; shift 2 ;;
    --nonstream-path)       NONSTREAM_PATH="${2:-}"; shift 2 ;;
    --stream-path)          STREAM_PATH="${2:-}"; shift 2 ;;
    --stream-model)         STREAM_MODEL="${2:-}"; shift 2 ;;
    --bearer-token)         BEARER_TOKEN="${2:-}"; shift 2 ;;
    --ca-cert)              CA_CERT_PATH="${2:-}"; shift 2 ;;
    --insecure)             ALLOW_INSECURE_TLS=1; shift ;;
    --output-dir)           OUTPUT_DIR="${2:-}"; shift 2 ;;
    --skip-adb)             SKIP_ADB=1; shift ;;
    --wrk)                  USE_WRK=1; shift ;;
    --wrk-duration)         WRK_DURATION_SEC="${2:-}"; shift 2 ;;
    -h|--help) usage; exit 0 ;;
    *) echo "Unknown argument: $1" >&2; usage; exit 1 ;;
  esac
done

if [[ -z "$PROXY_URL" ]]; then
  echo "Error: --proxy-url is required." >&2
  usage
  exit 1
fi

# Validate model name to prevent injection
if [[ ! "$STREAM_MODEL" =~ ^[A-Za-z0-9._:-]+$ ]]; then
  echo "Error: --stream-model contains unsupported characters." >&2
  exit 1
fi

# ── Dependency checks ─────────────────────────────────────────────────────────
check_tool() {
  if ! command -v "$1" >/dev/null 2>&1; then
    echo "Warning: '$1' not found in PATH — skipping that phase." >&2
    return 1
  fi
  return 0
}

HAS_HEY=0; HAS_WRK=0; HAS_CURL=0; HAS_ADB=0
check_tool hey   && HAS_HEY=1   || true
check_tool wrk   && HAS_WRK=1   || true
check_tool curl  && HAS_CURL=1  || true
check_tool adb   && HAS_ADB=1   || true

if (( USE_WRK == 1 && HAS_WRK == 0 )); then
  echo "Error: --wrk requested but 'wrk' not found." >&2; exit 1
fi
if (( USE_WRK == 0 && HAS_HEY == 0 )); then
  echo "Warning: 'hey' not found; non-streaming phase will be skipped." >&2
fi
if (( HAS_CURL == 0 )); then
  echo "Warning: 'curl' not found; streaming phase will be skipped." >&2
fi
if (( SKIP_ADB == 0 && HAS_ADB == 0 )); then
  echo "Warning: 'adb' not found; device monitoring will be skipped." >&2
  SKIP_ADB=1
fi

# ── TLS helpers ──────────────────────────────────────────────────────────────
CURL_TLS_OPTS=()
HEY_TLS_OPTS=()
if [[ -n "$CA_CERT_PATH" ]]; then
  CURL_TLS_OPTS=(--cacert "$CA_CERT_PATH")
elif (( ALLOW_INSECURE_TLS == 1 )); then
  CURL_TLS_OPTS=(-k)
  HEY_TLS_OPTS=(-insecure)
  echo "Warning: TLS verification disabled. Use --ca-cert for production testing." >&2
fi

AUTH_HEADER=""
if [[ -n "$BEARER_TOKEN" ]]; then
  AUTH_HEADER="Authorization: Bearer ${BEARER_TOKEN}"
fi

# ── Setup ────────────────────────────────────────────────────────────────────
RUN_TS="$(date -u +%Y%m%dT%H%M%SZ)"
RUN_DIR="${OUTPUT_DIR%/}/${RUN_TS}"
mkdir -p "$RUN_DIR"

NONSTREAM_REPORT="$RUN_DIR/nonstream_report.txt"
STREAM_REPORT="$RUN_DIR/stream_report.txt"
LEAK_SCAN_LOG="$RUN_DIR/leak_scan.log"
MEM_BEFORE="$RUN_DIR/meminfo_before.txt"
MEM_AFTER="$RUN_DIR/meminfo_after.txt"
CPU_DURING="$RUN_DIR/cpu_during.txt"
SUMMARY_MD="$RUN_DIR/summary.md"
LOGCAT_FILE="$RUN_DIR/logcat_load_test.log"

timestamp() { date -u +%FT%TZ; }
log() { echo "[$(timestamp)] $*"; }

log "Results directory: $RUN_DIR"
log "Proxy URL: $PROXY_URL"
log "Concurrency: $CONCURRENCY  |  Total non-stream requests: $TOTAL_REQUESTS"
log "Streaming workers: $STREAM_WORKERS  |  Requests each: $STREAM_REQUESTS_EACH"

# ── ADB helpers ──────────────────────────────────────────────────────────────
ADB=(adb)
if [[ -n "$SERIAL" ]]; then ADB+=(-s "$SERIAL"); fi

adb_cmd() { "${ADB[@]}" "$@"; }

pid_of_app() {
  adb_cmd shell pidof "$PACKAGE_NAME" 2>/dev/null | tr -d '\r' | awk 'NR==1{print $1}'
}

# ── Device snapshot helpers ──────────────────────────────────────────────────
snapshot_meminfo() {
  local dest="$1"
  if (( SKIP_ADB == 1 )); then return; fi
  log "Capturing meminfo → $dest"
  adb_cmd shell dumpsys meminfo "$PACKAGE_NAME" > "$dest" 2>/dev/null || true
}

snapshot_cpu() {
  local dest="$1"
  if (( SKIP_ADB == 1 )); then return; fi
  local pid
  pid="$(pid_of_app)"
  if [[ -n "$pid" ]]; then
    log "Capturing CPU snapshot (pid=$pid) → $dest"
    adb_cmd shell top -n 3 -p "$pid" > "$dest" 2>/dev/null || true
  else
    log "Warning: app PID not found for CPU snapshot"
    echo "app PID not found" > "$dest"
  fi
}

scan_logcat_leaks() {
  local logcat_file="$1" out="$2"
  {
    echo "=== Leak keyword scan at $(timestamp) ==="
    echo ""
    local c1 c2 c3
    c1="$(grep -Eic 'Channel closed unexpectedly' "$logcat_file" 2>/dev/null || echo 0)"
    c2="$(grep -Eic 'Resource leak'               "$logcat_file" 2>/dev/null || echo 0)"
    c3="$(grep -Eic 'ByteReadChannel'              "$logcat_file" 2>/dev/null || echo 0)"
    echo "Channel closed unexpectedly : $c1"
    echo "Resource leak               : $c2"
    echo "ByteReadChannel warnings    : $c3"
    echo ""
    if (( c1 > 0 || c2 > 0 )); then
      echo "ALERT: Potential resource-leak signals detected — review logcat_load_test.log"
    else
      echo "OK: No channel-close or resource-leak keywords found."
    fi
  } > "$out"
}

# ── Start logcat ─────────────────────────────────────────────────────────────
LOGCAT_PID=""
if (( SKIP_ADB == 0 )); then
  log "Starting logcat capture → $LOGCAT_FILE"
  adb_cmd logcat -c || true
  adb_cmd logcat -v time "${PACKAGE_NAME}:V" '*:S' > "$LOGCAT_FILE" 2>/dev/null &
  LOGCAT_PID=$!
fi

cleanup() {
  if [[ -n "$LOGCAT_PID" ]] && ps -p "$LOGCAT_PID" >/dev/null 2>&1; then
    kill "$LOGCAT_PID" >/dev/null 2>&1 || true
  fi
}
trap cleanup EXIT

# ── Pre-test memory snapshot ──────────────────────────────────────────────────
snapshot_meminfo "$MEM_BEFORE"

# ── Phase 1: Non-streaming load test ─────────────────────────────────────────
NONSTREAM_URL="${PROXY_URL%/}${NONSTREAM_PATH}"
NONSTREAM_OK=0

log "=== Phase 1: Non-streaming load test ==="
log "URL: $NONSTREAM_URL  Concurrency: $CONCURRENCY  Requests: $TOTAL_REQUESTS"

if (( USE_WRK == 1 && HAS_WRK == 1 )); then
  # wrk mode: continuous load for a fixed duration
  WRK_HEADER_ARG=()
  if [[ -n "$AUTH_HEADER" ]]; then
    WRK_HEADER_ARG=(-H "$AUTH_HEADER")
  fi
  log "Running wrk (duration=${WRK_DURATION_SEC}s, threads=${CONCURRENCY})..."
  wrk -t "$CONCURRENCY" -c "$CONCURRENCY" -d "${WRK_DURATION_SEC}s" \
    "${WRK_HEADER_ARG[@]+"${WRK_HEADER_ARG[@]}"}" \
    "$NONSTREAM_URL" \
    2>&1 | tee "$NONSTREAM_REPORT" && NONSTREAM_OK=1 || true

elif (( HAS_HEY == 1 )); then
  # hey mode: fixed number of total requests
  HEY_AUTH_ARG=()
  if [[ -n "$AUTH_HEADER" ]]; then
    HEY_AUTH_ARG=(-H "$AUTH_HEADER")
  fi
  log "Running hey (concurrency=${CONCURRENCY}, total=${TOTAL_REQUESTS})..."
  hey -n "$TOTAL_REQUESTS" -c "$CONCURRENCY" \
    "${HEY_TLS_OPTS[@]+"${HEY_TLS_OPTS[@]}"}" \
    "${HEY_AUTH_ARG[@]+"${HEY_AUTH_ARG[@]}"}" \
    "$NONSTREAM_URL" \
    2>&1 | tee "$NONSTREAM_REPORT" && NONSTREAM_OK=1 || true
else
  log "Skipping Phase 1: neither hey nor wrk available."
  echo "(Phase 1 skipped: hey/wrk not found)" > "$NONSTREAM_REPORT"
fi

# Capture CPU during/after Phase 1 load
snapshot_cpu "$CPU_DURING"

# ── Phase 2: Streaming (SSE) concurrent load ──────────────────────────────────
log "=== Phase 2: Streaming (SSE) load test ==="
STREAM_URL="${PROXY_URL%/}${STREAM_PATH}"
STREAM_SUCCESS=0
STREAM_FAILURE=0
STREAM_PIDS=()

streaming_worker() {
  local worker_id="$1" dest="$2"
  local ok=0 fail=0
  for (( i=0; i < STREAM_REQUESTS_EACH; i++ )); do
    local body
    body="{\"model\":\"${STREAM_MODEL}\",\"messages\":[{\"role\":\"user\",\"content\":\"load-test-${worker_id}-${i}\"}],\"stream\":true,\"max_tokens\":8}"
    local extra_headers=()
    if [[ -n "$AUTH_HEADER" ]]; then extra_headers=(-H "$AUTH_HEADER"); fi
    local code
    code="$(curl "${CURL_TLS_OPTS[@]+"${CURL_TLS_OPTS[@]}"}" \
      --connect-timeout 10 --max-time 30 -s -N \
      -H 'Content-Type: application/json' \
      "${extra_headers[@]+"${extra_headers[@]}"}" \
      -d "$body" \
      -o /dev/null -w '%{http_code}' \
      "$STREAM_URL" 2>/dev/null || echo "000")"
    if [[ "$code" == "200" || "$code" == "201" ]]; then
      ok=$(( ok + 1 ))
    else
      fail=$(( fail + 1 ))
    fi
  done
  echo "$ok $fail" > "$dest"
}

if (( HAS_CURL == 1 )); then
  log "Launching $STREAM_WORKERS streaming workers ($STREAM_REQUESTS_EACH requests each)..."
  WORKER_RESULT_DIR="$(mktemp -d /tmp/lt_workers.XXXXXX)"
  for (( w=1; w<=STREAM_WORKERS; w++ )); do
    streaming_worker "$w" "${WORKER_RESULT_DIR}/worker_${w}.txt" &
    STREAM_PIDS+=($!)
  done
  # Wait for all workers to finish
  for pid in "${STREAM_PIDS[@]}"; do
    wait "$pid" || true
  done
  # Aggregate results
  for result_file in "${WORKER_RESULT_DIR}"/worker_*.txt; do
    if [[ -f "$result_file" ]]; then
      read -r ok fail < "$result_file" || true
      STREAM_SUCCESS=$(( STREAM_SUCCESS + ${ok:-0} ))
      STREAM_FAILURE=$(( STREAM_FAILURE + ${fail:-0} ))
    fi
  done
  rm -rf "$WORKER_RESULT_DIR"
  log "Streaming results: success=$STREAM_SUCCESS  failure=$STREAM_FAILURE"
  {
    echo "=== Streaming (SSE) load test results ==="
    echo "Workers     : $STREAM_WORKERS"
    echo "Requests each: $STREAM_REQUESTS_EACH"
    echo "Total sent  : $(( STREAM_WORKERS * STREAM_REQUESTS_EACH ))"
    echo "Success     : $STREAM_SUCCESS"
    echo "Failure     : $STREAM_FAILURE"
    total_stream=$(( STREAM_WORKERS * STREAM_REQUESTS_EACH ))
    if (( total_stream > 0 )); then
      echo "Error rate  : $(awk "BEGIN{printf \"%.1f\", ($STREAM_FAILURE/$total_stream)*100}")%"
    fi
  } > "$STREAM_REPORT"
else
  log "Skipping Phase 2: curl not available."
  echo "(Phase 2 skipped: curl not found)" > "$STREAM_REPORT"
fi

# ── Post-test memory snapshot + leak scan ────────────────────────────────────
snapshot_meminfo "$MEM_AFTER"

if (( SKIP_ADB == 0 )); then
  sleep 2  # give logcat a moment to flush
  scan_logcat_leaks "$LOGCAT_FILE" "$LEAK_SCAN_LOG"
  log "Leak scan → $LEAK_SCAN_LOG"
fi

# ── Extract meminfo PSS helper ────────────────────────────────────────────────
pss_from_file() {
  local f="$1"
  if [[ ! -f "$f" ]]; then echo "n/a"; return; fi
  local v
  v="$(awk '/TOTAL PSS:/ {print $3; exit} /^TOTAL[[:space:]]+[0-9]+/ {print $2; exit}' "$f" \
       | tr -d '\r' || true)"
  echo "${v:-n/a}"
}

PSS_BEFORE="$(pss_from_file "$MEM_BEFORE")"
PSS_AFTER="$(pss_from_file  "$MEM_AFTER")"

# ── Extract throughput from hey report ───────────────────────────────────────
extract_hey_metric() {
  local label="$1" file="$2"
  grep -i "$label" "$file" 2>/dev/null | head -1 | awk '{print $NF}' || echo "n/a"
}

THROUGHPUT="n/a"; P95="n/a"; P99="n/a"; ERROR_RATE="n/a"
if [[ -f "$NONSTREAM_REPORT" ]] && (( NONSTREAM_OK == 1 )); then
  if (( USE_WRK == 1 )); then
    THROUGHPUT="$(grep -i "Requests/sec" "$NONSTREAM_REPORT" 2>/dev/null | awk '{print $2}' | head -1 || echo "n/a")"
    P95="$(grep -i "99%" "$NONSTREAM_REPORT" 2>/dev/null | awk '{print $2}' | head -1 || echo "n/a") (from wrk 99th percentile)"
    P99="n/a (use --latency flag with wrk for percentiles)"
  else
    THROUGHPUT="$(grep -i "Requests/sec" "$NONSTREAM_REPORT" 2>/dev/null | awk '{print $NF}' | head -1 || echo "n/a")"
    P95="$(awk '/95%/{print $2; exit}' "$NONSTREAM_REPORT" 2>/dev/null || echo "n/a")"
    P99="$(awk '/99%/{print $2; exit}' "$NONSTREAM_REPORT" 2>/dev/null || echo "n/a")"
    ERROR_RATE="$(grep -i "Error distribution" -A5 "$NONSTREAM_REPORT" 2>/dev/null | head -6 || echo "0 errors")"
    log "Error distribution: ${ERROR_RATE}"
  fi
fi

# ── Write summary markdown ────────────────────────────────────────────────────
cat > "$SUMMARY_MD" <<EOF
# Load Test Summary

- Run timestamp (UTC): \`${RUN_TS}\`
- Proxy URL: \`${PROXY_URL}\`
- Concurrency: \`${CONCURRENCY}\`
- Total non-streaming requests: \`${TOTAL_REQUESTS}\`
- Streaming workers × requests: \`${STREAM_WORKERS} × ${STREAM_REQUESTS_EACH}\`

## Phase 1 – Non-streaming (hey / wrk)

| Metric | Value |
|--------|-------|
| Throughput (req/s) | ${THROUGHPUT} |
| Latency p95 | ${P95} |
| Latency p99 | ${P99} |

Full report: \`nonstream_report.txt\`

## Phase 2 – Streaming SSE (curl)

| Metric | Value |
|--------|-------|
| Total streaming requests | $((STREAM_WORKERS * STREAM_REQUESTS_EACH)) |
| Successful | ${STREAM_SUCCESS} |
| Failed | ${STREAM_FAILURE} |

Full report: \`stream_report.txt\`

## Device Memory (PSS)

| Snapshot | Total PSS (KB) |
|----------|---------------|
| Before load test | ${PSS_BEFORE} |
| After load test  | ${PSS_AFTER} |

## Leak Scan

See \`leak_scan.log\` for keyword counts.

## Artifacts

- \`nonstream_report.txt\` — hey / wrk full output
- \`stream_report.txt\`    — SSE worker aggregate
- \`meminfo_before.txt\`   — pre-test device meminfo
- \`meminfo_after.txt\`    — post-test device meminfo
- \`cpu_during.txt\`       — device CPU snapshot during Phase 1
- \`leak_scan.log\`        — logcat keyword scan
- \`logcat_load_test.log\` — full app logcat for the test window
EOF

log "=== Load test complete ==="
log "Summary: $SUMMARY_MD"
log "Results: $RUN_DIR"
cat "$SUMMARY_MD"
