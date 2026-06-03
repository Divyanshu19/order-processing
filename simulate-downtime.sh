#!/bin/bash
# =============================================================================
# simulate-downtime.sh  — Bash 3.2 compatible (macOS default shell)
#
# Simulates product-service downtime to demonstrate Resilience4j Retry +
# Circuit Breaker behaviour on the GET /api/orders/{id} endpoint.
#
# ┌─────────────────────────────────────────────────────────────────────────┐
# │  Phase 0  Pre-flight checks (tools, Docker infra)                       │
# │  Phase 1  Build JARs + start order-service & product-service            │
# │  Phase 2  Seed: create a product + place an order                       │
# │  Phase 3  Baseline — CB CLOSED, product-service UP                      │
# │  Phase 4  Kill product-service (simulate downtime)                      │
# │  Phase 5  Trip the circuit (fire enough failures to open CB)            │
# │  Phase 6  Observe CB OPEN — instant fallback, no retries                │
# │  Phase 7  Wait for automatic HALF-OPEN transition (10 s)                │
# │  Phase 8  Restart product-service — probe succeeds, CB → CLOSED         │
# │  Phase 9  Summary table                                                 │
# └─────────────────────────────────────────────────────────────────────────┘
#
# Prerequisites (all checked at runtime):
#   • Java 17+, Maven wrapper (./mvnw) present
#   • curl, jq installed
#   • Docker running with:  docker compose up -d
#
# Usage:
#   chmod +x simulate-downtime.sh
#   ./simulate-downtime.sh
#
# Environment overrides (optional):
#   ORDER_PORT=8080   PRODUCT_PORT=8081   SKIP_BUILD=true
# =============================================================================

set -euo pipefail

# ─── Colour helpers ───────────────────────────────────────────────────────────
RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'
CYAN='\033[0;36m'; BOLD='\033[1m'; RESET='\033[0m'

info()    { echo -e "${CYAN}[INFO]${RESET}  $*"; }
success() { echo -e "${GREEN}[PASS]${RESET}  $*"; }
warn()    { echo -e "${YELLOW}[WARN]${RESET}  $*"; }
err()     { echo -e "${RED}[FAIL]${RESET}  $*"; }
banner()  {
    echo -e "\n${BOLD}${CYAN}══════════════════════════════════════════════════${RESET}"
    echo -e "${BOLD}${CYAN}  $*${RESET}"
    echo -e "${BOLD}${CYAN}══════════════════════════════════════════════════${RESET}"
}

# ─── Configuration ────────────────────────────────────────────────────────────
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ORDER_PORT="${ORDER_PORT:-8080}"
PRODUCT_PORT="${PRODUCT_PORT:-8081}"
ORDER_BASE="http://localhost:${ORDER_PORT}/api"
PRODUCT_BASE="http://localhost:${PRODUCT_PORT}/api"
SKIP_BUILD="${SKIP_BUILD:-false}"

# Circuit breaker settings — must match order-service/src/main/resources/application.yml
CB_MIN_CALLS=5          # minimum-number-of-calls
CB_WAIT_OPEN_SECS=10    # wait-duration-in-open-state (seconds)
CALLS_NEEDED_TO_TRIP=$CB_MIN_CALLS

# Temp files
ORDER_PID_FILE="/tmp/order-service.pid"
PRODUCT_PID_FILE="/tmp/product-service.pid"
ORDER_LOG="/tmp/order-service.log"
PRODUCT_LOG="/tmp/product-service.log"

# ─── Result tracking (plain variables — Bash 3.2 has no associative arrays) ──
R_PREFLIGHT="SKIP"
R_SERVICES="SKIP"
R_SEED="SKIP"
R_BASELINE="SKIP"
R_STOPPED="SKIP"
R_CB_OPEN="SKIP"
R_RECOVERY="SKIP"

# ─── Millisecond timestamp (date +%s%N is Linux-only; use python on macOS) ───
now_ms() { python3 -c "import time; print(int(time.time()*1000))"; }

# ─── Utility functions ────────────────────────────────────────────────────────

wait_for_service() {
    local name="$1" url="$2" timeout="${3:-90}"
    info "Waiting for ${name} at ${url} (timeout: ${timeout}s)..."
    local elapsed=0
    until curl -sf --max-time 3 "$url" > /dev/null 2>&1; do
        sleep 2; elapsed=$((elapsed + 2))
        if [ "$elapsed" -ge "$timeout" ]; then
            err "${name} did not start within ${timeout}s — check ${ORDER_LOG} or ${PRODUCT_LOG}"
            return 1
        fi
        printf "."
    done
    echo ""
    success "${name} is UP"
}

stop_service() {
    local name="$1" pid_file="$2"
    if [ -f "$pid_file" ]; then
        local pid
        pid=$(cat "$pid_file")
        if kill -0 "$pid" 2>/dev/null; then
            info "Stopping ${name} (PID ${pid})..."
            kill "$pid" 2>/dev/null || true
            sleep 2
            kill -9 "$pid" 2>/dev/null || true
            success "${name} stopped"
        else
            warn "${name} PID ${pid} was not running"
        fi
        rm -f "$pid_file"
    else
        warn "No PID file for ${name} at ${pid_file}"
    fi
}

show_response() {
    local label="$1" json="$2"
    echo -e "\n${BOLD}  ▶ ${label}${RESET}"
    echo "$json" | jq . 2>/dev/null || echo "$json"
}

json_field() { echo "$1" | jq -r "$2" 2>/dev/null || echo "N/A"; }

cleanup() {
    echo ""
    info "Cleanup — stopping services..."
    stop_service "order-service"   "$ORDER_PID_FILE"   2>/dev/null || true
    stop_service "product-service" "$PRODUCT_PID_FILE" 2>/dev/null || true
}
trap cleanup EXIT

# ═════════════════════════════════════════════════════════════════════════════
# Phase 0 — Pre-flight
# ═════════════════════════════════════════════════════════════════════════════
banner "Phase 0 — Pre-flight Checks"

for tool in curl jq java python3; do
    if command -v "$tool" > /dev/null 2>&1; then
        success "$tool: $(command -v "$tool")"
    else
        err "$tool is not installed. Please install it and retry."
        exit 1
    fi
done

JAVA_VER=$(java -version 2>&1 | awk -F '"' '/version/ {print $2}' | cut -d. -f1)
if [ "$JAVA_VER" -lt 17 ] 2>/dev/null; then
    err "Java 17+ required. Found: Java ${JAVA_VER}"
    exit 1
fi
success "Java ${JAVA_VER} — OK"

if [ ! -f "${SCRIPT_DIR}/mvnw" ]; then
    err "Maven wrapper (mvnw) not found in ${SCRIPT_DIR}"
    exit 1
fi
success "Maven wrapper found"

# Docker infra check
ALL_INFRA_UP=true
for container in postgres-order postgres-product kafka-kraft; do
    if docker ps --format '{{.Names}}' 2>/dev/null | grep -q "^${container}$"; then
        success "Docker container '${container}' running"
    else
        warn "Docker container '${container}' NOT found"
        ALL_INFRA_UP=false
    fi
done

if [ "$ALL_INFRA_UP" = "false" ]; then
    warn "Some infrastructure containers are missing."
    printf "  Start infra now? [y/N] "
    read -r reply
    if echo "$reply" | grep -qi "^y"; then
        info "Starting Docker infrastructure..."
        cd "$SCRIPT_DIR" && docker compose up -d
        info "Waiting 25s for containers to be healthy..."
        sleep 25
    else
        err "Cannot proceed without infrastructure containers."
        exit 1
    fi
fi

R_PREFLIGHT="PASS"

# ═════════════════════════════════════════════════════════════════════════════
# Phase 1 — Build JARs & Start Services
# ═════════════════════════════════════════════════════════════════════════════
banner "Phase 1 — Build JARs & Start Services"

ORDER_JAR="${SCRIPT_DIR}/order-service/target/order-service-0.0.1-SNAPSHOT.jar"
PRODUCT_JAR="${SCRIPT_DIR}/product-service/target/product-service-0.0.1-SNAPSHOT.jar"

if [ "${SKIP_BUILD}" = "true" ] && [ -f "$ORDER_JAR" ] && [ -f "$PRODUCT_JAR" ]; then
    warn "SKIP_BUILD=true — using existing JARs"
else
    info "Building order-service and product-service JARs (this takes ~30s)..."
    cd "$SCRIPT_DIR"
    ./mvnw package -pl order-service,product-service -am -DskipTests --no-transfer-progress -q
    success "Build complete"
fi

# Clear any leftover processes on the target ports
# (lsof returns exit code 1 when nothing is listening — guard with || true)
for PORT in "$ORDER_PORT" "$PRODUCT_PORT"; do
    EXISTING_PID=$(lsof -nP -iTCP:"${PORT}" -sTCP:LISTEN 2>/dev/null | awk 'NR>1 {print $2}' | head -1 || true)
    if [ -n "$EXISTING_PID" ]; then
        warn "Port ${PORT} in use by PID ${EXISTING_PID} — killing it"
        kill -9 "$EXISTING_PID" 2>/dev/null || true
        sleep 1
    fi
done

# Start product-service
info "Starting product-service on port ${PRODUCT_PORT}..."
java -jar "$PRODUCT_JAR" \
    --server.port="${PRODUCT_PORT}" \
    --server.servlet.context-path=/api \
    > "$PRODUCT_LOG" 2>&1 &
echo $! > "$PRODUCT_PID_FILE"
success "product-service started (PID $(cat "$PRODUCT_PID_FILE")) — logs: ${PRODUCT_LOG}"

# Start order-service
info "Starting order-service on port ${ORDER_PORT}..."
java -jar "$ORDER_JAR" \
    --server.port="${ORDER_PORT}" \
    --server.servlet.context-path=/api \
    > "$ORDER_LOG" 2>&1 &
echo $! > "$ORDER_PID_FILE"
success "order-service started (PID $(cat "$ORDER_PID_FILE")) — logs: ${ORDER_LOG}"

wait_for_service "product-service" "${PRODUCT_BASE}/actuator/health" 90
wait_for_service "order-service"   "${ORDER_BASE}/actuator/health"   90

R_SERVICES="PASS"

# ═════════════════════════════════════════════════════════════════════════════
# Phase 2 — Seed Data
# ═════════════════════════════════════════════════════════════════════════════
banner "Phase 2 — Seed Data"

info "Creating a product in product-service..."
# Timestamp-based SKU avoids duplicate-key errors on repeated runs
DEMO_SKU="CB-DEMO-$(date +%s)"
PRODUCT_RESPONSE=$(curl -sf -X POST "${PRODUCT_BASE}/products" \
    -H "Content-Type: application/json" \
    -d "{
          \"sku\":           \"${DEMO_SKU}\",
          \"name\":          \"Circuit Breaker Demo Widget\",
          \"price\":         49.99,
          \"stockQuantity\": 100
        }") || {
    err "Failed to create product. Is product-service healthy?"
    exit 1
}
PRODUCT_ID=$(json_field "$PRODUCT_RESPONSE" ".id")
show_response "Created product" "$PRODUCT_RESPONSE"
success "Product created: id=${PRODUCT_ID}"

info "Placing an order (userId=1, productId=${PRODUCT_ID}, qty=1)..."
ORDER_RESPONSE=$(curl -sf -X POST "${ORDER_BASE}/orders" \
    -H "Content-Type: application/json" \
    -d "{
          \"userId\":        1,
          \"productId\":     ${PRODUCT_ID},
          \"quantity\":      1,
          \"paymentMethod\": \"CREDIT_CARD\"
        }") || {
    err "Failed to place order."
    exit 1
}
ORDER_ID=$(json_field "$ORDER_RESPONSE" ".id")
show_response "Placed order" "$ORDER_RESPONSE"
success "Order placed: id=${ORDER_ID}"

R_SEED="PASS"

# ═════════════════════════════════════════════════════════════════════════════
# Phase 3 — Baseline: CB CLOSED (product-service UP)
# ═════════════════════════════════════════════════════════════════════════════
banner "Phase 3 — Baseline: Circuit CLOSED (product-service UP)"

info "GET /api/orders/${ORDER_ID} — expecting full enriched response..."

BASELINE_RESP=$(curl -sf "${ORDER_BASE}/orders/${ORDER_ID}") || {
    err "Baseline request failed."
    R_BASELINE="FAIL"
    BASELINE_RESP="{}"
}

show_response "Enriched order response (CB CLOSED)" "$BASELINE_RESP"

PROD_AVAIL=$(json_field "$BASELINE_RESP" ".productServiceAvailable")
PROD_NAME=$(json_field  "$BASELINE_RESP" ".product.name")

if [ "$PROD_AVAIL" = "true" ] && [ "$PROD_NAME" != "null" ] && [ "$PROD_NAME" != "N/A" ]; then
    success "productServiceAvailable=true, product.name='${PROD_NAME}' — CB CLOSED ✔"
    R_BASELINE="PASS"
else
    err "Baseline failed: productServiceAvailable=${PROD_AVAIL}, product.name=${PROD_NAME}"
    R_BASELINE="FAIL"
fi

info "Circuit breaker state BEFORE downtime:"
curl -sf "${ORDER_BASE}/actuator/health" \
    | jq '.components.circuitBreakers // "not exposed"' 2>/dev/null || true

# ═════════════════════════════════════════════════════════════════════════════
# Phase 4 — Kill product-service (simulate downtime)
# ═════════════════════════════════════════════════════════════════════════════
banner "Phase 4 — Simulate Downtime: Killing product-service"

stop_service "product-service" "$PRODUCT_PID_FILE"
sleep 2

if curl -sf --max-time 2 "${PRODUCT_BASE}/actuator/health" > /dev/null 2>&1; then
    warn "product-service still responding — waiting..."
    sleep 3
fi
success "product-service is DOWN — port ${PRODUCT_PORT} no longer responding"
R_STOPPED="PASS"

# ═════════════════════════════════════════════════════════════════════════════
# Phase 5 — Trip the circuit
# ═════════════════════════════════════════════════════════════════════════════
banner "Phase 5 — Trip the Circuit (${CALLS_NEEDED_TO_TRIP} CB-counted failures needed)"

info "CB config:  minimum-number-of-calls=${CB_MIN_CALLS},  failure-rate-threshold=50%"
info "Retry config: max-attempts=3 (500ms → 1000ms → 2000ms exponential back-off)"
info "Firing ${CALLS_NEEDED_TO_TRIP} requests while product-service is DOWN..."
echo ""

FALLBACK_COUNT=0
i=1
while [ "$i" -le "$CALLS_NEEDED_TO_TRIP" ]; do
    echo -e "${YELLOW}  ── Request ${i} / ${CALLS_NEEDED_TO_TRIP} ──${RESET}"
    T_START=$(now_ms)

    RESP=$(curl -sf --max-time 30 "${ORDER_BASE}/orders/${ORDER_ID}" 2>/dev/null) || RESP="{}"

    T_END=$(now_ms)
    ELAPSED_MS=$(( T_END - T_START ))

    AVAIL=$(json_field "$RESP" ".productServiceAvailable")
    PROD=$(json_field  "$RESP" ".product")

    if [ "$AVAIL" = "false" ] && [ "$PROD" = "null" ]; then
        echo -e "  ${GREEN}→ Fallback: productServiceAvailable=false, product=null${RESET}"
        echo -e "  ${CYAN}→ Elapsed: ${ELAPSED_MS}ms${RESET}  (includes 3 retry attempts + back-offs)"
        FALLBACK_COUNT=$((FALLBACK_COUNT + 1))
    else
        echo -e "  ${RED}→ Unexpected response: productServiceAvailable=${AVAIL}${RESET}"
    fi
    echo ""
    i=$((i + 1))
done

success "${FALLBACK_COUNT} / ${CALLS_NEEDED_TO_TRIP} calls returned fallback (productServiceAvailable=false)"

# ═════════════════════════════════════════════════════════════════════════════
# Phase 6 — Observe CB OPEN (instant fallback, zero retries)
# ═════════════════════════════════════════════════════════════════════════════
banner "Phase 6 — Observe Circuit OPEN (instant fallback)"

info "Sending one more request — CB should now be OPEN and short-circuit instantly..."
echo ""

T_START=$(now_ms)
OPEN_RESP=$(curl -sf --max-time 10 "${ORDER_BASE}/orders/${ORDER_ID}" 2>/dev/null) || OPEN_RESP="{}"
T_END=$(now_ms)
OPEN_ELAPSED=$(( T_END - T_START ))

show_response "Response while circuit is OPEN" "$OPEN_RESP"

OPEN_AVAIL=$(json_field "$OPEN_RESP" ".productServiceAvailable")
OPEN_PROD=$(json_field  "$OPEN_RESP" ".product")

echo ""
info "Response time: ${OPEN_ELAPSED}ms"

if [ "$OPEN_AVAIL" = "false" ] && [ "$OPEN_PROD" = "null" ]; then
    if [ "$OPEN_ELAPSED" -lt 500 ]; then
        success "Circuit OPEN confirmed — instant fallback in ${OPEN_ELAPSED}ms (no retry delays) ✔"
    else
        warn "Fallback received in ${OPEN_ELAPSED}ms — CB may still be CLOSED (fire more calls if needed)"
    fi
    R_CB_OPEN="PASS"
else
    warn "CB may not be OPEN yet. productServiceAvailable=${OPEN_AVAIL}"
    R_CB_OPEN="FAIL"
fi

# ── Actuator: circuit breaker state ──────────────────────────────────────────
echo ""
info "Actuator — circuit breaker health:"
curl -sf "${ORDER_BASE}/actuator/health" \
    | jq '.components.circuitBreakers.details.productService // "not found"' 2>/dev/null \
    || warn "circuitBreakers not in actuator health (check management.health.circuitbreakers.enabled)"

echo ""
info "Actuator — last 5 circuit breaker events:"
curl -sf "${ORDER_BASE}/actuator/circuitbreakerevents/productService" 2>/dev/null \
    | jq '[.circuitBreakerEvents[-5:] | .[] | {event: .type, transition: .stateTransition, time: .creationTime}]' \
    2>/dev/null \
    || warn "circuitbreakerevents endpoint not reachable"

echo ""
info "Actuator — last 9 retry events (3 per call × 3 calls):"
curl -sf "${ORDER_BASE}/actuator/retryevents/productService" 2>/dev/null \
    | jq '[.retryEvents[-9:] | .[] | {event: .type, attempts: .numberOfRetryAttempts, time: .creationTime}]' \
    2>/dev/null \
    || warn "retryevents endpoint not reachable"

# ═════════════════════════════════════════════════════════════════════════════
# Phase 7 — Wait for automatic HALF-OPEN transition
# ═════════════════════════════════════════════════════════════════════════════
banner "Phase 7 — Wait ${CB_WAIT_OPEN_SECS}s for CB Auto-Transition to HALF-OPEN"

info "automatic-transition-from-open-to-half-open-enabled=true in application.yml"
info "Sleeping ${CB_WAIT_OPEN_SECS} seconds (wait-duration-in-open-state)..."
echo ""

j=1
while [ "$j" -le "$CB_WAIT_OPEN_SECS" ]; do
    printf "\r  ${CYAN}Waiting: %2d / %d s${RESET}" "$j" "$CB_WAIT_OPEN_SECS"
    sleep 1
    j=$((j + 1))
done
echo ""
echo ""
success "Wait complete — CB should now be in HALF-OPEN state"

info "Actuator — state after wait:"
curl -sf "${ORDER_BASE}/actuator/health" \
    | jq '.components.circuitBreakers.details.productService // "not found"' 2>/dev/null \
    || echo "N/A"

# ═════════════════════════════════════════════════════════════════════════════
# Phase 8 — Restart product-service → probe call → CB CLOSED
# ═════════════════════════════════════════════════════════════════════════════
banner "Phase 8 — Restart product-service → Probe Call → CB Back to CLOSED"

info "Starting product-service again on port ${PRODUCT_PORT}..."
java -jar "$PRODUCT_JAR" \
    --server.port="${PRODUCT_PORT}" \
    --server.servlet.context-path=/api \
    > "$PRODUCT_LOG" 2>&1 &
echo $! > "$PRODUCT_PID_FILE"
success "product-service restarted (PID $(cat "$PRODUCT_PID_FILE"))"

wait_for_service "product-service" "${PRODUCT_BASE}/actuator/health" 90

info "Sending probe call (CB is HALF-OPEN — one call allowed through)..."
sleep 2

RECOVER_RESP=$(curl -sf --max-time 15 "${ORDER_BASE}/orders/${ORDER_ID}" 2>/dev/null) || RECOVER_RESP="{}"
show_response "Recovery probe response" "$RECOVER_RESP"

RECOVER_AVAIL=$(json_field "$RECOVER_RESP" ".productServiceAvailable")
RECOVER_NAME=$(json_field  "$RECOVER_RESP" ".product.name")

if [ "$RECOVER_AVAIL" = "true" ] && [ "$RECOVER_NAME" != "null" ] && [ "$RECOVER_NAME" != "N/A" ]; then
    success "CB → CLOSED: productServiceAvailable=true, product.name='${RECOVER_NAME}' ✔"
    R_RECOVERY="PASS"
else
    # permittedCallsInHalfOpenState=3 — send 2 more probe calls
    warn "Still transitioning — sending 2 more probe calls (permittedCallsInHalfOpenState=3)..."
    k=1
    while [ "$k" -le 2 ]; do
        sleep 1
        PROBE=$(curl -sf --max-time 10 "${ORDER_BASE}/orders/${ORDER_ID}" 2>/dev/null) || PROBE="{}"
        PROBE_AVAIL=$(json_field "$PROBE" ".productServiceAvailable")
        info "  probe ${k}: productServiceAvailable=${PROBE_AVAIL}"
        k=$((k + 1))
    done

    FINAL_RESP=$(curl -sf --max-time 10 "${ORDER_BASE}/orders/${ORDER_ID}" 2>/dev/null) || FINAL_RESP="{}"
    FINAL_AVAIL=$(json_field "$FINAL_RESP" ".productServiceAvailable")

    if [ "$FINAL_AVAIL" = "true" ]; then
        success "CB → CLOSED after probe calls ✔"
        R_RECOVERY="PASS"
    else
        err "CB did not recover. productServiceAvailable=${FINAL_AVAIL}"
        R_RECOVERY="FAIL"
    fi
fi

info "Final actuator — circuit breaker state:"
curl -sf "${ORDER_BASE}/actuator/health" \
    | jq '.components.circuitBreakers.details.productService // "not found"' 2>/dev/null \
    || echo "N/A"

echo ""
info "Final actuator — full circuit breaker event history:"
curl -sf "${ORDER_BASE}/actuator/circuitbreakerevents/productService" 2>/dev/null \
    | jq '[.circuitBreakerEvents[] | {event: .type, transition: .stateTransition, time: .creationTime}]' \
    2>/dev/null || echo "N/A"

# ═════════════════════════════════════════════════════════════════════════════
# Phase 9 — Summary
# ═════════════════════════════════════════════════════════════════════════════
banner "Phase 9 — Simulation Summary"

echo ""
printf "  %-35s  %s\n" "Check" "Result"
printf "  %-35s  %s\n" "─────────────────────────────────" "──────"

print_result() {
    local label="$1" result="$2"
    case "$result" in
        PASS) printf "  %-35s  ${GREEN}✔ PASS${RESET}\n" "$label" ;;
        FAIL) printf "  %-35s  ${RED}✘ FAIL${RESET}\n"   "$label" ;;
        SKIP) printf "  %-35s  ${YELLOW}– SKIP${RESET}\n" "$label" ;;
    esac
}

print_result "Pre-flight checks"           "$R_PREFLIGHT"
print_result "Services started"            "$R_SERVICES"
print_result "Seed data"                   "$R_SEED"
print_result "Baseline (CB CLOSED)"        "$R_BASELINE"
print_result "Product-service stopped"     "$R_STOPPED"
print_result "CB OPEN fallback observed"   "$R_CB_OPEN"
print_result "CB CLOSED after recovery"    "$R_RECOVERY"

echo ""
FAIL_COUNT=0
for r in "$R_PREFLIGHT" "$R_SERVICES" "$R_SEED" "$R_BASELINE" "$R_STOPPED" "$R_CB_OPEN" "$R_RECOVERY"; do
    [ "$r" = "FAIL" ] && FAIL_COUNT=$((FAIL_COUNT + 1))
done

if [ "$FAIL_COUNT" -eq 0 ]; then
    echo -e "  ${GREEN}${BOLD}All checks passed! Resilience4j Retry + Circuit Breaker verified.${RESET}"
else
    echo -e "  ${RED}${BOLD}${FAIL_COUNT} check(s) failed — check logs below for details.${RESET}"
fi

echo ""
echo -e "${BOLD}  Useful follow-up commands:${RESET}"
echo ""
echo -e "  ${CYAN}# Live circuit breaker state${RESET}"
echo    "  curl -s ${ORDER_BASE}/actuator/health | jq '.components.circuitBreakers'"
echo ""
echo -e "  ${CYAN}# All CB state transitions${RESET}"
echo    "  curl -s ${ORDER_BASE}/actuator/circuitbreakerevents/productService | jq '[.circuitBreakerEvents[] | {event:.type, transition:.stateTransition, time:.creationTime}]'"
echo ""
echo -e "  ${CYAN}# All retry events (shows 3 attempts per failed call)${RESET}"
echo    "  curl -s ${ORDER_BASE}/actuator/retryevents/productService | jq '[.retryEvents[] | {event:.type, attempts:.numberOfRetryAttempts, time:.creationTime}]'"
echo ""
echo -e "  ${CYAN}# Tail order-service logs (CB + Retry lines only)${RESET}"
echo    "  tail -f ${ORDER_LOG} | grep -E 'CB\+Retry|Retry|Fallback|OPEN|CLOSED|HALF'"
echo ""
echo -e "  ${CYAN}# Stop services when done${RESET}"
echo    "  kill \$(cat /tmp/order-service.pid) \$(cat /tmp/product-service.pid) 2>/dev/null"
echo    "  docker compose -f ${SCRIPT_DIR}/docker-compose.yml down"
echo ""
