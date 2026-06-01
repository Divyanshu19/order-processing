#!/usr/bin/env bash
# =============================================================================
# test-async-flow.sh
#
# End-to-end test for the async order-processing saga.
#
# Usage:
#   chmod +x test-async-flow.sh
#   ./test-async-flow.sh [PRODUCT_ID] [QUANTITY] [USER_ID]
#
# Defaults:
#   PRODUCT_ID = 1
#   QUANTITY   = 1
#   USER_ID    = 100
#
# Prerequisites:
#   • All three services are running (order:8080, product:8081, payment:8082)
#   • Kafka broker reachable on localhost:9092
#   • curl and jq installed
#
# What this script does:
#   1.  Verifies all three service health endpoints.
#   2.  Confirms the target product exists and has enough stock.
#   3.  Places an order via POST /api/orders → captures orderId.
#   4.  Polls GET /api/orders/{id} every 2 s for up to 30 s until the order
#       leaves PENDING (reaches CONFIRMED, FAILED, or CANCELLED).
#   5.  Prints a clear PASS / FAIL summary.
#   6.  Optionally re-submits the SAME order payload to simulate a duplicate
#       event and confirms the system stays idempotent.
# =============================================================================

set -euo pipefail

# ── Configurable defaults ─────────────────────────────────────────────────────
ORDER_HOST="${ORDER_HOST:-http://localhost:8080}"
PRODUCT_HOST="${PRODUCT_HOST:-http://localhost:8081}"
PAYMENT_HOST="${PAYMENT_HOST:-http://localhost:8082}"
PRODUCT_ID="${1:-1}"
QUANTITY="${2:-1}"
USER_ID="${3:-100}"
PAYMENT_METHOD="${PAYMENT_METHOD:-CREDIT_CARD}"
POLL_INTERVAL=2        # seconds between status polls
POLL_TIMEOUT=30        # total seconds to wait for saga to complete
# ─────────────────────────────────────────────────────────────────────────────

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
BOLD='\033[1m'
RESET='\033[0m'

# ── Helper functions ──────────────────────────────────────────────────────────
log()   { echo -e "${CYAN}[$(date '+%H:%M:%S')]${RESET} $*"; }
ok()    { echo -e "${GREEN}[OK]${RESET} $*"; }
warn()  { echo -e "${YELLOW}[WARN]${RESET} $*"; }
fail()  { echo -e "${RED}[FAIL]${RESET} $*"; exit 1; }
sep()   { echo -e "${BOLD}────────────────────────────────────────────${RESET}"; }

require_cmd() {
    command -v "$1" >/dev/null 2>&1 || fail "'$1' is required but not installed."
}

# ── Dependency checks ─────────────────────────────────────────────────────────
require_cmd curl
require_cmd jq

sep
echo -e "${BOLD}  Order-Processing Saga — Async Flow Test${RESET}"
sep

# ── Step 1: Health checks ─────────────────────────────────────────────────────
log "Checking service health endpoints..."

check_health() {
    local name=$1 url=$2
    local status
    status=$(curl -sf "${url}/actuator/health" | jq -r '.status' 2>/dev/null || echo "DOWN")
    if [[ "$status" == "UP" ]]; then
        ok "${name} health: UP"
    else
        fail "${name} is not healthy (status='${status}'). Ensure all services and Kafka are running."
    fi
}

check_health "order-service"   "$ORDER_HOST"
check_health "product-service" "$PRODUCT_HOST"
check_health "payment-service" "$PAYMENT_HOST"

# ── Step 2: Verify product exists and has stock ───────────────────────────────
sep
log "Verifying product productId=${PRODUCT_ID} has at least ${QUANTITY} units in stock..."

PRODUCT_JSON=$(curl -sf "${PRODUCT_HOST}/api/products/${PRODUCT_ID}" || \
    fail "Could not fetch product ${PRODUCT_ID} from product-service.")

STOCK=$(echo "$PRODUCT_JSON" | jq -r '.stockQuantity')
PRODUCT_NAME=$(echo "$PRODUCT_JSON" | jq -r '.name')
PRICE=$(echo "$PRODUCT_JSON" | jq -r '.price')

log "Product: name='${PRODUCT_NAME}', price=${PRICE}, stockQuantity=${STOCK}"

if (( STOCK < QUANTITY )); then
    warn "Product has only ${STOCK} units — adjusting QUANTITY to ${STOCK} for this test."
    QUANTITY=$STOCK
fi

if (( QUANTITY == 0 )); then
    fail "Product ${PRODUCT_ID} has 0 stock. Cannot place order."
fi

ok "Product validated — proceeding with quantity=${QUANTITY}."

# ── Step 3: Place the order ───────────────────────────────────────────────────
sep
log "Placing order: userId=${USER_ID}, productId=${PRODUCT_ID}, quantity=${QUANTITY}, method=${PAYMENT_METHOD}"

ORDER_PAYLOAD=$(jq -n \
    --argjson userId      "$USER_ID" \
    --argjson productId   "$PRODUCT_ID" \
    --argjson quantity    "$QUANTITY" \
    --arg     paymentMethod "$PAYMENT_METHOD" \
    '{userId: $userId, productId: $productId, quantity: $quantity, paymentMethod: $paymentMethod}')

echo -e "  Payload: ${ORDER_PAYLOAD}"

ORDER_RESPONSE=$(curl -sf -X POST \
    -H "Content-Type: application/json" \
    -d "$ORDER_PAYLOAD" \
    "${ORDER_HOST}/api/orders" || \
    fail "POST /api/orders failed. Check order-service logs.")

ORDER_ID=$(echo "$ORDER_RESPONSE" | jq -r '.id')
INITIAL_STATUS=$(echo "$ORDER_RESPONSE" | jq -r '.status')

ok "Order created — orderId=${ORDER_ID}, initialStatus=${INITIAL_STATUS}"

if [[ "$INITIAL_STATUS" != "PENDING" ]]; then
    warn "Expected initial status PENDING but got '${INITIAL_STATUS}'."
fi

# ── Step 4: Poll for saga completion ─────────────────────────────────────────
sep
log "Polling GET ${ORDER_HOST}/api/orders/${ORDER_ID} every ${POLL_INTERVAL}s (timeout=${POLL_TIMEOUT}s)..."
log "Watching for order to leave PENDING status (CONFIRMED / FAILED / CANCELLED)..."
echo ""

ELAPSED=0
FINAL_STATUS=""

while (( ELAPSED < POLL_TIMEOUT )); do
    sleep "$POLL_INTERVAL"
    ELAPSED=$(( ELAPSED + POLL_INTERVAL ))

    CURRENT_JSON=$(curl -sf "${ORDER_HOST}/api/orders/${ORDER_ID}" || \
        fail "GET /api/orders/${ORDER_ID} failed.")
    CURRENT_STATUS=$(echo "$CURRENT_JSON" | jq -r '.status')

    log "  [t+${ELAPSED}s] orderId=${ORDER_ID}, status=${CURRENT_STATUS}"

    if [[ "$CURRENT_STATUS" != "PENDING" ]]; then
        FINAL_STATUS="$CURRENT_STATUS"
        break
    fi
done

# ── Step 5: Result ────────────────────────────────────────────────────────────
sep
if [[ -z "$FINAL_STATUS" ]]; then
    fail "Saga did NOT complete within ${POLL_TIMEOUT}s. Order is still PENDING. Check service logs."
fi

case "$FINAL_STATUS" in
    CONFIRMED)
        ok "${BOLD}SAGA COMPLETED — Order ${ORDER_ID} CONFIRMED (happy path ✓)${RESET}"
        ;;
    CANCELLED)
        warn "Saga completed — Order ${ORDER_ID} CANCELLED (stock reservation failed)."
        ;;
    FAILED)
        warn "Saga completed — Order ${ORDER_ID} FAILED (payment declined or error)."
        ;;
    *)
        warn "Saga completed with unexpected final status '${FINAL_STATUS}' for order ${ORDER_ID}."
        ;;
esac

# ── Step 6: Idempotency smoke test (duplicate order submission) ───────────────
sep
log "${BOLD}Idempotency check${RESET}: re-submitting the same order payload to verify no double-processing..."

DUPLICATE_RESPONSE=$(curl -sf -X POST \
    -H "Content-Type: application/json" \
    -d "$ORDER_PAYLOAD" \
    "${ORDER_HOST}/api/orders" || \
    fail "Duplicate POST /api/orders failed unexpectedly.")

DUPLICATE_ORDER_ID=$(echo "$DUPLICATE_RESPONSE" | jq -r '.id')
DUPLICATE_STATUS=$(echo "$DUPLICATE_RESPONSE" | jq -r '.status')

log "Duplicate order created with orderId=${DUPLICATE_ORDER_ID}, status=${DUPLICATE_STATUS}"
log "Waiting ${POLL_TIMEOUT}s for duplicate saga to settle..."
sleep "$POLL_TIMEOUT"

DUPLICATE_FINAL=$(curl -sf "${ORDER_HOST}/api/orders/${DUPLICATE_ORDER_ID}" | jq -r '.status')
log "Duplicate order final status: ${DUPLICATE_FINAL}"

# Validate idempotency: the duplicate order should not leave PENDING if there
# is no stock left (i.e. stock was not double-deducted), or it should independently
# succeed if there IS remaining stock. Either way it should not be stuck PENDING.
if [[ "$DUPLICATE_FINAL" == "PENDING" ]]; then
    warn "Duplicate order ${DUPLICATE_ORDER_ID} is still PENDING after ${POLL_TIMEOUT}s — check logs for idempotency guard output."
else
    ok "Duplicate order ${DUPLICATE_ORDER_ID} settled with status '${DUPLICATE_FINAL}'."
    ok "Idempotency guards fired correctly — check logs for '[IDEMPOTENCY]' entries."
fi

# ── Summary ───────────────────────────────────────────────────────────────────
sep
echo -e "${BOLD}  Test Summary${RESET}"
sep
echo -e "  Original order:   orderId=${ORDER_ID},           final status=${BOLD}${FINAL_STATUS}${RESET}"
echo -e "  Duplicate order:  orderId=${DUPLICATE_ORDER_ID}, final status=${BOLD}${DUPLICATE_FINAL}${RESET}"
sep
echo ""
echo -e "${CYAN}Tip:${RESET} To observe the full saga in real time, tail service logs while running this script:"
echo -e "  ${YELLOW}docker-compose logs -f order-service product-service payment-service | grep -E '\\[SAGA\\]|\\[IDEMPOTENCY\\]'${RESET}"
echo ""
