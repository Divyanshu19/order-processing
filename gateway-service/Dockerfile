# =============================================================================
# gateway-service/Dockerfile
#
# Multi-stage build — keeps the final image small (JRE-only, no Maven/JDK).
#
# Stage 1 — deps:    resolve + cache all Maven dependencies (layer cache)
# Stage 2 — build:   compile and package the fat JAR
# Stage 3 — runtime: copy JAR into a minimal JRE 17 image, run as non-root
#
# Usage (from repo root):
#   docker build -t gateway-service:latest -f gateway-service/Dockerfile .
#
# Or let docker compose build it automatically — see docker-compose.yml.
#
# Cache behaviour
# ───────────────
# Docker rebuilds from the first changed layer downward.
# By resolving dependencies (Stage 1) before copying sources, the heavy
# "download the internet" step is skipped on every source-only change.
# =============================================================================

# ── Stage 1: Dependency resolution (cache layer) ──────────────────────────────
# maven:3.9-eclipse-temurin-17 bundles Maven 3.9 + JDK 17 — no extra install.
FROM maven:3.9-eclipse-temurin-17 AS deps

WORKDIR /workspace

# Copy only POM files first so this layer is cached until a POM changes.
# Maven requires ALL module directories listed in the parent pom.xml to exist.
# We only need their pom.xml files — no src/ — because we build ONLY
# gateway-service.  Copying just the pom.xml satisfies the reactor without
# pulling in megabytes of source for the other four services.
COPY pom.xml                  ./pom.xml
COPY gateway-service/pom.xml  ./gateway-service/pom.xml
COPY order-service/pom.xml    ./order-service/pom.xml
COPY product-service/pom.xml  ./product-service/pom.xml
COPY payment-service/pom.xml  ./payment-service/pom.xml
COPY auth-service/pom.xml     ./auth-service/pom.xml

# Resolve (and cache locally) every dependency declared by gateway-service.
# This layer is reused on rebuilds as long as no POM file changes.
RUN mvn dependency:go-offline \
        -pl gateway-service \
        --no-transfer-progress \
        -q

# ── Stage 2: Build ────────────────────────────────────────────────────────────
FROM deps AS builder

# Copy source after dependencies are cached so a source-only change doesn't
# invalidate the expensive dependency-download layer.
COPY gateway-service/src ./gateway-service/src

# Package the gateway-service JAR.
#   -pl gateway-service — build ONLY the gateway-service module
#   --am                — also build any modules gateway-service depends on
#   -DskipTests         — tests run in CI; skip here to keep the image build fast
RUN mvn package \
        -pl gateway-service \
        --am \
        -DskipTests \
        --no-transfer-progress \
        -q

# ── Stage 3: Runtime ──────────────────────────────────────────────────────────
# eclipse-temurin:17-jre-jammy — Temurin JRE on Ubuntu 22.04 LTS (slim).
# No JDK, no Maven, no build tools in the final image.
FROM eclipse-temurin:17-jre-jammy AS runtime

# ── OCI standard image labels ──────────────────────────────────────────────────
LABEL org.opencontainers.image.title="gateway-service"
LABEL org.opencontainers.image.description="API Gateway (Spring Cloud Gateway / Reactive) — Spring Boot 3 / Java 17"
LABEL org.opencontainers.image.vendor="order-processing"
LABEL org.opencontainers.image.base.name="eclipse-temurin:17-jre-jammy"

# ── Non-root user ──────────────────────────────────────────────────────────────
# Running as root inside a container is a CIS/NIST violation and widens the
# blast radius of any container escape.  Create a dedicated system account.
RUN groupadd --system appgroup && \
    useradd  --system --gid appgroup --no-create-home appuser

WORKDIR /app

# Copy only the fat JAR from the builder stage — no JDK, no Maven in final image.
COPY --from=builder \
     /workspace/gateway-service/target/gateway-service-0.0.1-SNAPSHOT.jar \
     gateway-service.jar

# Hand ownership to the non-root user before switching.
RUN chown appuser:appgroup gateway-service.jar

USER appuser

# Expose the gateway port (matches server.port in application.yml / docker-compose.yml).
EXPOSE 8079

# Docker-native health check (works without docker-compose).
# Gateway uses Spring Boot Actuator at /management/health (no /api prefix —
# Spring Cloud Gateway runs on the Reactive / Netty stack, not servlet).
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
    CMD wget -qO- http://localhost:8079/management/health || exit 1

# JVM flags:
#   -XX:+UseContainerSupport   — honour Docker CPU + memory limits (default in JDK 11+,
#                                explicit here for clarity)
#   -XX:MaxRAMPercentage=75.0  — cap heap at 75 % of container RAM; leaves headroom
#                                for Metaspace, thread stacks, and off-heap buffers
#   -Djava.security.egd=...    — use /dev/urandom via the Solaris alias trick to avoid
#                                SecureRandom blocking on entropy-starved containers
ENTRYPOINT ["java", \
            "-XX:+UseContainerSupport", \
            "-XX:MaxRAMPercentage=75.0", \
            "-Djava.security.egd=file:/dev/./urandom", \
            "-jar", "gateway-service.jar"]
