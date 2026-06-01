#!/bin/bash

# Health Check Verification Script
# This script verifies that all microservices are running and healthy

set -e

# Colors for output
GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Configuration
ORDER_SERVICE_URL="http://localhost:8080/api/actuator/health"
PRODUCT_SERVICE_URL="http://localhost:8081/api/actuator/health"
PAYMENT_SERVICE_URL="http://localhost:8082/api/actuator/health"

TIMEOUT=5
MAX_RETRIES=30
RETRY_DELAY=2

echo "================================"
echo "Service Health Check Verification"
echo "================================"
echo ""

# Function to check service health
check_service_health() {
    local service_name=$1
    local service_url=$2
    local retry_count=0
    
    echo -e "${YELLOW}Checking ${service_name}...${NC}"
    
    while [ $retry_count -lt $MAX_RETRIES ]; do
        response=$(curl -s -o /dev/null -w "%{http_code}" --connect-timeout $TIMEOUT $service_url)
        
        if [ "$response" == "200" ]; then
            # Get the full health response
            health_response=$(curl -s --connect-timeout $TIMEOUT $service_url)
            status=$(echo $health_response | grep -o '"status":"[^"]*' | head -1 | cut -d'"' -f4)
            
            if [ "$status" == "UP" ]; then
                echo -e "${GREEN}✓ ${service_name} is HEALTHY (HTTP $response)${NC}"
                echo "  Status: $status"
                
                # Check component statuses
                if echo $health_response | grep -q '"kafkaHealth"'; then
                    kafka_status=$(echo $health_response | grep -o '"kafkaHealth":{"status":"[^"]*' | cut -d'"' -f8)
                    echo "  - Kafka Health: ${kafka_status}"
                fi
                
                if echo $health_response | grep -q '"databaseHealth"'; then
                    db_status=$(echo $health_response | grep -o '"databaseHealth":{"status":"[^"]*' | cut -d'"' -f8)
                    echo "  - Database Health: ${db_status}"
                fi
                
                echo ""
                return 0
            else
                echo -e "${RED}✗ ${service_name} returned status: $status${NC}"
                return 1
            fi
        fi
        
        retry_count=$((retry_count + 1))
        
        if [ $retry_count -lt $MAX_RETRIES ]; then
            echo "  Waiting for service to be available... (attempt $((retry_count + 1))/$MAX_RETRIES)"
            sleep $RETRY_DELAY
        fi
    done
    
    echo -e "${RED}✗ ${service_name} is NOT RESPONSIVE (timeout after $((MAX_RETRIES * RETRY_DELAY)) seconds)${NC}"
    echo ""
    return 1
}

# Function to check Docker containers
check_docker_containers() {
    echo -e "${YELLOW}Checking Docker containers...${NC}"
    
    containers=("kafka-kraft" "postgres-order" "postgres-product" "postgres-payment")
    
    for container in "${containers[@]}"; do
        if docker ps -a --format '{{.Names}}' | grep -q "^${container}$"; then
            status=$(docker ps --format "table {{.Names}}\t{{.Status}}" | grep $container | awk '{$1=""; print substr($0, 2)}')
            
            if docker ps --format '{{.Names}}' | grep -q "^${container}$"; then
                echo -e "${GREEN}✓ ${container}: Running${NC}"
            else
                echo -e "${RED}✗ ${container}: Not running${NC}"
            fi
        else
            echo -e "${RED}✗ ${container}: Not found${NC}"
        fi
    done
    
    echo ""
}

# Main execution
main() {
    # Check Docker containers first
    check_docker_containers
    
    # Initialize overall health status
    overall_status=0
    
    # Check each service
    check_service_health "Order Service" "$ORDER_SERVICE_URL" || overall_status=$?
    check_service_health "Product Service" "$PRODUCT_SERVICE_URL" || overall_status=$?
    check_service_health "Payment Service" "$PAYMENT_SERVICE_URL" || overall_status=$?
    
    # Print summary
    echo "================================"
    if [ $overall_status -eq 0 ]; then
        echo -e "${GREEN}All services are HEALTHY!${NC}"
        echo "================================"
        return 0
    else
        echo -e "${RED}One or more services are UNHEALTHY!${NC}"
        echo "================================"
        return 1
    fi
}

# Run main function
main
