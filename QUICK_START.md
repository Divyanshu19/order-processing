# Quick Start Guide

## 60-Second Startup

### Step 1: Start Infrastructure (20 seconds)
```bash
docker-compose up -d
```

### Step 2: Build Services (30 seconds)
```bash
mvn clean install -DskipTests
```

### Step 3: Start Services (Open 3 terminals)
```bash
# Terminal 1 - Order Service (Port 8080)
cd order-service && mvn spring-boot:run

# Terminal 2 - Product Service (Port 8081)
cd product-service && mvn spring-boot:run

# Terminal 3 - Payment Service (Port 8082)
cd payment-service && mvn spring-boot:run
```

### Step 4: Verify All Services Are Healthy
```bash
bash verify-health.sh
```

## Health Check URLs

| Service | URL |
|---------|-----|
| Order | http://localhost:8080/api/actuator/health |
| Product | http://localhost:8081/api/actuator/health |
| Payment | http://localhost:8082/api/actuator/health |

## Quick Tests

### Test Single Service
```bash
curl http://localhost:8080/api/actuator/health | jq '.status'
```

### Test All Services
```bash
for port in 8080 8081 8082; do
  echo "Port $port:"
  curl -s http://localhost:$port/api/actuator/health | jq '.status'
done
```

## Expected Output

✓ All services respond with `"status": "UP"`

```
{
  "status": "UP",
  "components": {
    "db": { "status": "UP" },
    "databaseHealth": { "status": "UP" },
    "kafka": { "status": "UP" },
    "kafkaHealth": { "status": "UP" },
    "livenessState": { "status": "UP" },
    "readinessState": { "status": "UP" }
  }
}
```

## What Was Implemented

✓ Database (PostgreSQL) health checks  
✓ Kafka broker connectivity checks  
✓ Service liveness probes (Kubernetes-compatible)  
✓ Service readiness probes (Kubernetes-compatible)  
✓ Detailed health status reporting  
✓ Custom health indicators per service  

## Health Endpoints Available

```
GET /api/actuator/health           # Full health report
GET /api/actuator/health/live      # Liveness probe
GET /api/actuator/health/ready     # Readiness probe
GET /api/actuator/info             # Service information
GET /api/actuator/metrics          # Service metrics
```

## Troubleshooting

### Services won't connect to Kafka/DB
```bash
# Check Docker containers
docker ps

# View logs
docker logs kafka-kraft
docker logs postgres-order
docker logs postgres-product
docker logs postgres-payment
```

### Health endpoint returns 503
```bash
# Get detailed error
curl http://localhost:8080/api/actuator/health | jq '.'

# Check service logs for specific errors
# Look for "Kafka health check failed" or "Database health check failed"
```

### Port already in use
```bash
# If ports 8080/8081/8082 are in use, modify service ports in:
# - order-service/src/main/resources/application.yml
# - product-service/src/main/resources/application.yml
# - payment-service/src/main/resources/application.yml

# Change "server.port" values
```

## Docker Compose Services

| Service | Port | Purpose |
|---------|------|---------|
| kafka-kraft | 9092 | Message broker |
| postgres-order | 5432 | Order database |
| postgres-product | 5433 | Product database |
| postgres-payment | 5434 | Payment database |

## Clean Up

```bash
# Stop services (Ctrl+C in each terminal)

# Stop and remove containers
docker-compose down

# Remove volumes (clean data)
docker-compose down -v
```

## Key Features

### Custom Health Indicators
- **KafkaHealthIndicator** - Tests broker connectivity with message send
- **DatabaseHealthIndicator** - Tests database connection availability

### Spring Actuator Endpoints
- Automatic database health checks
- Automatic Kafka health checks
- Kubernetes-compatible liveness/readiness probes

### Service Configuration
- Separate PostgreSQL database per service
- Shared Kafka broker across all services
- Consistent health check response format

## Documentation

- **STARTUP_GUIDE.md** - Complete step-by-step instructions
- **HEALTH_CHECK_CONFIGURATION.md** - Technical configuration details
- **IMPLEMENTATION_SUMMARY.md** - What was changed and why
- **QUICK_START.md** - This file

## Verify Everything Works

```bash
# 1. Check containers
docker ps

# 2. Verify all services started
sleep 30  # Give services time to initialize

# 3. Run verification script
bash verify-health.sh

# Should output:
# ✓ Order Service is HEALTHY
# ✓ Product Service is HEALTHY  
# ✓ Payment Service is HEALTHY
# All services are HEALTHY!
```

## Success Indicators

When all services are healthy:
- Docker containers all show "healthy" status
- All services start without error logs
- Health endpoints return HTTP 200
- Health status shows "UP" for all components
- Kafka health indicator shows "UP"
- Database health indicator shows "UP"

---

**You're all set!** All services are now configured with comprehensive health checks for Kafka/DB connectivity and Kubernetes-compatible probes.
