# =============================================================================
# payment-service/Dockerfile
#
# Multi-stage build — keeps the final image small (JRE-only, no Maven/JDK).
#
# Stage 1 — deps:    resolve + cache all Maven dependencies (layer cache)
# Stage 2 — build:   compile and package the fat JAR
# Stage 3 — runtime: copy JAR into a minimal JRE 17 image, run as non-root
#
# Usage (from repo root):
#   docker build -t payment-service:latest -f payment-service/Dockerfile .
#
# Or let docker compose build it:  docker compose build payment-service
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
# All sibling POMs are needed because Maven's reactor reads the parent pom.xml
# and validates that every declared <module> directory exists.
COPY pom.xml                  ./pom.xml
COPY payment-service/pom.xml  ./payment-service/pom.xml
COPY order-service/pom.xml    ./order-service/pom.xml
COPY product-service/pom.xml  ./product-service/pom.xml
COPY gateway-service/pom.xml  ./gateway-service/pom.xml
COPY auth-service/pom.xml     ./auth-service/pom.xml

# Resolve (and cache locally) every dependency declared by payment-service.
# This layer is reused on rebuilds as long as no POM file changes.
RUN mvn dependency:go-offline \
        -pl payment-service \
        --no-transfer-progress \
        -q

# ── Stage 2: Build ────────────────────────────────────────────────────────────
FROM deps AS builder

# Copy source after dependencies are cached so a source-only change doesn't
# invalidate the expensive dependency-download layer.
COPY payment-service/src ./payment-service/src

# Package the fat JAR.
#   -pl payment-service — build only this module
#   --am                — also build any modules payment-service depends on
#   -DskipTests         — tests run in CI; skip here to keep the image build fast
RUN mvn package \
        -pl payment-service \
        --am \
        -DskipTests \
        --no-transfer-progress \
        -q

# ── Stage 3: Runtime ──────────────────────────────────────────────────────────
# eclipse-temurin:17-jre-jammy — Temurin JRE on Ubuntu 22.04 LTS (slim).
# No JDK, no Maven, no build tools in the final image.
FROM eclipse-temurin:17-jre-jammy AS runtime

# ── OCI standard image labels ──────────────────────────────────────────────────
LABEL org.opencontainers.image.title="payment-service"
LABEL org.opencontainers.image.description="Payment processing microservice — Spring Boot 3 / Java 17"
LABEL org.opencontainers.image.vendor="order-processing"
LABEL org.opencontainers.image.base.name="eclipse-temurin:17-jre-jammy"

# ── Non-root user ──────────────────────────────────────────────────────────────
# Running as root inside a container is a CIS/NIST violation and widens the
# blast radius of any container escape.  Create a dedicated system account.
RUN groupadd --system appgroup && \
    useradd  --system --gid appgroup --no-create-home appuser

WORKDIR /app

# Copy only the fat JAR from the builder stage — nothing else lands in the image.
COPY --from=builder \
     /workspace/payment-service/target/payment-service-0.0.1-SNAPSHOT.jar \
     payment-service.jar

# Hand ownership to the non-root user before switching.
RUN chown appuser:appgroup payment-service.jar

USER appuser

# Expose the port that matches server.port in application.yml / docker-compose.yml.
EXPOSE 8082

# Docker-native health check (works without docker-compose).
# /api/management/health is the Spring Boot Actuator health endpoint.
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
    CMD wget -qO- http://localhost:8082/api/management/health || exit 1

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
            "-jar", "payment-service.jar"]
