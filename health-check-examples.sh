#!/bin/bash

# Health Check Examples
# Shows how to test the health endpoints with various curl commands

echo "================================================"
echo "Health Check Examples - Microservices"
echo "================================================"
echo ""

# Colors for output
GREEN='\033[0;32m'
BLUE='\033[0;34m'
NC='\033[0m'

# Base URLs
ORDER_URL="http://localhost:8080/api"
PRODUCT_URL="http://localhost:8081/api"
PAYMENT_URL="http://localhost:8082/api"

echo -e "${BLUE}=== ORDER SERVICE HEALTH CHECKS ===${NC}"
echo ""

echo -e "${GREEN}1. Full Health Report${NC}"
echo "Command:"
echo "curl $ORDER_URL/actuator/health | jq"
echo ""
echo "Description: Returns complete health status of all components"
echo "Expected: HTTP 200, status UP with all components UP"
echo ""

echo -e "${GREEN}2. Liveness Probe${NC}"
echo "Command:"
echo "curl $ORDER_URL/actuator/health/live"
echo ""
echo "Description: Kubernetes liveness probe - is service running?"
echo "Expected: HTTP 200, status UP"
echo ""

echo -e "${GREEN}3. Readiness Probe${NC}"
echo "Command:"
echo "curl $ORDER_URL/actuator/health/ready"
echo ""
echo "Description: Kubernetes readiness probe - is service ready for traffic?"
echo "Expected: HTTP 200, status UP"
echo ""

echo -e "${GREEN}4. Database Health Only${NC}"
echo "Command:"
echo "curl $ORDER_URL/actuator/health/db"
echo ""
echo "Description: Check default Spring database health indicator"
echo "Expected: HTTP 200, status UP"
echo ""

echo -e "${GREEN}5. Custom Database Health${NC}"
echo "Command:"
echo "curl $ORDER_URL/actuator/health/databaseHealth"
echo ""
echo "Description: Check custom database connectivity"
echo "Expected: HTTP 200, status UP with connection details"
echo ""

echo -e "${GREEN}6. Kafka Health${NC}"
echo "Command:"
echo "curl $ORDER_URL/actuator/health/kafka"
echo ""
echo "Description: Check default Spring Kafka health indicator"
echo "Expected: HTTP 200, status UP"
echo ""

echo -e "${GREEN}7. Custom Kafka Health${NC}"
echo "Command:"
echo "curl $ORDER_URL/actuator/health/kafkaHealth"
echo ""
echo "Description: Check custom Kafka broker connectivity"
echo "Expected: HTTP 200, status UP with broker details"
echo ""

echo -e "${GREEN}8. Service Info${NC}"
echo "Command:"
echo "curl $ORDER_URL/actuator/info"
echo ""
echo "Description: Get service information and version details"
echo "Expected: HTTP 200 with service metadata"
echo ""

echo -e "${GREEN}9. Pretty Print Health Response${NC}"
echo "Command:"
echo "curl -s $ORDER_URL/actuator/health | jq '.components | keys'"
echo ""
echo "Description: List all health check components"
echo "Expected: Array of component names"
echo ""

echo -e "${GREEN}10. Extract Status Only${NC}"
echo "Command:"
echo "curl -s $ORDER_URL/actuator/health | jq '.status'"
echo ""
echo "Description: Get overall health status"
echo "Expected: \"UP\" or \"DOWN\""
echo ""

echo ""
echo -e "${BLUE}=== BATCH TESTING ALL SERVICES ===${NC}"
echo ""

echo -e "${GREEN}Test all services with one command${NC}"
echo "Command:"
cat << 'EOF'
for service in order product payment; do
  for port in 8080 8081 8082; do
    [ "$service" = "order" ] && [ $port -eq 8080 ] && \
    echo "Order Service:" && \
    curl -s http://localhost:$port/api/actuator/health | jq '.status' && \
    break
    [ "$service" = "product" ] && [ $port -eq 8081 ] && \
    echo "Product Service:" && \
    curl -s http://localhost:$port/api/actuator/health | jq '.status' && \
    break
    [ "$service" = "payment" ] && [ $port -eq 8082 ] && \
    echo "Payment Service:" && \
    curl -s http://localhost:$port/api/actuator/health | jq '.status' && \
    break
  done
done
EOF
echo ""

echo -e "${BLUE}=== MONITORING HEALTH OVER TIME ===${NC}"
echo ""

echo -e "${GREEN}Watch health status every 5 seconds${NC}"
echo "Command:"
echo "watch -n 5 'curl -s http://localhost:8080/api/actuator/health | jq \".status\"'"
echo ""

echo -e "${GREEN}Log health status every minute${NC}"
echo "Command:"
echo "while true; do echo \"\$(date): \$(curl -s http://localhost:8080/api/actuator/health | jq '.status')\"; sleep 60; done"
echo ""

echo -e "${BLUE}=== FAILURE SCENARIOS ===${NC}"
echo ""

echo -e "${GREEN}Test service down (port doesn't exist)${NC}"
echo "Command:"
echo "curl http://localhost:9999/api/actuator/health"
echo "Expected: Connection refused error"
echo ""

echo -e "${GREEN}Test invalid endpoint${NC}"
echo "Command:"
echo "curl http://localhost:8080/api/actuator/invalid"
echo "Expected: HTTP 404 Not Found"
echo ""

echo -e "${GREEN}Test with timeout${NC}"
echo "Command:"
echo "curl --connect-timeout 2 http://localhost:8080/api/actuator/health"
echo "Expected: Connection established or timeout error"
echo ""

echo -e "${BLUE}=== RESPONSE CODES ===${NC}"
echo ""
echo "HTTP 200: Service and all components are healthy"
echo "HTTP 503: One or more components are down"
echo "HTTP 404: Endpoint not found"
echo "HTTP 500: Internal server error"
echo ""

echo -e "${BLUE}=== TYPICAL RESPONSE EXAMPLE ===${NC}"
echo ""
cat << 'EOF'
{
  "status": "UP",
  "components": {
    "db": {
      "status": "UP",
      "details": {
        "database": "PostgreSQL",
        "validationQuery": "isValid()"
      }
    },
    "databaseHealth": {
      "status": "UP",
      "details": {
        "status": "Database connection is active",
        "database": "PostgreSQL"
      }
    },
    "kafka": {
      "status": "UP",
      "details": {}
    },
    "kafkaHealth": {
      "status": "UP",
      "details": {
        "status": "Kafka broker is reachable",
        "bootstrapServers": null
      }
    },
    "livenessState": {
      "status": "UP"
    },
    "readinessState": {
      "status": "UP"
    }
  }
}
EOF
echo ""

echo "================================================"
echo "For more details, see HEALTH_CHECK_CONFIGURATION.md"
echo "================================================"
