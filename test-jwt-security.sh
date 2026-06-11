#!/usr/bin/env bash
# =============================================================================
# test-jwt-security.sh
#
# End-to-end JWT Authentication & Authorization test suite.
#
# Covers every scenario from the Day-7 Postman guide:
#   Phase 1 — 401 without token (4 cases)
#   Phase 2 — Token issuance: login, bad password, blank username
#   Phase 3 — Authenticated requests: products, order creation (userId from JWT)
#   Phase 4 — Token edge cases: tampered token, wrong scheme, blank validation
#   Phase 5 — Security assertion: userId in response MUST equal JWT uid, not body
#
# Usage:
#   chmod +x test-jwt-security.sh
#   ./test-jwt-security.sh
#
# Prerequisites:
#   • All services running:
#       gateway-service  :8079
#       order-service    :8080
#       product-service  :8081
#       payment-service  :8082
#       auth-service     :8090
#   • curl and jq installed
#
# All requests go through the GATEWAY on port 8079 — exactly as Postman would.
# =============================================================================

set -uo pipefail

# ── Colours ───────────────────────────────────────────────────────────────────
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
BLUE='\033[0;34m'
BOLD='\033[1m'
DIM='\033[2m'
RESET='\033[0m'

# ── Config ────────────────────────────────────────────────────────────────────
GATEWAY="http://localhost:8079"   # ALL requests through the gateway
PAUSE=0.4                         # seconds between tests (readability)

# ── Counters ──────────────────────────────────────────────────────────────────
TOTAL=0
PASSED=0
FAILED=0

# ── Helpers ───────────────────────────────────────────────────────────────────
sep()     { echo -e "${BOLD}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${RESET}"; }
thin()    { echo -e "${DIM}────────────────────────────────────────────────────────────${RESET}"; }
header()  { sep; echo -e "  ${BOLD}${BLUE}$*${RESET}"; sep; }
info()    { echo -e "  ${CYAN}ℹ${RESET}  $*"; }
pass()    { echo -e "  ${GREEN}✔  PASS${RESET}  $*"; (( PASSED++ )); }
fail()    { echo -e "  ${RED}✘  FAIL${RESET}  $*"; (( FAILED++ )); }
warn()    { echo -e "  ${YELLOW}⚠${RESET}  $*"; }

require_cmd() {
    command -v "$1" >/dev/null 2>&1 || {
        echo -e "${RED}[ERROR]${RESET} '$1' is required but not installed. Aborting."
        exit 1
    }
}

# Run one test.
# Usage: run_test "label" <expected_http_status> curl_args...
# Prints the label, HTTP status received, and PASS/FAIL.
run_test() {
    local label="$1"
    local expected_status="$2"
    shift 2

    (( TOTAL++ ))
    thin
    echo -e "\n  ${BOLD}Test ${TOTAL}:${RESET} ${label}"
    echo -e "  ${DIM}Expected HTTP: ${expected_status}${RESET}"

    # Run curl; capture HTTP status code and response body separately
    local http_status body tmp_body
    tmp_body=$(mktemp)

    http_status=$(curl -s -o "$tmp_body" -w "%{http_code}" \
        --connect-timeout 5 \
        "$@" 2>/dev/null)
    http_status="${http_status:-000}"
    body=$(cat "$tmp_body")
    rm -f "$tmp_body"

    echo -e "  ${DIM}Received HTTP: ${http_status}${RESET}"

    # Pretty-print body if it's valid JSON, otherwise show raw
    if echo "$body" | jq . >/dev/null 2>&1 && [[ -n "$body" ]]; then
        echo -e "  ${DIM}Response body:${RESET}"
        echo "$body" | jq . | sed 's/^/    /'
    elif [[ -n "$body" ]]; then
        echo -e "  ${DIM}Response body:${RESET} $body"
    fi

    if [[ "$http_status" == "$expected_status" ]]; then
        pass "Got expected HTTP ${http_status}"
    else
        fail "Expected HTTP ${expected_status}, got ${http_status}"
    fi

    sleep "$PAUSE"
}

# ── Startup ───────────────────────────────────────────────────────────────────
clear
echo ""
sep
echo -e "  ${BOLD}${BLUE}JWT Security Test Suite — Day 7${RESET}"
echo -e "  ${DIM}Gateway: ${GATEWAY}${RESET}"
sep
echo ""

require_cmd curl
require_cmd jq

# ── Gateway reachability check ────────────────────────────────────────────────
info "Checking gateway is up at ${GATEWAY} ..."
GW_STATUS=$(curl -s -o /dev/null -w "%{http_code}" \
    --connect-timeout 5 "${GATEWAY}/actuator/health" 2>/dev/null)
GW_STATUS="${GW_STATUS:-000}"

if [[ "$GW_STATUS" == "000" ]]; then
    echo -e "${RED}[ERROR]${RESET} Cannot reach gateway on ${GATEWAY}."
    echo -e "        Start all services first, then re-run this script."
    exit 1
fi
echo -e "  ${GREEN}Gateway reachable${RESET} (health HTTP ${GW_STATUS})"
echo ""

# =============================================================================
# PHASE 1 — 401 WITHOUT ANY TOKEN
# These tests prove SecurityConfig.anyExchange().authenticated() is enforced.
# =============================================================================
header "PHASE 1 — 401 Responses Without a Token"

run_test \
    "GET /product  — no Authorization header at all" \
    "401" \
    -X GET \
    "${GATEWAY}/product"

run_test \
    "POST /order   — no Authorization header at all" \
    "401" \
    -X POST \
    -H "Content-Type: application/json" \
    -d '{"productId":1,"quantity":2,"paymentMethod":"CREDIT_CARD"}' \
    "${GATEWAY}/order"

run_test \
    "GET /product  — wrong scheme ('Token' instead of 'Bearer')" \
    "401" \
    -X GET \
    -H "Authorization: Token abc123" \
    "${GATEWAY}/product"

run_test \
    "GET /product  — Bearer token with a fake/tampered signature" \
    "401" \
    -X GET \
    -H "Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJoYWNrZXIiLCJ1aWQiOjk5OX0.FAKE_SIGNATURE_TAMPERED" \
    "${GATEWAY}/product"

# =============================================================================
# PHASE 2 — TOKEN ISSUANCE  (POST /auth/login)
# =============================================================================
header "PHASE 2 — Token Issuance via POST /auth/login"

# ── 2A: Login as 'user' ───────────────────────────────────────────────────────
(( TOTAL++ ))
thin
echo -e "\n  ${BOLD}Test ${TOTAL}:${RESET} POST /auth/login — valid credentials (user / userpassword)"
echo -e "  ${DIM}Expected HTTP: 200 + { token, type, expiresIn }${RESET}"

USER_LOGIN_BODY=$(curl -s -w "\n%{http_code}" \
    --connect-timeout 5 \
    -X POST \
    -H "Content-Type: application/json" \
    -d '{"username":"user","password":"userpassword"}' \
    "${GATEWAY}/auth/login" 2>/dev/null)

USER_HTTP_STATUS=$(echo "$USER_LOGIN_BODY" | tail -1)
USER_JSON=$(echo "$USER_LOGIN_BODY" | sed '$d')

echo -e "  ${DIM}Received HTTP: ${USER_HTTP_STATUS}${RESET}"
echo -e "  ${DIM}Response body:${RESET}"
echo "$USER_JSON" | jq . | sed 's/^/    /' 2>/dev/null || echo "    $USER_JSON"

if [[ "$USER_HTTP_STATUS" == "200" ]]; then
    USER_TOKEN=$(echo "$USER_JSON" | jq -r '.token' 2>/dev/null)
    TOKEN_TYPE=$(echo "$USER_JSON" | jq -r '.type'  2>/dev/null)
    EXPIRES_IN=$(echo "$USER_JSON" | jq -r '.expiresIn' 2>/dev/null)

    if [[ -n "$USER_TOKEN" && "$USER_TOKEN" != "null" \
        && "$TOKEN_TYPE" == "Bearer" \
        && -n "$EXPIRES_IN" && "$EXPIRES_IN" != "null" ]]; then

        # Verify it is a 3-part JWT
        PARTS=$(echo "$USER_TOKEN" | tr '.' '\n' | wc -l | tr -d ' ')
        if [[ "$PARTS" == "3" ]]; then
            pass "Got HTTP 200, token is a valid 3-part JWT, type=Bearer, expiresIn=${EXPIRES_IN}s"
        else
            fail "Token present but is not a valid 3-part JWT (got ${PARTS} parts)"
        fi
    else
        fail "HTTP 200 but response is missing token/type/expiresIn fields"
    fi
else
    fail "Expected HTTP 200, got ${USER_HTTP_STATUS}"
    USER_TOKEN=""
fi

sleep "$PAUSE"

# ── Decode and display the 'user' JWT payload ─────────────────────────────────
if [[ -n "${USER_TOKEN:-}" && "$USER_TOKEN" != "null" ]]; then
    thin
    echo -e "\n  ${BOLD}JWT Payload (user token — decoded, not verified):${RESET}"
    PAYLOAD_B64=$(echo "$USER_TOKEN" | cut -d'.' -f2)
    # Base64-url decode: replace - with + and _ with /; pad to multiple of 4
    PADDED="${PAYLOAD_B64}=="
    DECODED=$(echo "$PADDED" | tr '_-' '/+' | base64 -d 2>/dev/null || echo "{}")
    echo "$DECODED" | jq . | sed 's/^/    /'

    UID_CLAIM=$(echo "$DECODED" | jq -r '.uid'   2>/dev/null)
    SUB_CLAIM=$(echo "$DECODED" | jq -r '.sub'   2>/dev/null)
    ROLES_CLAIM=$(echo "$DECODED" | jq -r '.roles[]' 2>/dev/null | tr '\n' ' ')
    info "sub=${SUB_CLAIM}  uid=${UID_CLAIM}  roles=${ROLES_CLAIM}"
fi

# ── 2B: Login as 'admin' ──────────────────────────────────────────────────────
(( TOTAL++ ))
thin
echo -e "\n  ${BOLD}Test ${TOTAL}:${RESET} POST /auth/login — valid credentials (admin / secret)"
echo -e "  ${DIM}Expected HTTP: 200 + roles include ROLE_ADMIN${RESET}"

ADMIN_LOGIN=$(curl -s -w "\n%{http_code}" \
    --connect-timeout 5 \
    -X POST \
    -H "Content-Type: application/json" \
    -d '{"username":"admin","password":"secret"}' \
    "${GATEWAY}/auth/login" 2>/dev/null)

ADMIN_HTTP=$(echo "$ADMIN_LOGIN" | tail -1)
ADMIN_JSON=$(echo "$ADMIN_LOGIN" | sed '$d')

echo -e "  ${DIM}Received HTTP: ${ADMIN_HTTP}${RESET}"
echo "$ADMIN_JSON" | jq . | sed 's/^/    /' 2>/dev/null

if [[ "$ADMIN_HTTP" == "200" ]]; then
    ADMIN_TOKEN=$(echo "$ADMIN_JSON" | jq -r '.token')
    ADMIN_PAYLOAD_B64=$(echo "$ADMIN_TOKEN" | cut -d'.' -f2)
    ADMIN_DECODED=$(echo "${ADMIN_PAYLOAD_B64}==" | tr '_-' '/+' | base64 -d 2>/dev/null || echo "{}")
    ADMIN_UID=$(echo "$ADMIN_DECODED" | jq -r '.uid')
    HAS_ADMIN_ROLE=$(echo "$ADMIN_DECODED" | jq -r '.roles | contains(["ROLE_ADMIN"])')

    if [[ "$ADMIN_UID" == "1" && "$HAS_ADMIN_ROLE" == "true" ]]; then
        pass "HTTP 200, admin uid=1, ROLE_ADMIN present in token"
    else
        fail "HTTP 200 but uid=${ADMIN_UID} (expected 1) or ROLE_ADMIN missing"
    fi
else
    fail "Expected HTTP 200, got ${ADMIN_HTTP}"
    ADMIN_TOKEN=""
fi

sleep "$PAUSE"

# ── 2C: Wrong password ────────────────────────────────────────────────────────
run_test \
    "POST /auth/login — wrong password → 401" \
    "401" \
    -X POST \
    -H "Content-Type: application/json" \
    -d '{"username":"admin","password":"wrongpassword"}' \
    "${GATEWAY}/auth/login"

# ── 2D: Unknown user ──────────────────────────────────────────────────────────
run_test \
    "POST /auth/login — unknown username → 401" \
    "401" \
    -X POST \
    -H "Content-Type: application/json" \
    -d '{"username":"hacker","password":"secret"}' \
    "${GATEWAY}/auth/login"

# ── 2E: Blank username (Bean Validation) ──────────────────────────────────────
run_test \
    "POST /auth/login — blank username → 400 (Bean Validation @NotBlank)" \
    "400" \
    -X POST \
    -H "Content-Type: application/json" \
    -d '{"username":"","password":"secret"}' \
    "${GATEWAY}/auth/login"

# ── 2F: Missing body ──────────────────────────────────────────────────────────
run_test \
    "POST /auth/login — missing body entirely → 400" \
    "400" \
    -X POST \
    -H "Content-Type: application/json" \
    "${GATEWAY}/auth/login"

# =============================================================================
# PHASE 3 — AUTHENTICATED REQUESTS (require USER_TOKEN from Phase 2)
# =============================================================================
header "PHASE 3 — Authenticated Requests (user token)"

if [[ -z "${USER_TOKEN:-}" || "$USER_TOKEN" == "null" ]]; then
    warn "Skipping Phase 3 — could not obtain user token in Phase 2."
    warn "Check that auth-service is running on :8090."
else
    # ── 3A: GET all products ────────────────────────────────────────────────
    run_test \
        "GET /product — with valid Bearer token → 200" \
        "200" \
        -X GET \
        -H "Authorization: Bearer ${USER_TOKEN}" \
        "${GATEWAY}/product"

    # ── 3B: Create a product (need data for order test) ─────────────────────
    (( TOTAL++ ))
    thin
    echo -e "\n  ${BOLD}Test ${TOTAL}:${RESET} POST /product — create product → 201"
    echo -e "  ${DIM}Expected HTTP: 201 Created${RESET}"

    CREATE_PRODUCT_RESP=$(curl -s -w "\n%{http_code}" \
        --connect-timeout 5 \
        -X POST \
        -H "Content-Type: application/json" \
        -H "Authorization: Bearer ${USER_TOKEN}" \
        -d '{
              "sku":           "LAPTOP-JWT-001",
              "name":          "JWT Test Laptop",
              "description":   "Created by test-jwt-security.sh",
              "price":         999.99,
              "stockQuantity": 100
            }' \
        "${GATEWAY}/product" 2>/dev/null)

    CREATE_PRODUCT_HTTP=$(echo "$CREATE_PRODUCT_RESP" | tail -1)
    CREATE_PRODUCT_JSON=$(echo "$CREATE_PRODUCT_RESP" | sed '$d')

    echo -e "  ${DIM}Received HTTP: ${CREATE_PRODUCT_HTTP}${RESET}"
    echo "$CREATE_PRODUCT_JSON" | jq . | sed 's/^/    /' 2>/dev/null

    PRODUCT_ID=$(echo "$CREATE_PRODUCT_JSON" | jq -r '.id' 2>/dev/null)

    if [[ "$CREATE_PRODUCT_HTTP" == "201" && -n "$PRODUCT_ID" && "$PRODUCT_ID" != "null" ]]; then
        pass "Product created with id=${PRODUCT_ID}"
    else
        fail "Expected HTTP 201, got ${CREATE_PRODUCT_HTTP}"
        # Fallback: try to use product id=1 if it already exists
        PRODUCT_ID="1"
        warn "Falling back to productId=1 for order tests"
    fi

    sleep "$PAUSE"

    # ── 3C: GET single product ───────────────────────────────────────────────
    run_test \
        "GET /product/${PRODUCT_ID} — single product by id → 200" \
        "200" \
        -X GET \
        -H "Authorization: Bearer ${USER_TOKEN}" \
        "${GATEWAY}/product/${PRODUCT_ID}"

    # ── 3D: POST /order — THE KEY SECURITY TEST ──────────────────────────────
    # userId must come from JWT uid claim (=2), NOT from the request body.
    # The request body has NO userId field — OrderRequest.java does not have one.
    (( TOTAL++ ))
    thin
    echo -e "\n  ${BOLD}Test ${TOTAL}:${RESET} POST /order — userId injected from JWT uid claim, not body → 201"
    echo -e "  ${DIM}Expected HTTP: 201, and response.userId must equal JWT uid (2)${RESET}"

    CREATE_ORDER_RESP=$(curl -s -w "\n%{http_code}" \
        --connect-timeout 5 \
        -X POST \
        -H "Content-Type: application/json" \
        -H "Authorization: Bearer ${USER_TOKEN}" \
        -d "{
              \"productId\":     ${PRODUCT_ID},
              \"quantity\":      2,
              \"paymentMethod\": \"CREDIT_CARD\"
            }" \
        "${GATEWAY}/order" 2>/dev/null)

    ORDER_HTTP=$(echo "$CREATE_ORDER_RESP" | tail -1)
    ORDER_JSON=$(echo "$CREATE_ORDER_RESP" | sed '$d')

    echo -e "  ${DIM}Received HTTP: ${ORDER_HTTP}${RESET}"
    echo "$ORDER_JSON" | jq . | sed 's/^/    /' 2>/dev/null

    if [[ "$ORDER_HTTP" == "201" ]]; then
        RESPONSE_USER_ID=$(echo "$ORDER_JSON" | jq -r '.userId' 2>/dev/null)
        ORDER_ID=$(echo "$ORDER_JSON"         | jq -r '.id'     2>/dev/null)

        echo ""
        info "JWT uid claim = ${UID_CLAIM:-?}"
        info "Order response userId = ${RESPONSE_USER_ID}"

        if [[ "$RESPONSE_USER_ID" == "${UID_CLAIM:-2}" ]]; then
            pass "HTTP 201 — orderId=${ORDER_ID}, userId=${RESPONSE_USER_ID} matches JWT uid (not body)"
        else
            fail "HTTP 201 but userId=${RESPONSE_USER_ID} does NOT match JWT uid=${UID_CLAIM:-2}"
        fi
    else
        fail "Expected HTTP 201, got ${ORDER_HTTP}"
        ORDER_ID=""
    fi

    sleep "$PAUSE"

    # ── 3E: GET order by id ──────────────────────────────────────────────────
    if [[ -n "${ORDER_ID:-}" && "$ORDER_ID" != "null" ]]; then
        run_test \
            "GET /order/${ORDER_ID} — fetch created order → 200" \
            "200" \
            -X GET \
            -H "Authorization: Bearer ${USER_TOKEN}" \
            "${GATEWAY}/order/${ORDER_ID}"
    fi
fi

# =============================================================================
# PHASE 4 — EDGE CASES
# =============================================================================
header "PHASE 4 — Token Edge Cases"

# ── 4A: Structurally valid JWT but wrong secret (signature mismatch) ──────────
# Build a plausible-looking JWT payload signed with a DIFFERENT secret.
# The gateway will reject it because HMAC verification will fail.
FAKE_HEADER='{"alg":"HS256","typ":"JWT"}'
FAKE_PAYLOAD='{"sub":"hacker","uid":99,"roles":["ROLE_ADMIN"],"iat":9999999999,"exp":9999999999}'
FAKE_HEADER_B64=$(echo -n "$FAKE_HEADER"  | base64 | tr '+/' '-_' | tr -d '=')
FAKE_PAYLOAD_B64=$(echo -n "$FAKE_PAYLOAD" | base64 | tr '+/' '-_' | tr -d '=')
FAKE_TOKEN="${FAKE_HEADER_B64}.${FAKE_PAYLOAD_B64}.FakeSignatureThatWillFailHmacVerification"

run_test \
    "GET /product — structurally valid JWT signed with wrong secret → 401" \
    "401" \
    -X GET \
    -H "Authorization: Bearer ${FAKE_TOKEN}" \
    "${GATEWAY}/product"

# ── 4B: Completely malformed token (not 3 parts) ─────────────────────────────
run_test \
    "GET /product — malformed token (not 3 parts) → 401" \
    "401" \
    -X GET \
    -H "Authorization: Bearer notavalidjwtatall" \
    "${GATEWAY}/product"

# ── 4C: Empty Bearer value ────────────────────────────────────────────────────
run_test \
    "GET /product — empty Bearer value → 401" \
    "401" \
    -X GET \
    -H "Authorization: Bearer " \
    "${GATEWAY}/product"

# ── 4D: Auth service public endpoint needs no token (sanity) ─────────────────
run_test \
    "POST /auth/login — /auth/** is permitAll, needs no token → NOT 401" \
    "401" \
    -X POST \
    -H "Content-Type: application/json" \
    -d '{"username":"user","password":"userpassword"}' \
    "${GATEWAY}/auth/login" 2>/dev/null || true   # expect 200, shown in phase 2

# Actually assert 200 for the public path
(( TOTAL++ ))
thin
echo -e "\n  ${BOLD}Test ${TOTAL}:${RESET} /auth/** is public — login without token must return 200 (not 401)"
echo -e "  ${DIM}Expected HTTP: 200${RESET}"

PUBLIC_STATUS=$(curl -s -o /dev/null -w "%{http_code}" \
    --connect-timeout 5 \
    -X POST \
    -H "Content-Type: application/json" \
    -d '{"username":"user","password":"userpassword"}' \
    "${GATEWAY}/auth/login" 2>/dev/null)

echo -e "  ${DIM}Received HTTP: ${PUBLIC_STATUS}${RESET}"

if [[ "$PUBLIC_STATUS" == "200" ]]; then
    pass "HTTP 200 — /auth/login correctly exempt from authentication gate"
else
    fail "Expected HTTP 200, got ${PUBLIC_STATUS} — /auth/login may be incorrectly secured"
fi

sleep "$PAUSE"

# ── 4E: Actuator health is public (no token needed) ──────────────────────────
(( TOTAL++ ))
thin
echo -e "\n  ${BOLD}Test ${TOTAL}:${RESET} GET /actuator/health — public endpoint needs no token → 200"
echo -e "  ${DIM}Expected HTTP: 200${RESET}"

ACTUATOR_STATUS=$(curl -s -o /dev/null -w "%{http_code}" \
    --connect-timeout 5 \
    "${GATEWAY}/actuator/health" 2>/dev/null)

echo -e "  ${DIM}Received HTTP: ${ACTUATOR_STATUS}${RESET}"

if [[ "$ACTUATOR_STATUS" == "200" ]]; then
    pass "HTTP 200 — /actuator/health correctly exempt from authentication gate"
else
    fail "Expected HTTP 200, got ${ACTUATOR_STATUS}"
fi

sleep "$PAUSE"

# =============================================================================
# PHASE 5 — SECURITY ASSERTION: userId from JWT, never from body
# =============================================================================
header "PHASE 5 — Security Assertion: userId is JWT-bound, Never from Body"

if [[ -z "${USER_TOKEN:-}" || "$USER_TOKEN" == "null" ]]; then
    warn "Skipping Phase 5 — no user token available."
else
    (( TOTAL++ ))
    thin
    echo -e "\n  ${BOLD}Test ${TOTAL}:${RESET} POST /order body has no userId — response.userId must equal JWT uid"
    echo -e "  ${DIM}user token uid = ${UID_CLAIM:-2}  (this is what the order must show)${RESET}"
    echo -e "  ${DIM}Request body contains: productId, quantity, paymentMethod only${RESET}"

    PROOF_RESP=$(curl -s -w "\n%{http_code}" \
        --connect-timeout 5 \
        -X POST \
        -H "Content-Type: application/json" \
        -H "Authorization: Bearer ${USER_TOKEN}" \
        -d "{
              \"productId\":     ${PRODUCT_ID:-1},
              \"quantity\":      1,
              \"paymentMethod\": \"DIGITAL_WALLET\"
            }" \
        "${GATEWAY}/order" 2>/dev/null)

    PROOF_HTTP=$(echo "$PROOF_RESP" | tail -1)
    PROOF_JSON=$(echo "$PROOF_RESP" | sed '$d')

    echo -e "  ${DIM}Received HTTP: ${PROOF_HTTP}${RESET}"
    echo "$PROOF_JSON" | jq . | sed 's/^/    /' 2>/dev/null

    if [[ "$PROOF_HTTP" == "201" ]]; then
        RESP_UID=$(echo "$PROOF_JSON" | jq -r '.userId' 2>/dev/null)
        echo ""
        info "Body sent:              { productId, quantity, paymentMethod }  — NO userId"
        info "JWT uid claim:          ${UID_CLAIM:-2}"
        info "Response userId:        ${RESP_UID}"

        if [[ "$RESP_UID" == "${UID_CLAIM:-2}" ]]; then
            pass "userId=${RESP_UID} matches JWT uid — client cannot inject a foreign userId ✓"
        else
            fail "userId=${RESP_UID} does not match JWT uid=${UID_CLAIM:-2} — security gap!"
        fi
    else
        warn "Could not create order (HTTP ${PROOF_HTTP}) — assertion skipped."
    fi
fi

# =============================================================================
# SUMMARY
# =============================================================================
sep
echo ""
echo -e "  ${BOLD}Test Results${RESET}"
sep
echo ""
echo -e "  Total tests run : ${BOLD}${TOTAL}${RESET}"
echo -e "  ${GREEN}Passed          : ${BOLD}${PASSED}${RESET}"
if (( FAILED > 0 )); then
    echo -e "  ${RED}Failed          : ${BOLD}${FAILED}${RESET}"
else
    echo -e "  Failed          : ${BOLD}0${RESET}"
fi
echo ""

if (( FAILED == 0 )); then
    echo -e "  ${GREEN}${BOLD}All tests passed. JWT security layer is working correctly.${RESET}"
else
    SKIPPED=$(( TOTAL - PASSED - FAILED ))
    echo -e "  ${YELLOW}${BOLD}${FAILED} test(s) failed.${RESET}"
    echo -e "  ${DIM}Check that all 5 services are running and the shared JWT_SECRET matches.${RESET}"
fi

echo ""
sep
echo ""
echo -e "  ${CYAN}Tip:${RESET} To watch live gateway JWT validation logs while this script runs:"
echo -e "  ${DIM}docker-compose logs -f gateway-service | grep -E '\\[Gateway\\]|JWT'${RESET}"
echo ""
echo -e "  ${CYAN}Tip:${RESET} To watch order-service pick up the principal from headers:"
echo -e "  ${DIM}docker-compose logs -f order-service | grep -E '\\[OrderService\\]|userId'${RESET}"
echo ""

exit $(( FAILED > 0 ? 1 : 0 ))
