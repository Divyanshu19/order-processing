# =============================================================================
# gateway-service/Dockerfile
#
# Multi-stage build — keeps the final image small (JRE-only, no Maven/JDK).
#
# Stage 1 — Build:  uses Maven + JDK 17 to compile and package the JAR
# Stage 2 — Runtime: copies only the JAR into a minimal JRE 17 image
#
# Usage (from repo root):
#   docker build -t gateway-service:latest ./gateway-service
#
# Or let docker compose build it automatically — see docker-compose.yml.
# =============================================================================

# ── Stage 1: Build ────────────────────────────────────────────────────────────
# maven:3.9-eclipse-temurin-17 bundles both Maven 3.9 and JDK 17 — no separate
# Maven installation needed.
FROM maven:3.9-eclipse-temurin-17 AS builder

WORKDIR /workspace

# ── Copy the full multi-module reactor skeleton ───────────────────────────────
# Maven requires ALL module directories listed in the parent pom.xml to exist
# (product-service, order-service, payment-service, gateway-service).
# We only need their pom.xml files — no src/ — because we will build ONLY
# gateway-service.  Copying just the pom.xml satisfies the reactor without
# pulling in megabytes of source for the other three services.

# Parent POM
COPY pom.xml ./pom.xml

# Sibling module POMs (stub entries — not compiled, just present for reactor)
COPY order-service/pom.xml    ./order-service/pom.xml
COPY product-service/pom.xml  ./product-service/pom.xml
COPY payment-service/pom.xml  ./payment-service/pom.xml
COPY auth-service/pom.xml     ./auth-service/pom.xml

# Gateway-service: full pom + source (this is what we actually build)
COPY gateway-service/pom.xml  ./gateway-service/pom.xml
COPY gateway-service/src      ./gateway-service/src

# Package the gateway-service JAR.
# -pl gateway-service  — build ONLY the gateway-service module
# -DskipTests          — no runtime environment inside Docker build
RUN mvn package \
        -pl gateway-service \
        -DskipTests \
        --no-transfer-progress \
        -q

# ── Stage 2: Runtime ──────────────────────────────────────────────────────────
FROM eclipse-temurin:17-jre

LABEL maintainer="order-processing"
LABEL service="gateway-service"

WORKDIR /app

# Copy only the fat JAR from the builder stage — no JDK, no Maven in final image
COPY --from=builder \
     /workspace/gateway-service/target/gateway-service-0.0.1-SNAPSHOT.jar \
     gateway-service.jar

# Expose the gateway port (matches server.port in application.yml)
EXPOSE 8079

# Run with sensible container JVM flags:
#   -XX:+UseContainerSupport        — respect Docker CPU/memory limits
#   -XX:MaxRAMPercentage=75.0       — use at most 75% of container RAM for heap
#   -Djava.security.egd=...         — faster startup (avoids /dev/random blocking)
ENTRYPOINT ["java", \
            "-XX:+UseContainerSupport", \
            "-XX:MaxRAMPercentage=75.0", \
            "-Djava.security.egd=file:/dev/./urandom", \
            "-jar", "gateway-service.jar"]
