# Health Check Implementation - Complete Guide

## 🎯 Overview

All three microservices in the Order Processing System have been configured with comprehensive health checks for:

- ✅ **Database Connectivity** (PostgreSQL) - Verifies database connections are active
- ✅ **Kafka Connectivity** - Verifies Kafka broker is reachable
- ✅ **Service Liveness** - Kubernetes-compatible liveness probes
- ✅ **Service Readiness** - Kubernetes-compatible readiness probes

---

## 📚 Documentation Index

### Getting Started
1. **[QUICK_START.md](QUICK_START.md)** ⚡
   - 60-second startup guide
   - Essential commands only
   - Quick verification steps

2. **[STARTUP_GUIDE.md](STARTUP_GUIDE.md)** 📖
   - Step-by-step detailed instructions
   - Infrastructure setup
   - Service startup process
   - Troubleshooting guide

### Technical Details
3. **[HEALTH_CHECK_CONFIGURATION.md](HEALTH_CHECK_CONFIGURATION.md)** 🔧
   - Configuration explanations
   - Health indicator details
   - Endpoint specifications
   - Customization guide
   - Kubernetes integration

4. **[IMPLEMENTATION_SUMMARY.md](IMPLEMENTATION_SUMMARY.md)** 📋
   - What was changed
   - Files modified and created
   - Architecture overview
   - Feature descriptions

5. **[CHANGES_SUMMARY.txt](CHANGES_SUMMARY.txt)** 📝
   - Complete file listing
   - Configuration changes
   - Service specifications
   - Success criteria

### Scripts & Examples
6. **[health-check-examples.sh](health-check-examples.sh)** 🔍
   - Curl command examples
   - Expected responses
   - Testing scenarios

7. **[verify-health.sh](verify-health.sh)** ✓
   - Automated health verification
   - Docker container checks
   - Service endpoint testing
   - Color-coded output

8. **[test-health-endpoints.sh](test-health-endpoints.sh)** 🧪
   - Comprehensive endpoint testing
   - Component-level checks
   - Detailed status reporting

---

## 🚀 Quick Start (2 minutes)

### 1. Start Infrastructure
```bash
docker-compose up -d
```

### 2. Build Services
```bash
mvn clean install -DskipTests
```

### 3. Start Services (3 terminals)
```bash
# Terminal 1
cd order-service && mvn spring-boot:run

# Terminal 2
cd product-service && mvn spring-boot:run

# Terminal 3
cd payment-service && mvn spring-boot:run
```

### 4. Verify Health
```bash
bash verify-health.sh
```

---

## 🏥 Health Endpoints

### Service URLs

| Service | Health Endpoint |
|---------|-----------------|
| Order | http://localhost:8080/api/actuator/health |
| Product | http://localhost:8081/api/actuator/health |
| Payment | http://localhost:8082/api/actuator/health |

### Available Endpoints (per service)

| Endpoint | Purpose | Use Case |
|----------|---------|----------|
| `/api/actuator/health` | Full health report | Complete status check |
| `/api/actuator/health/live` | Liveness probe | Kubernetes liveness checks |
| `/api/actuator/health/ready` | Readiness probe | Load balancer traffic control |
| `/api/actuator/info` | Service information | Version & metadata |
| `/api/actuator/metrics` | Performance metrics | Monitoring & observability |

---

## 📊 Health Components

Each service reports health status for:

```
┌─────────────────────────────────────────┐
│          Service Health Report          │
├─────────────────────────────────────────┤
│ ✓ Database (db)                         │
│ ✓ Database Custom (databaseHealth)      │
│ ✓ Kafka (kafka)                         │
│ ✓ Kafka Custom (kafkaHealth)            │
│ ✓ Liveness State (livenessState)        │
│ ✓ Readiness State (readinessState)      │
└─────────────────────────────────────────┘
```

---

## 🔍 Testing Examples

### Test Single Service
```bash
# Order Service
curl http://localhost:8080/api/actuator/health | jq '.status'

# Expected output: "UP"
```

### Test All Services
```bash
# Run verification script
bash verify-health.sh

# Expected output:
# ✓ Order Service is HEALTHY
# ✓ Product Service is HEALTHY
# ✓ Payment Service is HEALTHY
# All services are HEALTHY!
```

### Test Specific Component
```bash
# Check Kafka health
curl http://localhost:8080/api/actuator/health/kafkaHealth | jq '.'

# Check Database health
curl http://localhost:8080/api/actuator/health/databaseHealth | jq '.'
```

### Monitor Health Over Time
```bash
# Watch health status every 5 seconds
watch -n 5 'curl -s http://localhost:8080/api/actuator/health | jq ".status"'
```

---

## 🔧 Configuration Summary

### Database Connections
```yaml
Order Service:    localhost:5432 (order_db)
Product Service:  localhost:5433 (product_db)
Payment Service:  localhost:5434 (payment_db)
```

### Kafka Configuration
```yaml
Bootstrap Servers: localhost:9092
All Services Connected
Separate Consumer Groups (service-name-group)
JSON Serialization Enabled
```

### Service Ports
```yaml
Order Service:    8080
Product Service:  8081
Payment Service:  8082
All use /api context path
```

---

## 📁 Implementation Files

### Configuration Files (Modified)
```
✏️  order-service/src/main/resources/application.yml
✏️  product-service/src/main/resources/application.yml
✏️  payment-service/src/main/resources/application.yml
```

### Health Indicator Code (Created - 6 files)
```
📝 order-service/src/main/java/com/order/processing/order/health/
   ├── KafkaHealthIndicator.java
   └── DatabaseHealthIndicator.java

📝 product-service/src/main/java/com/order/processing/product/health/
   ├── KafkaHealthIndicator.java
   └── DatabaseHealthIndicator.java

📝 payment-service/src/main/java/com/order/processing/payment/health/
   ├── KafkaHealthIndicator.java
   └── DatabaseHealthIndicator.java
```

### Documentation (Created - 5 files)
```
📖 STARTUP_GUIDE.md
📖 HEALTH_CHECK_CONFIGURATION.md
📖 IMPLEMENTATION_SUMMARY.md
📖 QUICK_START.md
📖 CHANGES_SUMMARY.txt
```

### Scripts (Created - 3 files)
```
🔧 verify-health.sh
🔧 test-health-endpoints.sh
🔧 health-check-examples.sh
```

---

## ✨ Key Features

### ✅ Custom Health Indicators
- **KafkaHealthIndicator**: Sends test message to verify broker connectivity
- **DatabaseHealthIndicator**: Tests connection pool availability

### ✅ Spring Actuator Integration
- Automatic health checks via Spring Boot Actuator
- Detailed health reporting with all components
- HTTP status codes (200 = UP, 503 = DOWN)

### ✅ Kubernetes Ready
- Liveness probe endpoint (`/health/live`)
- Readiness probe endpoint (`/health/ready`)
- Compatible with orchestration tools

### ✅ Production Grade
- Connection pool management
- Timeout handling
- Comprehensive logging
- Graceful degradation

### ✅ Easy Troubleshooting
- Detailed error messages
- Component-level status reporting
- Test scripts provided

---

## 🚨 Troubleshooting

### Issue: Health endpoint returns 503

**Solution:**
1. Check which component failed: `curl http://localhost:8080/api/actuator/health | jq '.components'`
2. Verify Docker containers: `docker ps`
3. Check specific service logs: `docker logs {container-name}`

### Issue: Services won't start

**Solution:**
1. Verify Docker is running: `docker ps`
2. Check container health: `docker ps --format "table {{.Names}}\t{{.Status}}"`
3. Review startup logs for connection errors

### Issue: Kafka health always DOWN

**Solution:**
```bash
# Verify Kafka is running
docker ps | grep kafka

# Check Kafka logs
docker logs kafka-kraft

# Test Kafka connectivity
docker exec kafka-kraft kafka-broker-api-versions.sh --bootstrap-server=localhost:9092
```

### Issue: Database health always DOWN

**Solution:**
```bash
# Verify PostgreSQL is running
docker ps | grep postgres-order

# Test database connection
psql -h localhost -p 5432 -U order_user -d order_db -c "SELECT 1"
```

---

## 📊 Expected Response Example

### Healthy Service (HTTP 200)
```json
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
      "status": "UP"
    },
    "kafkaHealth": {
      "status": "UP",
      "details": {
        "status": "Kafka broker is reachable"
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
```

### Unhealthy Service (HTTP 503)
```json
{
  "status": "DOWN",
  "components": {
    "kafkaHealth": {
      "status": "DOWN",
      "details": {
        "error": "Connection refused to broker"
      }
    },
    ...other components...
  }
}
```

---

## 🎯 Success Criteria

All of the following should be true:

- ✅ All three services start without errors
- ✅ Health endpoints return HTTP 200
- ✅ Health status shows "UP" for all components
- ✅ Kafka health indicator shows "UP"
- ✅ Database health indicator shows "UP"
- ✅ Services are Kubernetes-ready with liveness/readiness probes

---

## 📚 Reading Order

**For Quick Setup:**
1. [QUICK_START.md](QUICK_START.md) - Get started in 60 seconds

**For Complete Setup:**
1. [STARTUP_GUIDE.md](STARTUP_GUIDE.md) - Step-by-step instructions
2. [HEALTH_CHECK_CONFIGURATION.md](HEALTH_CHECK_CONFIGURATION.md) - Technical details
3. [health-check-examples.sh](health-check-examples.sh) - Testing examples

**For Development:**
1. [IMPLEMENTATION_SUMMARY.md](IMPLEMENTATION_SUMMARY.md) - What was implemented
2. [CHANGES_SUMMARY.txt](CHANGES_SUMMARY.txt) - File-by-file changes
3. Source code in health package directories

**For Troubleshooting:**
- [STARTUP_GUIDE.md](STARTUP_GUIDE.md#troubleshooting) - Troubleshooting section
- [HEALTH_CHECK_CONFIGURATION.md](HEALTH_CHECK_CONFIGURATION.md#troubleshooting-health-check-issues) - Technical troubleshooting

---

## 🔗 Related Commands

```bash
# Start everything
docker-compose up -d && mvn clean install -DskipTests

# View service status
docker ps

# Check logs
docker logs kafka-kraft
docker logs postgres-order

# Run tests
bash verify-health.sh
bash test-health-endpoints.sh

# View metrics
curl http://localhost:8080/api/actuator/metrics

# Cleanup
docker-compose down -v
```

---

## 📞 Support

For issues or questions:
1. Check [STARTUP_GUIDE.md](STARTUP_GUIDE.md#troubleshooting)
2. Review [health-check-examples.sh](health-check-examples.sh)
3. Run `bash verify-health.sh` for diagnostic output
4. Check Docker logs for detailed error messages

---

## ✅ Implementation Status

| Component | Status | Files |
|-----------|--------|-------|
| Order Service | ✅ Complete | 3 (config + 2 health indicators) |
| Product Service | ✅ Complete | 3 (config + 2 health indicators) |
| Payment Service | ✅ Complete | 3 (config + 2 health indicators) |
| Documentation | ✅ Complete | 5 guides |
| Test Scripts | ✅ Complete | 3 scripts |
| **Total** | **✅ Complete** | **17 files** |

---

**Last Updated:** 2024  
**Status:** ✅ Production Ready  
**Services:** 3/3  
**Health Checks:** Database + Kafka + Kubernetes Probes
