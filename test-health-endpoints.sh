#!/bin/bash

# Health Check Endpoint Testing Script
# Tests all health endpoints across all services

set -e

# Colors for output
GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Service configurations
declare -A SERVICES=(
    [order]="8080"
    [product]="8081"
    [payment]="8082"
)

TIMEOUT=5

echo "======================================"
echo "Microservices Health Endpoint Tester"
echo "======================================"
echo ""

# Function to test an endpoint
test_endpoint() {
    local service=$1
    local port=$2
    local endpoint=$3
    local description=$4
    
    local url="http://localhost:${port}/api/actuator/${endpoint}"
    
    echo -e "${BLUE}Testing ${description}${NC}"
    echo "  URL: $url"
    
    response=$(curl -s -w "\n%{http_code}" --connect-timeout $TIMEOUT "$url" 2>/dev/null || echo "Connection failed\n000")
    http_code=$(echo "$response" | tail -n1)
    body=$(echo "$response" | head -n-1)
    
    if [ "$http_code" == "200" ]; then
        echo -e "${GREEN}✓ HTTP $http_code - OK${NC}"
        
        # Extract and display key status information
        if echo "$body" | grep -q '"status"'; then
            status=$(echo "$body" | grep -o '"status":"[^"]*' | head -1 | cut -d'"' -f4)
            echo "  Status: $status"
        fi
        
        # For full health endpoint, show component details
        if [ "$endpoint" == "health" ]; then
            if echo "$body" | grep -q '"kafkaHealth"'; then
                kafka_status=$(echo "$body" | grep -o '"kafkaHealth":{"status":"[^"]*' | cut -d'"' -f8)
                [ ! -z "$kafka_status" ] && echo "  Kafka Health: $kafka_status"
            fi
            
            if echo "$body" | grep -q '"databaseHealth"'; then
                db_status=$(echo "$body" | grep -o '"databaseHealth":{"status":"[^"]*' | cut -d'"' -f8)
                [ ! -z "$db_status" ] && echo "  Database Health: $db_status"
            fi
        fi
    else
        echo -e "${RED}✗ HTTP $http_code - FAILED${NC}"
        echo "  Error: $body"
    fi
    
    echo ""
}

# Function to test service endpoints
test_service() {
    local service=$1
    local port=$2
    
    echo -e "${YELLOW}========== ${service^^} SERVICE (Port $port) ==========${NC}"
    echo ""
    
    # Full health check
    test_endpoint "$service" "$port" "health" "Full Health Report"
    
    # Liveness probe
    test_endpoint "$service" "$port" "health/live" "Liveness Probe"
    
    # Readiness probe
    test_endpoint "$service" "$port" "health/ready" "Readiness Probe"
    
    # Info endpoint
    test_endpoint "$service" "$port" "info" "Service Info"
}

# Function to test component-specific endpoints
test_component_health() {
    local service=$1
    local port=$2
    local component=$3
    
    local url="http://localhost:${port}/api/actuator/health/${component}"
    
    echo -e "${BLUE}Testing ${component^} Health${NC}"
    echo "  URL: $url"
    
    response=$(curl -s -w "\n%{http_code}" --connect-timeout $TIMEOUT "$url" 2>/dev/null || echo "Connection failed\n000")
    http_code=$(echo "$response" | tail -n1)
    body=$(echo "$response" | head -n-1)
    
    if [ "$http_code" == "200" ]; then
        echo -e "${GREEN}✓ HTTP $http_code - OK${NC}"
        if echo "$body" | grep -q '"status"'; then
            status=$(echo "$body" | grep -o '"status":"[^"]*' | head -1 | cut -d'"' -f4)
            echo "  Status: $status"
        fi
    else
        echo -e "${RED}✗ HTTP $http_code - Not Available${NC}"
    fi
    
    echo ""
}

# Main test execution
main() {
    # Test each service
    for service in "${!SERVICES[@]}"; do
        port=${SERVICES[$service]}
        test_service "$service" "$port"
    done
    
    echo ""
    echo "======================================"
    echo -e "${YELLOW}Advanced Component Tests${NC}"
    echo "======================================"
    echo ""
    
    # Test individual components for Order Service
    echo -e "${YELLOW}========== ORDER SERVICE COMPONENTS ==========${NC}"
    echo ""
    test_component_health "order" "8080" "db"
    test_component_health "order" "8080" "kafkaHealth"
    test_component_health "order" "8080" "databaseHealth"
    
    echo ""
    echo "======================================"
    echo "Health Check Testing Complete"
    echo "======================================"
}

# Run main function
main
