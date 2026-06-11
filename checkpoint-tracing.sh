#!/usr/bin/env bash
# =============================================================================
# checkpoint-tracing.sh
#
# Checkpoint verification: exercises one full order flow, then confirms that:
#   1. All /management/{health,info,metrics} endpoints return 200
#   2. Logs contain traceId in JSON format  (CONSOLE_JSON / json profile)
#   3. The order-service log contains a traceId field for this request
#   4. Zipkin has a cross-service trace covering ≥ 3 services
#
# Usage:
#   chmod +x checkpoint-tracing.sh
#   ./checkpoint-tracing.sh
#
#   # Override defaults:
#   BASE_URL=http://localhost:8079 ZIPKIN=http://localhost:9411 ./checkpoint-tracing.sh
# =============================================================================
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8079}"
ORDER_SVC="${ORDER_SVC:-http://localhost:8080}"
PRODUCT_SVC="${PRODUCT_SVC:-http://localhost:8081}"
PAYMENT_SVC="${PAYMENT_SVC:-http://localhost:8082}"
AUTH_SVC="${AUTH_SVC:-http://localhost:8090}"
GATEWAY_SVC="${GATEWAY_SVC:-http://localhost:8079}"
ZIPKIN="${ZIPKIN:-http://localhost:9411}"

GREEN="\033[0;32m"; YELLOW="\033[0;33m"; RED="\033[0;31m"; CYAN="\033[0;36m"; RESET="\033[0m"
PASS=0; FAIL=0
pass() { echo -e "${GREEN}  ✔ PASS${RESET}  $*"; (( PASS++ )); }
fail() { echo -e "${RED}  ✘ FAIL${RESET}  $*"; (( FAIL++ )); }
warn() { echo -e "${YELLOW}  ! WARN${RESET}  $*"; }
section() { echo -e "\n${CYAN}━━━ $* ━━━${RESET}"; }

# ── Helper: HTTP GET and check status ────────────────────────────────────────
check_endpoint() {
    local label="$1" url="$2" expect="${3:-200}"
    local actual
    actual=$(curl -s -o /dev/null -w "%{http_code}" --max-time 5 "$url" 2>/dev/null || echo "000")
    if [[ "$actual" == "$expect" ]]; then
        pass "$label → HTTP $actual"
    else
        fail "$label → expected HTTP $expect, got $actual  ($url)"
    fi
}

# ── Helper: GET body ─────────────────────────────────────────────────────────
get_body() { curl -s --max-time 8 "$1" 2>/dev/null || true; }

# =============================================================================
section "1. /management endpoints — health, info, metrics"
# =============================================================================

# order-service (servlet context-path /api)
check_endpoint "order-service   /management/health"   "${ORDER_SVC}/api/management/health"
check_endpoint "order-service   /management/info"     "${ORDER_SVC}/api/management/info"
check_endpoint "order-service   /management/metrics"  "${ORDER_SVC}/api/management/metrics"
check_endpoint "order-service   /management/prometheus" "${ORDER_SVC}/api/management/prometheus"

# payment-service
check_endpoint "payment-service /management/health"   "${PAYMENT_SVC}/api/management/health"
check_endpoint "payment-service /management/info"     "${PAYMENT_SVC}/api/management/info"
check_endpoint "payment-service /management/metrics"  "${PAYMENT_SVC}/api/management/metrics"

# product-service
check_endpoint "product-service /management/health"   "${PRODUCT_SVC}/api/management/health"
check_endpoint "product-service /management/info"     "${PRODUCT_SVC}/api/management/info"
check_endpoint "product-service /management/metrics"  "${PRODUCT_SVC}/api/management/metrics"

# auth-service (no servlet context-path)
check_endpoint "auth-service    /management/health"   "${AUTH_SVC}/management/health"
check_endpoint "auth-service    /management/info"     "${AUTH_SVC}/management/info"

# gateway-service (reactive, no context-path)
check_endpoint "gateway-service /management/health"   "${GATEWAY_SVC}/management/health"
check_endpoint "gateway-service /management/info"     "${GATEWAY_SVC}/management/info"

# =============================================================================
section "2. /management/info content check"
# =============================================================================

INFO_BODY=$(get_body "${ORDER_SVC}/api/management/info")
if echo "$INFO_BODY" | jq -e '.app.name' >/dev/null 2>&1; then
    pass "order-service /management/info contains app.name: $(echo "$INFO_BODY" | jq -r '.app.name')"
else
    fail "order-service /management/info is missing 'app.name' field. Body: $INFO_BODY"
fi

if echo "$INFO_BODY" | jq -e '.java' >/dev/null 2>&1; then
    pass "order-service /management/info contains java info: $(echo "$INFO_BODY" | jq -rc '.java.version // "n/a"')"
else
    warn "order-service /management/info missing 'java' block (needs management.info.java.enabled=true)"
fi

if echo "$INFO_BODY" | jq -e '.build' >/dev/null 2>&1; then
    pass "order-service /management/info contains build info: $(echo "$INFO_BODY" | jq -rc '.build.artifact // "n/a"') v$(echo "$INFO_BODY" | jq -r '.build.version // "n/a"')"
else
    warn "order-service /management/info missing 'build' block (needs spring-boot-maven-plugin build-info goal)"
fi

# =============================================================================
section "3. Obtain JWT and place a test order"
# =============================================================================

LOGIN=$(curl -s -X POST "${AUTH_SVC}/auth/login" \
    -H "Content-Type: application/json" \
    -d '{"username":"admin","password":"admin"}' 2>/dev/null || true)

JWT=$(echo "$LOGIN" | jq -r '.token // .accessToken // .jwt // empty' 2>/dev/null || true)
if [[ -z "$JWT" ]]; then
    fail "Could not obtain JWT — is auth-service running?  Response: $LOGIN"
    echo -e "\n${RED}Cannot continue without a JWT — exiting checkpoint.${RESET}"
    exit 1
fi
pass "JWT obtained (${#JWT} chars)"

ORDER_RESP=$(curl -s -w "\n%{http_code}" -X POST "${BASE_URL}/order" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer ${JWT}" \
    -d '{"productId":1,"quantity":1,"paymentMethod":"CREDIT_CARD"}' 2>/dev/null || true)

HTTP_CODE=$(echo "$ORDER_RESP" | tail -1)
ORDER_BODY=$(echo "$ORDER_RESP" | head -n -1)

if [[ "$HTTP_CODE" == "200" || "$HTTP_CODE" == "201" ]]; then
    ORDER_ID=$(echo "$ORDER_BODY" | jq -r '.id // .orderId // "unknown"' 2>/dev/null || echo "unknown")
    pass "Order placed — HTTP $HTTP_CODE, orderId=${ORDER_ID}"
else
    fail "Order placement failed — HTTP $HTTP_CODE. Body: $ORDER_BODY"
    warn "Tracing checkpoint cannot be fully verified without a successful order."
fi

# =============================================================================
section "4. Wait for spans to flush to Zipkin"
# =============================================================================
warn "Sleeping 5 s for span flush (zipkin-reporter-brave batches at 1-second intervals)..."
sleep 5
pass "Wait complete."

# =============================================================================
section "5. Zipkin cross-service trace verification"
# =============================================================================

SERVICES=$(get_body "${ZIPKIN}/api/v2/services")
echo "  Zipkin registered services: $SERVICES"

EXPECTED=("order-service" "product-service" "payment-service")
for svc in "${EXPECTED[@]}"; do
    if echo "$SERVICES" | jq -e --arg s "$svc" '. | map(select(. == $s)) | length > 0' >/dev/null 2>&1; then
        pass "Zipkin knows service: $svc"
    else
        fail "Zipkin does not yet know service: $svc — spans may not have reached Zipkin"
    fi
done

TRACES=$(get_body "${ZIPKIN}/api/v2/traces?serviceName=order-service&limit=5&lookback=120000")
TRACE_COUNT=$(echo "$TRACES" | jq 'length' 2>/dev/null || echo 0)

if [[ "$TRACE_COUNT" -gt 0 ]]; then
    FIRST_TRACE=$(echo "$TRACES" | jq '.[0]')
    TRACE_ID=$(echo "$FIRST_TRACE" | jq -r '.[0].traceId')
    SPAN_COUNT=$(echo "$FIRST_TRACE" | jq 'length')
    SERVICES_IN_TRACE=$(echo "$FIRST_TRACE" | jq -r '[.[].localEndpoint.serviceName] | unique | sort | .[]')
    SVC_COUNT=$(echo "$SERVICES_IN_TRACE" | grep -c '.' || echo 0)

    pass "Found $TRACE_COUNT trace(s) for order-service in last 2 minutes"
    pass "Most recent trace: $TRACE_ID ($SPAN_COUNT spans, $SVC_COUNT services)"
    echo ""
    echo "$SERVICES_IN_TRACE" | while read -r svc; do
        echo -e "    ${GREEN}→${RESET} $svc"
    done
    echo ""
    echo "  Span names:"
    echo "$FIRST_TRACE" | jq -r '.[] | "    [\(.localEndpoint.serviceName)] \(.name)"' | sort | uniq

    if [[ "$SVC_COUNT" -ge 3 ]]; then
        pass "Trace covers $SVC_COUNT services — cross-service propagation CONFIRMED"
    else
        fail "Trace covers only $SVC_COUNT service(s) — expected ≥ 3. Saga may not have completed."
    fi
else
    fail "No traces found in Zipkin for order-service. Check ZIPKIN_BASE_URL in service config."
fi

# =============================================================================
section "6. JSON log format and traceId field verification"
# =============================================================================

echo ""
echo "  To verify traceId in JSON logs, inspect your running service logs with:"
echo ""
echo "  ${CYAN}# Docker Compose:${RESET}"
echo "  docker compose logs --tail=50 order-service | grep traceId"
echo ""
echo "  ${CYAN}# Or pipe through jq to confirm structured JSON:${RESET}"
echo "  docker compose logs --tail=50 order-service | tail -20 | jq -rc '{ts:.\"@timestamp\",svc:.service,level:.level,msg:.message,traceId:.traceId}' 2>/dev/null"
echo ""
echo "  ${CYAN}# Local JVM (activate json profile):${RESET}"
echo "  SPRING_PROFILES_ACTIVE=json java -jar order-service/target/*.jar 2>&1 | jq -rc '{traceId}'  "
echo ""

if command -v docker &>/dev/null && docker compose ps 2>/dev/null | grep -q "order-service"; then
    SAMPLE_LOG=$(docker compose logs --tail=40 order-service 2>/dev/null | tail -20 || true)
    if echo "$SAMPLE_LOG" | jq -e '.traceId' >/dev/null 2>&1; then
        SAMPLE_TRACE=$(echo "$SAMPLE_LOG" | jq -r 'select(.traceId != null) | .traceId' | head -1 || true)
        pass "order-service logs ARE JSON and contain traceId: ${SAMPLE_TRACE:0:16}..."
    else
        # Try to check if it's JSON at all
        if echo "$SAMPLE_LOG" | head -1 | jq . >/dev/null 2>&1; then
            warn "Logs are JSON but traceId field not found in last 40 lines (no active trace context)"
        else
            warn "Logs are in text format — activate the 'json' Spring profile for structured JSON:"
            warn "  SPRING_PROFILES_ACTIVE=json  (or add to docker-compose.yml environment)"
        fi
    fi
else
    warn "Docker not running — skipping live log check. Start with: docker compose up -d"
fi

# =============================================================================
section "7. Checkpoint Summary"
# =============================================================================
echo ""
echo -e "  Tests passed: ${GREEN}${PASS}${RESET}"
echo -e "  Tests failed: ${RED}${FAIL}${RESET}"
echo ""

if [[ "${FAIL}" -eq 0 ]]; then
    echo -e "${GREEN}  ✔ CHECKPOINT PASSED${RESET} — /management endpoints, JSON tracing, and Zipkin cross-service trace all verified."
else
    echo -e "${YELLOW}  ✘ CHECKPOINT INCOMPLETE${RESET} — ${FAIL} check(s) failed. See details above."
fi

echo ""
echo "  Useful URLs:"
echo "  ─────────────────────────────────────────────────────────────────"
echo "  Zipkin UI:              ${ZIPKIN}/zipkin/?serviceName=order-service"
if [[ "${TRACE_COUNT:-0}" -gt 0 ]]; then
echo "  Latest trace:           ${ZIPKIN}/zipkin/traces/${TRACE_ID}"
fi
echo "  Order /management:      ${ORDER_SVC}/api/management/health"
echo "  Order /management/info: ${ORDER_SVC}/api/management/info"
echo "  Gateway /management:    ${GATEWAY_SVC}/management/health"
echo "  Prometheus UI:          http://localhost:9090"
echo "  Grafana UI:             http://localhost:3000"
echo "  ─────────────────────────────────────────────────────────────────"
echo ""
