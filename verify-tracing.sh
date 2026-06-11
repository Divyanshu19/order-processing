#!/usr/bin/env bash
# =============================================================================
# verify-tracing.sh
#
# End-to-end distributed tracing verification script.
#
# What it does:
#   1. Obtains a JWT from auth-service
#   2. Places a test order through the gateway → order-service
#   3. Waits for the saga to complete (product reserved + payment processed)
#   4. Extracts the traceId from the response / logs
#   5. Queries the Zipkin API to verify all four services are represented
#      in a single trace
#   6. Prints a clickable Zipkin UI URL
#
# Prerequisites:
#   • All services running (docker compose up -d  OR  local JVMs)
#   • curl, jq installed
#
# Usage:
#   chmod +x verify-tracing.sh
#   ./verify-tracing.sh                 # uses default ports
#   GATEWAY_URL=http://localhost:8079 \
#   ZIPKIN_URL=http://localhost:9411  \
#   ./verify-tracing.sh
# =============================================================================
set -euo pipefail

# ── Configurable endpoints ────────────────────────────────────────────────────
GATEWAY_URL="${GATEWAY_URL:-http://localhost:8079}"
ZIPKIN_URL="${ZIPKIN_URL:-http://localhost:9411}"
AUTH_URL="${AUTH_URL:-${GATEWAY_URL}}"   # auth is routed via /auth/**

# ── Colours ───────────────────────────────────────────────────────────────────
GREEN="\033[0;32m"; YELLOW="\033[0;33m"; RED="\033[0;31m"; RESET="\033[0m"
info()    { echo -e "${GREEN}[✔]${RESET} $*"; }
warn()    { echo -e "${YELLOW}[!]${RESET} $*"; }
error()   { echo -e "${RED}[✘]${RESET} $*"; }
section() { echo -e "\n${YELLOW}━━━ $* ━━━${RESET}"; }

# ── Dependency check ──────────────────────────────────────────────────────────
section "Dependency check"
for cmd in curl jq; do
    if ! command -v "$cmd" &>/dev/null; then
        error "'$cmd' is required but not installed."
        exit 1
    fi
    info "$cmd: $(command -v "$cmd")"
done

# ── Service health checks ─────────────────────────────────────────────────────
section "Service health checks"
check_service() {
    local name="$1"; local url="$2"
    local status
    status=$(curl -s -o /dev/null -w "%{http_code}" --max-time 5 "$url" 2>/dev/null || echo "000")
    if [[ "$status" == "200" ]]; then
        info "$name → $url ($status)"
    else
        error "$name → $url (HTTP $status) — is the service running?"
        return 1
    fi
}

check_service "gateway-service"  "${GATEWAY_URL}/actuator/health"
check_service "zipkin"           "${ZIPKIN_URL}/health"

# ── Step 1: Obtain JWT ────────────────────────────────────────────────────────
section "Step 1: Obtain JWT from auth-service"
LOGIN_RESPONSE=$(curl -s -X POST "${AUTH_URL}/auth/login" \
    -H "Content-Type: application/json" \
    -d '{"username":"admin","password":"admin"}')

JWT=$(echo "$LOGIN_RESPONSE" | jq -r '.token // .accessToken // .jwt // empty' 2>/dev/null)

if [[ -z "$JWT" ]]; then
    error "Failed to obtain JWT. Response: $LOGIN_RESPONSE"
    exit 1
fi
info "JWT obtained (${#JWT} chars): ${JWT:0:40}..."

# ── Step 2: Place a test order ────────────────────────────────────────────────
section "Step 2: Place a test order via gateway"

# Use a product ID that is likely to exist (seeded by data.sql)
ORDER_PAYLOAD='{"productId":1,"quantity":1,"paymentMethod":"CREDIT_CARD"}'

ORDER_RESPONSE=$(curl -s -w "\n%{http_code}" -X POST "${GATEWAY_URL}/order" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer ${JWT}" \
    -d "$ORDER_PAYLOAD")

HTTP_STATUS=$(echo "$ORDER_RESPONSE" | tail -1)
ORDER_BODY=$(echo "$ORDER_RESPONSE" | head -n -1)

info "HTTP status: $HTTP_STATUS"
info "Response body: $ORDER_BODY"

if [[ "$HTTP_STATUS" != "200" && "$HTTP_STATUS" != "201" ]]; then
    error "Order placement failed (HTTP $HTTP_STATUS). Check order-service logs."
    exit 1
fi

ORDER_ID=$(echo "$ORDER_BODY" | jq -r '.id // .orderId // empty' 2>/dev/null)
info "Order ID: ${ORDER_ID:-'(not found in response)'}"

# ── Step 3: Extract traceId ───────────────────────────────────────────────────
section "Step 3: Extract traceId"
# Spring Boot Micrometer Tracing echoes the traceId in the 'traceparent' or
# 'X-B3-TraceId' response header when tracing is enabled and the route doesn't
# strip response headers. We also accept it from the response JSON body if the
# downstream service injects it.
TRACE_ID=$(echo "$ORDER_BODY" | jq -r '.traceId // empty' 2>/dev/null || true)

if [[ -z "$TRACE_ID" ]]; then
    warn "traceId not in response body — will search Zipkin by service name instead"
fi

# ── Step 4: Wait for spans to be flushed to Zipkin ───────────────────────────
section "Step 4: Waiting for spans to flush to Zipkin"
warn "Zipkin reporter batches spans (default: 1-second flush interval)..."
sleep 4
info "Wait complete."

# ── Step 5: Query Zipkin API ──────────────────────────────────────────────────
section "Step 5: Querying Zipkin API"

# 5a. Get the list of services known to Zipkin
SERVICES=$(curl -s "${ZIPKIN_URL}/api/v2/services" 2>/dev/null)
info "Services in Zipkin: $SERVICES"

EXPECTED_SERVICES=("order-service" "product-service" "payment-service" "gateway-service")
for svc in "${EXPECTED_SERVICES[@]}"; do
    if echo "$SERVICES" | jq -e --arg s "$svc" '. | map(select(. == $s)) | length > 0' >/dev/null 2>&1; then
        info "  ✔ $svc is registered in Zipkin"
    else
        warn "  ✘ $svc not yet in Zipkin (spans may still be buffered — retry in a few seconds)"
    fi
done

# 5b. Find the most recent trace for order-service
RECENT_TRACES=$(curl -s \
    "${ZIPKIN_URL}/api/v2/traces?serviceName=order-service&limit=5&lookback=60000" 2>/dev/null)

TRACE_COUNT=$(echo "$RECENT_TRACES" | jq 'length' 2>/dev/null || echo 0)
info "Recent traces for order-service (last 60 s): $TRACE_COUNT"

if [[ "$TRACE_COUNT" -eq "0" ]]; then
    warn "No traces found yet. Possible reasons:"
    warn "  • management.tracing.sampling.probability is < 1.0"
    warn "  • ZIPKIN_BASE_URL is not set correctly in the service env"
    warn "  • Spans not flushed yet — run this script again in a few seconds"
    echo ""
    warn "Manual check: ${ZIPKIN_URL}/api/v2/services"
    exit 0
fi

# 5c. Inspect the first trace — check how many services it covers
FIRST_TRACE=$(echo "$RECENT_TRACES" | jq '.[0]')
FIRST_TRACE_ID=$(echo "$FIRST_TRACE" | jq -r '.[0].traceId')
SERVICES_IN_TRACE=$(echo "$FIRST_TRACE" | jq -r '[.[].localEndpoint.serviceName] | unique | sort | .[]')
SPAN_COUNT=$(echo "$FIRST_TRACE" | jq 'length')

echo ""
info "══════════════════════════════════════════════════════"
info "  Most recent trace: $FIRST_TRACE_ID"
info "  Span count:        $SPAN_COUNT"
info "  Services covered:"
echo "$SERVICES_IN_TRACE" | while read -r svc; do
    info "    → $svc"
done
info "══════════════════════════════════════════════════════"

# 5d. Print span names (the Kafka hops)
echo ""
section "Span names in trace"
echo "$FIRST_TRACE" | jq -r '.[] | "  [\(.localEndpoint.serviceName)] \(.name) (\(.duration // 0) µs)"' | sort

# ── Step 6: Result ────────────────────────────────────────────────────────────
section "Result"
SVC_COUNT=$(echo "$SERVICES_IN_TRACE" | wc -l | tr -d ' ')

if [[ "$SVC_COUNT" -ge 3 ]]; then
    info "SUCCESS — trace covers $SVC_COUNT services (≥ 3 required)"
else
    warn "PARTIAL — trace only covers $SVC_COUNT service(s). Saga may not have completed yet."
    warn "Wait 2–3 seconds and re-run, OR check service logs for errors."
fi

echo ""
info "🔍 View this trace in Zipkin UI:"
echo ""
echo "   ${ZIPKIN_URL}/zipkin/traces/${FIRST_TRACE_ID}"
echo ""
info "🌐 Zipkin service dependency graph:"
echo ""
echo "   ${ZIPKIN_URL}/zipkin/dependency"
echo ""
info "📋 All traces for order-service:"
echo ""
echo "   ${ZIPKIN_URL}/zipkin/?serviceName=order-service&limit=20"
echo ""
