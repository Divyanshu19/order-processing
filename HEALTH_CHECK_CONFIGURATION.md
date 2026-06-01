# Health Check Configuration Guide

## Overview

Each microservice in the Order Processing System has been configured with comprehensive health checks for:
1. **Database Connectivity** (PostgreSQL)
2. **Kafka Connectivity** 
3. **Service Liveness** (is the service running?)
4. **Service Readiness** (is the service ready to handle requests?)

## Configuration Details

### Application Configuration Changes

All three services (Order, Product, Payment) have been updated with the following management configuration:

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics
  endpoint:
    health:
      show-details: always
  health:
    db:
      enabled: true
    kafka:
      enabled: true
    livenessState:
      enabled: true
    readinessState:
      enabled: true
```

### What This Configuration Does:

| Configuration | Purpose |
|---------------|---------|
| `endpoints.web.exposure.include` | Exposes health, info, and metrics endpoints |
| `endpoint.health.show-details` | Shows detailed health information for all components |
| `health.db.enabled` | Enables default database health indicator |
| `health.kafka.enabled` | Enables default Kafka health indicator |
| `health.livenessState.enabled` | Enables Kubernetes-style liveness probe |
| `health.readinessState.enabled` | Enables Kubernetes-style readiness probe |

## Custom Health Indicators

Each service includes two custom health indicators:

### 1. KafkaHealthIndicator
**Location:** `{service}/src/main/java/com/order/processing/{service}/health/KafkaHealthIndicator.java`

**Purpose:** Verifies connection to Kafka broker

**How it works:**
- Attempts to send a test message to the Kafka broker
- Reports `UP` if successful
- Reports `DOWN` if the broker is unreachable
- Includes error details for troubleshooting

**Configuration:**
```java
@Component("kafkaHealth")
@RequiredArgsConstructor
public class KafkaHealthIndicator implements HealthIndicator {
    private final KafkaTemplate<String, String> kafkaTemplate;
    
    @Override
    public Health health() {
        // Attempts Kafka operation and returns UP/DOWN status
    }
}
```

### 2. DatabaseHealthIndicator
**Location:** `{service}/src/main/java/com/order/processing/{service}/health/DatabaseHealthIndicator.java`

**Purpose:** Verifies connection to PostgreSQL database

**How it works:**
- Attempts to open a database connection
- Checks if the connection is active
- Reports `UP` if the connection succeeds
- Reports `DOWN` if the connection fails

**Configuration:**
```java
@Component("databaseHealth")
@RequiredArgsConstructor
public class DatabaseHealthIndicator implements HealthIndicator {
    private final DataSource dataSource;
    
    @Override
    public Health health() {
        // Opens connection and verifies database availability
    }
}
```

## Health Check Endpoints

All services expose health information via the `/actuator/health` endpoint with the following sub-endpoints:

### 1. Full Health Report
**Endpoint:** `/api/actuator/health`

**Example Response:**
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

### 2. Liveness Probe (Kubernetes Compatible)
**Endpoint:** `/api/actuator/health/live`

**Purpose:** Indicates if the service process is running

**Example Response:**
```json
{
  "status": "UP",
  "components": {
    "livenessState": {
      "status": "UP"
    }
  }
}
```

### 3. Readiness Probe (Kubernetes Compatible)
**Endpoint:** `/api/actuator/health/ready`

**Purpose:** Indicates if the service is ready to accept traffic

**Example Response:**
```json
{
  "status": "UP",
  "components": {
    "readinessState": {
      "status": "UP"
    }
  }
}
```

## Service Port Configuration

Each service runs on a different port:

| Service | Port | Health Endpoint |
|---------|------|-----------------|
| Order Service | 8080 | `http://localhost:8080/api/actuator/health` |
| Product Service | 8081 | `http://localhost:8081/api/actuator/health` |
| Payment Service | 8082 | `http://localhost:8082/api/actuator/health` |

All services use the `/api` context path, so the full endpoint path is `/api/actuator/health`.

## Database Connection Configuration

Each service connects to its own PostgreSQL database:

| Service | Database | Port | User | Password |
|---------|----------|------|------|----------|
| Order Service | order_db | 5432 | order_user | order_password |
| Product Service | product_db | 5433 | product_user | product_password |
| Payment Service | payment_db | 5434 | payment_user | payment_password |

Configuration in `application.yml`:
```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/order_db
    username: order_user
    password: order_password
    driver-class-name: org.postgresql.Driver
```

## Kafka Connection Configuration

All services connect to the same Kafka broker:

| Configuration | Value |
|---------------|-------|
| Bootstrap Servers | localhost:9092 |
| Auto Create Topics | Enabled |
| Replication Factor | 1 (single broker) |

Configuration in `application.yml`:
```yaml
spring:
  kafka:
    bootstrap-servers: localhost:9092
    producer:
      key-serializer: org.apache.kafka.common.serialization.StringSerializer
      value-serializer: org.springframework.kafka.support.serializer.JsonSerializer
    consumer:
      bootstrap-servers: localhost:9092
      group-id: {service-name}-group
      key-deserializer: org.apache.kafka.common.serialization.StringDeserializer
      value-deserializer: org.springframework.kafka.support.serializer.JsonDeserializer
      auto-offset-reset: earliest
```

## Health Check HTTP Status Codes

| Status Code | Meaning | Health Status |
|------------|---------|---------------|
| 200 | OK | All components UP |
| 503 | Service Unavailable | One or more components DOWN |
| 500 | Internal Server Error | Unexpected error during health check |

## Monitoring and Logging

### Log Levels

Health check operations are logged at:
- **INFO level:** Successful health checks
- **ERROR level:** Failed health checks with error details

Example logs:
```
INFO  - Database health check passed
DEBUG - Attempting Kafka connection...
ERROR - Kafka health check failed: Connection refused
```

### Metrics

The following metrics are exposed via `/actuator/metrics`:
- JVM metrics (memory, GC, threads)
- HTTP metrics (requests, response times)
- Database connection pool metrics
- Kafka producer/consumer metrics

## Integration with Kubernetes

The health endpoints are compatible with Kubernetes probes:

### Liveness Probe Configuration
```yaml
livenessProbe:
  httpGet:
    path: /api/actuator/health/live
    port: 8080
  initialDelaySeconds: 30
  periodSeconds: 10
```

### Readiness Probe Configuration
```yaml
readinessProbe:
  httpGet:
    path: /api/actuator/health/ready
    port: 8080
  initialDelaySeconds: 10
  periodSeconds: 5
```

## Customization

To modify health check behavior:

### 1. Add Custom Indicators
Create a new class implementing `HealthIndicator`:
```java
@Component("customHealth")
public class CustomHealthIndicator implements HealthIndicator {
    @Override
    public Health health() {
        // Your custom health logic
        return Health.up().build();
    }
}
```

### 2. Configure Health Indicator Properties
Add to `application.yml`:
```yaml
management:
  health:
    custom:
      enabled: true
```

### 3. Adjust Timeout Values
Configure connection timeouts:
```yaml
spring:
  datasource:
    hikari:
      connection-timeout: 20000
      maximum-pool-size: 10
```

## Troubleshooting Health Check Issues

### Issue: Health endpoint returns 503 (Service Unavailable)

**Cause:** One or more health components are DOWN

**Solution:**
1. Check the detailed health response to identify which component failed
2. Review the component-specific logs
3. Verify the external service (Kafka/Database) is running

### Issue: Health check slow response

**Cause:** Timeouts or slow external services

**Solution:**
1. Check Docker container resource usage
2. Verify network connectivity
3. Consider adjusting timeout values

### Issue: Kafka health always DOWN

**Cause:** 
- Kafka broker not running
- Bootstrap server configuration incorrect
- Network connectivity issue

**Solution:**
```bash
# Verify Kafka is running
docker ps | grep kafka

# Test Kafka connectivity
docker exec kafka-kraft kafka-broker-api-versions.sh --bootstrap-server=localhost:9092
```

### Issue: Database health always DOWN

**Cause:**
- PostgreSQL service not running
- Incorrect database credentials
- Database not initialized

**Solution:**
```bash
# Verify PostgreSQL is running
docker ps | grep postgres

# Test database connectivity
psql -h localhost -p 5432 -U order_user -d order_db -c "SELECT 1"
```

## Best Practices

1. **Regular Monitoring:** Monitor health endpoint responses in production
2. **Alert on DOWN Status:** Set up alerts when health status becomes DOWN
3. **Slow Response Tracking:** Track health check response times to detect degradation
4. **Graceful Shutdown:** Use readiness probes to drain traffic before shutdown
5. **Resource Monitoring:** Monitor database connection pool and Kafka consumer lag

## Performance Considerations

- Health checks complete within 100-500ms under normal conditions
- Kafka health check timeout: 5 seconds
- Database health check timeout: depends on connection pool settings
- All health checks run asynchronously from main request handling

## Summary

The health check configuration ensures:
✓ Services cannot start without verifying external dependencies
✓ Kubernetes can automatically restart unhealthy services
✓ Load balancers can route traffic only to healthy instances
✓ Operations teams have visibility into system health
✓ Debugging is simplified with detailed error messages
