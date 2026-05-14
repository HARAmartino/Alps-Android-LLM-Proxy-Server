#!/usr/bin/env bash
set -euo pipefail

PACKAGE_NAME="com.llmproxy"
SERIAL=""
PROXY_URL=""
OUTPUT_DIR="./soak-results"
DURATION_HOURS=72
REQUEST_INTERVAL_SEC=300
NETWORK_SWITCH_INTERVAL_SEC=21600
RENEWAL_INTERVAL_SEC=86400
SAMPLE_INTERVAL_SEC=3600
NONSTREAM_PATH="/v1/models"
STREAM_PATH="/v1/chat/completions"
STREAM_MODEL="gpt-4o-mini"
RENEWAL_WORK_NAME="daily_certificate_renewal"
HEAP_GROWTH_THRESHOLD_PCT=10
CA_CERT_PATH=""
ALLOW_INSECURE_TLS=0

usage() {
  cat <<'EOF'
Usage:
  ./soak_test.sh --serial <adb-serial> --proxy-url https://<device-ip>:8443 [options]

Options:
  --serial <value>                        ADB serial (recommended when multiple devices connected)
  --proxy-url <value>                     Proxy base URL, e.g. https://192.168.1.42:8443
  --duration-hours <value>                Test duration in hours (default: 72)
  --request-interval-sec <value>          Request interval in seconds (default: 300)
  --network-switch-interval-sec <value>   Wi-Fi/mobile switch interval (default: 21600)
  --renewal-interval-sec <value>          Renewal trigger interval (default: 86400)
  --sample-interval-sec <value>           meminfo/top sample interval (default: 3600)
  --output-dir <value>                    Output directory (default: ./soak-results)
  --nonstream-path <value>                Non-streaming path (default: /v1/models)
  --stream-path <value>                   Streaming path (default: /v1/chat/completions)
  --stream-model <value>                  Streaming request model field (default: gpt-4o-mini)
  --renewal-work-name <value>             WorkManager unique name (default: daily_certificate_renewal)
  --heap-growth-threshold-pct <value>     Heap-growth alert threshold (default: 10)
  --ca-cert <path>                        PEM certificate for TLS verification
  --insecure                              Use curl -k (skip TLS verification)
  --strict-tls                            Disable -k and require valid TLS chain
  -h, --help                              Show this help
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --serial) SERIAL="${2:-}"; shift 2 ;;
    --proxy-url) PROXY_URL="${2:-}"; shift 2 ;;
    --duration-hours) DURATION_HOURS="${2:-}"; shift 2 ;;
    --request-interval-sec) REQUEST_INTERVAL_SEC="${2:-}"; shift 2 ;;
    --network-switch-interval-sec) NETWORK_SWITCH_INTERVAL_SEC="${2:-}"; shift 2 ;;
    --renewal-interval-sec) RENEWAL_INTERVAL_SEC="${2:-}"; shift 2 ;;
    --sample-interval-sec) SAMPLE_INTERVAL_SEC="${2:-}"; shift 2 ;;
    --output-dir) OUTPUT_DIR="${2:-}"; shift 2 ;;
    --nonstream-path) NONSTREAM_PATH="${2:-}"; shift 2 ;;
    --stream-path) STREAM_PATH="${2:-}"; shift 2 ;;
    --stream-model) STREAM_MODEL="${2:-}"; shift 2 ;;
    --renewal-work-name) RENEWAL_WORK_NAME="${2:-}"; shift 2 ;;
    --heap-growth-threshold-pct) HEAP_GROWTH_THRESHOLD_PCT="${2:-}"; shift 2 ;;
    --ca-cert) CA_CERT_PATH="${2:-}"; shift 2 ;;
    --insecure) ALLOW_INSECURE_TLS=1; shift ;;
    --strict-tls) ALLOW_INSECURE_TLS=0; shift ;;
    -h|--help) usage; exit 0 ;;
    *) echo "Unknown argument: $1" >&2; usage; exit 1 ;;
  esac
done

if [[ -z "$PROXY_URL" ]]; then
  echo "Error: --proxy-url is required." >&2
  usage
  exit 1
fi

if ! command -v adb >/dev/null 2>&1; then
  echo "Error: adb is not installed or not in PATH." >&2
  exit 1
fi

if ! command -v curl >/dev/null 2>&1; then
  echo "Error: curl is not installed or not in PATH." >&2
  exit 1
fi

if [[ ! "$HEAP_GROWTH_THRESHOLD_PCT" =~ ^[0-9]+$ ]]; then
  echo "Error: --heap-growth-threshold-pct must be an integer." >&2
  exit 1
fi

if [[ ! "$STREAM_MODEL" =~ ^[A-Za-z0-9._:-]+$ ]]; then
  echo "Error: --stream-model contains unsupported characters." >&2
  exit 1
fi

ADB=(adb)
if [[ -n "$SERIAL" ]]; then
  ADB+=(-s "$SERIAL")
fi

adb_cmd() {
  "${ADB[@]}" "$@"
}

CURL_TLS_OPTS=()
if [[ -n "$CA_CERT_PATH" ]]; then
  CURL_TLS_OPTS=(--cacert "$CA_CERT_PATH")
elif (( ALLOW_INSECURE_TLS == 1 )); then
  CURL_TLS_OPTS=(-k)
fi

RUN_TS="$(date -u +%Y%m%dT%H%M%SZ)"
DEVICE_ID="${SERIAL:-$(adb_cmd get-serialno | tr -d '\r')}"
RUN_DIR="${OUTPUT_DIR%/}/${DEVICE_ID}/${RUN_TS}"
RAW_MEM_DIR="$RUN_DIR/meminfo_raw"
RAW_CPU_DIR="$RUN_DIR/cpu_raw"
mkdir -p "$RUN_DIR" "$RAW_MEM_DIR" "$RAW_CPU_DIR"

REQUEST_LOG="$RUN_DIR/request_log.csv"
MEM_LOG="$RUN_DIR/meminfo_hourly.csv"
CPU_LOG="$RUN_DIR/cpu_hourly.csv"
RENEWAL_LOG="$RUN_DIR/renewal_events.log"
NETWORK_LOG="$RUN_DIR/network_switch.log"
LEAK_LOG="$RUN_DIR/leak_scan.log"
ALERT_LOG="$RUN_DIR/alerts.log"
SUMMARY_MD="$RUN_DIR/summary.md"
LOGCAT_FILE="$RUN_DIR/logcat_app.log"
STARTED_AT_UTC="$(date -u +%FT%TZ)"

echo "timestamp_utc,request_kind,url,http_code,curl_exit,latency_ms" > "$REQUEST_LOG"
echo "timestamp_utc,total_pss_kb,java_heap_kb,native_heap_kb,pid" > "$MEM_LOG"
echo "timestamp_utc,pid,cpu_percent" > "$CPU_LOG"
echo "timestamp_utc,event,detail" > "$RENEWAL_LOG"
echo "timestamp_utc,event,detail" > "$NETWORK_LOG"
echo "timestamp_utc,keyword,count_total,count_delta" > "$LEAK_LOG"
echo "timestamp_utc,severity,message" > "$ALERT_LOG"

echo "[$(date -u +%FT%TZ)] Starting logcat capture to $LOGCAT_FILE"
adb_cmd logcat -c || true
adb_cmd logcat -v time "${PACKAGE_NAME}:V" '*:S' > "$LOGCAT_FILE" 2>/dev/null &
LOGCAT_PID=$!

cleanup() {
  if ps -p "$LOGCAT_PID" >/dev/null 2>&1; then
    kill "$LOGCAT_PID" >/dev/null 2>&1 || true
  fi
}
trap cleanup EXIT

timestamp() {
  date -u +%FT%TZ
}

pid_of_app() {
  adb_cmd shell pidof "$PACKAGE_NAME" 2>/dev/null | tr -d '\r' | awk 'NR==1 {print $1}'
}

safe_number() {
  local value="${1:-0}"
  if [[ "$value" =~ ^[0-9]+$ ]]; then
    echo "$value"
  else
    echo "0"
  fi
}

extract_total_pss_kb() {
  local file="$1"
  local pss
  pss="$(awk '/TOTAL PSS:/ {print $3; exit} /^TOTAL[[:space:]]+[0-9]+/ {print $2; exit}' "$file" | tr -d '\r' || true)"
  safe_number "$pss"
}

extract_heap_kb() {
  local label="$1"
  local file="$2"
  local value
  value="$(awk -v label="$label" '$0 ~ label":" {for (i=1;i<=NF;i++) if ($i ~ /^[0-9]+$/) {print $i; exit}}' "$file" | tr -d '\r' || true)"
  safe_number "$value"
}

sample_mem_and_cpu() {
  local ts pid mem_raw cpu_raw total_pss java_heap native_heap cpu_pct
  ts="$(timestamp)"
  pid="$(pid_of_app)"
  mem_raw="$RAW_MEM_DIR/meminfo_${ts}.txt"
  cpu_raw="$RAW_CPU_DIR/top_${ts}.txt"

  adb_cmd shell dumpsys meminfo "$PACKAGE_NAME" > "$mem_raw" || true
  total_pss="$(extract_total_pss_kb "$mem_raw")"
  java_heap="$(extract_heap_kb "Java Heap" "$mem_raw")"
  native_heap="$(extract_heap_kb "Native Heap" "$mem_raw")"

  echo "$ts,$total_pss,$java_heap,$native_heap,$pid" >> "$MEM_LOG"

  cpu_pct="0"
  if [[ -n "$pid" ]]; then
    adb_cmd shell top -n 1 -p "$pid" > "$cpu_raw" || true
    cpu_pct="$(awk -v pid="$pid" '$1==pid {for(i=1;i<=NF;i++) if($i ~ /%/) {gsub("%","",$i); print $i; exit}}' "$cpu_raw" | tr -d '\r' || true)"
    cpu_pct="$(safe_number "${cpu_pct:-0}")"
  else
    echo "[$ts] WARN: PID not found; app may not be running."
    echo "$ts,WARN,PID not found; app may not be running." >> "$ALERT_LOG"
  fi

  echo "$ts,${pid:-0},$cpu_pct" >> "$CPU_LOG"
}

check_24h_heap_growth() {
  local rows current baseline min_window ts
  rows="$(($(wc -l < "$MEM_LOG") - 1))"
  if (( rows < 25 )); then
    return
  fi

  ts="$(timestamp)"
  current="$(tail -n 1 "$MEM_LOG" | cut -d',' -f2)"
  baseline="$(tail -n 25 "$MEM_LOG" | head -n 1 | cut -d',' -f2)"
  min_window="$(tail -n 24 "$MEM_LOG" | awk -F',' 'NR==1 {min=$2} NR>1 && $2 < min {min=$2} END {print min+0}')"

  current="$(safe_number "$current")"
  baseline="$(safe_number "$baseline")"
  min_window="$(safe_number "$min_window")"

  if awk -v current="$current" -v baseline="$baseline" -v min_window="$min_window" -v threshold="$HEAP_GROWTH_THRESHOLD_PCT" \
    'BEGIN { exit !(baseline > 0 && current > baseline * (1 + threshold / 100.0) && min_window >= baseline) }'; then
    echo "$ts,ALERT,Heap/PSS grew >${HEAP_GROWTH_THRESHOLD_PCT}% vs 24h baseline without recovery (baseline=${baseline}KB current=${current}KB)." >> "$ALERT_LOG"
  fi
}

REQUEST_COUNT=0
REQUEST_FAILURE_COUNT=0
renewal_attempts=0
leak_total_channel_closed=0
leak_total_resource_leak=0
leak_total_byte_read_channel=0
network_mode="wifi"

send_request() {
  local ts url kind start_s end_s latency_ms http_code curl_exit body
  ts="$(timestamp)"

  if (( REQUEST_COUNT % 2 == 0 )); then
    kind="non_stream"
    url="${PROXY_URL%/}${NONSTREAM_PATH}"
    start_s="$(date +%s)"
    set +e
    http_code="$(curl "${CURL_TLS_OPTS[@]}" --connect-timeout 10 --max-time 60 -o /dev/null -w '%{http_code}' "$url")"
    curl_exit=$?
    set -e
  else
    kind="stream"
    url="${PROXY_URL%/}${STREAM_PATH}"
    body="{\"model\":\"${STREAM_MODEL}\",\"messages\":[{\"role\":\"user\",\"content\":\"health-check\"}],\"stream\":true,\"max_tokens\":8}"
    start_s="$(date +%s)"
    set +e
    http_code="$(curl "${CURL_TLS_OPTS[@]}" --connect-timeout 10 --max-time 120 -N -H 'Content-Type: application/json' -d "$body" -o /dev/null -w '%{http_code}' "$url")"
    curl_exit=$?
    set -e
  fi

  end_s="$(date +%s)"
  latency_ms=$(( (end_s - start_s) * 1000 ))
  echo "$ts,$kind,$url,$http_code,$curl_exit,$latency_ms" >> "$REQUEST_LOG"

  REQUEST_COUNT=$((REQUEST_COUNT + 1))
  if [[ "$curl_exit" != "0" || "$http_code" == "000" ]]; then
    REQUEST_FAILURE_COUNT=$((REQUEST_FAILURE_COUNT + 1))
  fi
}

switch_network() {
  local ts
  ts="$(timestamp)"

  if [[ "$network_mode" == "wifi" ]]; then
    adb_cmd shell svc wifi disable || true
    adb_cmd shell svc data enable || true
    network_mode="mobile"
    echo "$ts,switch,Wi-Fi->Mobile" >> "$NETWORK_LOG"
  else
    adb_cmd shell svc data disable || true
    adb_cmd shell svc wifi enable || true
    network_mode="wifi"
    echo "$ts,switch,Mobile->Wi-Fi" >> "$NETWORK_LOG"
  fi
}

force_renewal_check() {
  local ts jobs_out job_id
  ts="$(timestamp)"
  renewal_attempts=$((renewal_attempts + 1))

  jobs_out="$(adb_cmd shell dumpsys jobscheduler "$PACKAGE_NAME" 2>/dev/null | tr -d '\r' || true)"
  job_id="$(printf '%s\n' "$jobs_out" | awk -v work_name="$RENEWAL_WORK_NAME" '$0 ~ work_name {seen=1} seen && /JOB #/ {if (match($0,/\/[0-9]+/)) {print substr($0,RSTART+1,RLENGTH-1); exit}}')"

  if [[ -n "$job_id" ]]; then
    adb_cmd shell cmd jobscheduler run -f "$PACKAGE_NAME" "$job_id" >/dev/null 2>&1 || true
    echo "$ts,renewal_trigger,jobscheduler job_id=$job_id" >> "$RENEWAL_LOG"
  else
    adb_cmd shell am broadcast -a androidx.work.diagnostics.REQUEST_DIAGNOSTICS --es request run_all "$PACKAGE_NAME" >/dev/null 2>&1 || true
    echo "$ts,renewal_trigger,broadcast REQUEST_DIAGNOSTICS fallback" >> "$RENEWAL_LOG"
  fi
}

scan_leak_keywords() {
  local ts c1 c2 c3 d1 d2 d3
  ts="$(timestamp)"

  c1="$(grep -Eci 'Channel closed unexpectedly' "$LOGCAT_FILE" || true)"
  c2="$(grep -Eci 'Resource leak' "$LOGCAT_FILE" || true)"
  c3="$(grep -Eci 'ByteReadChannel' "$LOGCAT_FILE" || true)"
  c1="$(safe_number "$c1")"
  c2="$(safe_number "$c2")"
  c3="$(safe_number "$c3")"

  d1=$(( c1 - leak_total_channel_closed ))
  d2=$(( c2 - leak_total_resource_leak ))
  d3=$(( c3 - leak_total_byte_read_channel ))

  echo "$ts,Channel closed unexpectedly,$c1,$d1" >> "$LEAK_LOG"
  echo "$ts,Resource leak,$c2,$d2" >> "$LEAK_LOG"
  echo "$ts,ByteReadChannel,$c3,$d3" >> "$LEAK_LOG"

  leak_total_channel_closed="$c1"
  leak_total_resource_leak="$c2"
  leak_total_byte_read_channel="$c3"
}

echo "[$(timestamp)] Run directory: $RUN_DIR"
echo "[$(timestamp)] Duration: ${DURATION_HOURS}h"
echo "[$(timestamp)] Request interval: ${REQUEST_INTERVAL_SEC}s"
echo "[$(timestamp)] Sample interval: ${SAMPLE_INTERVAL_SEC}s"
echo "[$(timestamp)] Network switch interval: ${NETWORK_SWITCH_INTERVAL_SEC}s"
echo "[$(timestamp)] Renewal interval: ${RENEWAL_INTERVAL_SEC}s"
echo "[$(timestamp)] Heap growth alert threshold: ${HEAP_GROWTH_THRESHOLD_PCT}%"
if (( ALLOW_INSECURE_TLS == 1 )) && [[ -z "$CA_CERT_PATH" ]]; then
  warn_ts="$(timestamp)"
  echo "[$warn_ts] WARN: running with insecure TLS (-k). Use --ca-cert or --strict-tls for certificate validation."
  echo "$warn_ts,WARN,curl uses -k (insecure TLS); pass --ca-cert or --strict-tls to enforce verification." >> "$ALERT_LOG"
fi

start_epoch="$(date +%s)"
end_epoch=$(( start_epoch + DURATION_HOURS * 3600 ))
next_request_epoch="$start_epoch"
next_sample_epoch="$start_epoch"
next_switch_epoch=$(( start_epoch + NETWORK_SWITCH_INTERVAL_SEC ))
next_renewal_epoch=$(( start_epoch + RENEWAL_INTERVAL_SEC ))

while (( $(date +%s) < end_epoch )); do
  now="$(date +%s)"

  if (( now >= next_request_epoch )); then
    send_request
    next_request_epoch=$(( next_request_epoch + REQUEST_INTERVAL_SEC ))
  fi

  if (( now >= next_sample_epoch )); then
    sample_mem_and_cpu
    scan_leak_keywords
    check_24h_heap_growth
    next_sample_epoch=$(( next_sample_epoch + SAMPLE_INTERVAL_SEC ))
  fi

  if (( now >= next_switch_epoch )); then
    switch_network
    next_switch_epoch=$(( next_switch_epoch + NETWORK_SWITCH_INTERVAL_SEC ))
  fi

  if (( now >= next_renewal_epoch )); then
    force_renewal_check
    next_renewal_epoch=$(( next_renewal_epoch + RENEWAL_INTERVAL_SEC ))
  fi

  sleep 1
done

last_mem_sample="$(tail -n 1 "$MEM_LOG" | cut -d',' -f2)"
last_cpu_sample="$(tail -n 1 "$CPU_LOG" | cut -d',' -f3)"
alert_count="$(($(wc -l < "$ALERT_LOG") - 1))"
leak_hits_total=$(( leak_total_channel_closed + leak_total_resource_leak ))

cat > "$SUMMARY_MD" <<EOF
# Soak Test Summary

- Device: \`${DEVICE_ID}\`
- Package: \`${PACKAGE_NAME}\`
- Started (UTC): \`${STARTED_AT_UTC}\`
- Ended (UTC): \`$(date -u +%FT%TZ)\`
- Duration (hours): \`${DURATION_HOURS}\`
- Requests sent: \`${REQUEST_COUNT}\`
- Request failures: \`${REQUEST_FAILURE_COUNT}\`
- Renewal trigger attempts: \`${renewal_attempts}\`
- Latest total PSS (KB): \`${last_mem_sample}\`
- Latest CPU sample (%): \`${last_cpu_sample}\`
- Leak keyword hits (Channel closed unexpectedly + Resource leak): \`${leak_hits_total}\`
- Alert count: \`${alert_count}\`

## Artifacts

- request log: \`request_log.csv\`
- meminfo snapshots: \`meminfo_hourly.csv\`, \`meminfo_raw/\`
- CPU snapshots: \`cpu_hourly.csv\`, \`cpu_raw/\`
- renewal events: \`renewal_events.log\`
- network switches: \`network_switch.log\`
- leak scan: \`leak_scan.log\`
- alerts: \`alerts.log\`
- app logcat: \`logcat_app.log\`
EOF

echo "[$(timestamp)] Soak test completed."
echo "[$(timestamp)] Summary: $SUMMARY_MD"
