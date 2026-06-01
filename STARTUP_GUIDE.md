# Microservices Startup Guide

This document provides comprehensive instructions for starting the Order Processing System and verifying all services are healthy.

## Prerequisites

- Docker and Docker Compose installed
- Java 17+ installed
- Maven installed

## Step 1: Start Infrastructure (Kafka & Databases)

```bash
docker-compose up -d
```

This will start:
- Kafka (port 9092)
- PostgreSQL for Order Service (port 5432)
- PostgreSQL for Product Service (port 5433)
- PostgreSQL for Payment Service (port 5434)

**Verify containers are running:**
```bash
docker ps
```

**Check container health:**
```bash
docker ps --format "table {{.Names}}\t{{.Status}}"
```

Wait for all containers to show as "healthy" before proceeding.

## Step 2: Build All Services

```bash
# From the root project directory
mvn clean install -DskipTests
```

## Step 3: Start Microservices

Each service should be started in separate terminal windows:

### Terminal 1 - Order Service (Port 8080)
```bash
cd order-service
mvn spring-boot:run
```

### Terminal 2 - Product Service (Port 8081)
```bash
cd product-service
mvn spring-boot:run
```

### Terminal 3 - Payment Service (Port 8082)
```bash
cd payment-service
mvn spring-boot:run
```

## Step 4: Verify Services Are Running

### 4.1 Check Order Service Health
```bash
curl http://localhost:8080/api/actuator/health
```

Expected Response:
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
      "status": "UP",
      "details": {
        "status": "Kafka broker is reachable"
      }
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
```

### 4.2 Check Product Service Health
```bash
curl http://localhost:8081/api/actuator/health
```

### 4.3 Check Payment Service Health
```bash
curl http://localhost:8082/api/actuator/health
```

## Step 5: Verify All Components

### Check Kafka Connection
```bash
# From any service health endpoint, verify kafkaHealth.status = "UP"
```

### Check Database Connections
```bash
# Verify databaseHealth.status = "UP" for each service
```

### Check Service Readiness
```bash
curl http://localhost:8080/api/actuator/health/readiness
curl http://localhost:8081/api/actuator/health/readiness
curl http://localhost:8082/api/actuator/health/readiness
```

All should respond with status "UP"

## Health Check Endpoints

Each service exposes the following health-related endpoints:

| Endpoint | Purpose |
|----------|---------|
| `/actuator/health` | Full health report with all components |
| `/actuator/health/live` | Liveness probe (is service running?) |
| `/actuator/health/ready` | Readiness probe (is service ready for traffic?) |
| `/actuator/info` | Service information |
| `/actuator/metrics` | Service metrics |

## Troubleshooting

### Services Won't Start

**Problem:** Connection refused to Kafka or Database
- Verify Docker containers are running and healthy
- Check port conflicts (5432, 5433, 5434, 9092)
- Verify firewall settings

**Command to diagnose:**
```bash
docker logs kafka-kraft
docker logs postgres-order
docker logs postgres-product
docker logs postgres-payment
```

### Health Endpoint Returns DOWN

**For Kafka health issues:**
- Check if Kafka container is running: `docker ps | grep kafka`
- Verify bootstrap servers in application.yml: `localhost:9092`
- Check Kafka logs: `docker logs kafka-kraft`

**For Database health issues:**
- Verify correct port and credentials in application.yml
- Check database logs: `docker logs postgres-order` (or postgres-product, postgres-payment)
- Test connection manually using psql:
  ```bash
  psql -h localhost -p 5432 -U order_user -d order_db -c "SELECT 1"
  ```

### Slow Startup

Health checks may take a few seconds to complete initially. This is normal as Spring Boot is initializing connections to Kafka and the database.

## Shutdown

### Stop Services
Press Ctrl+C in each terminal window running the services.

### Stop Docker Containers
```bash
docker-compose down
```

### Remove Volumes (Clean Data)
```bash
docker-compose down -v
```

## Health Check Response Codes

- **200 (OK):** All systems healthy
- **503 (Service Unavailable):** One or more components unhealthy
- **500 (Internal Server Error):** Unexpected error

## Monitoring

Watch the logs for any errors related to:
- Kafka connection failures
- Database connection pool exhaustion
- Schema initialization issues

All health indicators log their status at INFO and ERROR levels for easy monitoring.

## Next Steps

Once all services are running and healthy:
1. Test inter-service communication
2. Verify Kafka message flow
3. Run integration tests
4. Deploy to production environment

---

For more information, consult the README.md in the project root.
