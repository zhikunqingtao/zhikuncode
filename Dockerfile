# =============================================================================
# ZhikunCode — Multi-stage Production Docker Build
# Architecture: Single container (Java manages Python as subprocess)
# =============================================================================

# ---- Stage 1: Build Frontend ----
FROM node:20-alpine AS frontend-build
WORKDIR /build/frontend

COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci --ignore-scripts

COPY frontend/src ./src/
COPY frontend/index.html frontend/vite.config.ts frontend/tsconfig.json ./
COPY frontend/tsconfig.node.json frontend/postcss.config.js frontend/tailwind.config.ts ./
COPY frontend/.env.production ./.env.production
RUN npm run build

# ---- Stage 2: Build Backend ----
FROM eclipse-temurin:21-jdk AS backend-build
WORKDIR /build

# Cache Maven dependencies (layer caching optimization)
COPY backend/pom.xml ./backend/
COPY backend/.mvn ./backend/.mvn/
COPY backend/mvnw ./backend/
RUN cd backend && chmod +x mvnw && ./mvnw dependency:go-offline -B

# Build application JAR
COPY backend/src ./backend/src/
RUN cd backend && ./mvnw package -DskipTests -B \
    && mv target/ai-code-assistant-*.jar target/app.jar

# ---- Stage 3: Production Runtime ----
FROM eclipse-temurin:21-jre-jammy AS runtime

LABEL maintainer="ZhikunCode Team"
LABEL org.opencontainers.image.title="ZhikunCode"
LABEL org.opencontainers.image.description="AI-powered code assistant with multi-agent collaboration"
LABEL org.opencontainers.image.vendor="ZhikunCode"
LABEL org.opencontainers.image.source="https://github.com/zhikuncode/zhikuncode"

# Install runtime dependencies:
#   - python3 + venv: Python subprocess for code analysis
#   - ripgrep: GrepTool backend
#   - curl: healthcheck
#   - git: Git tools
#   - tree-sitter runtime handled by python venv
RUN apt-get update && \
    apt-get install -y --no-install-recommends \
        python3 python3-pip python3-venv \
        ripgrep curl git && \
    apt-get clean && \
    rm -rf /var/lib/apt/lists/*

# Create non-root user
RUN groupadd -r zhikun && useradd -r -g zhikun -d /app -s /bin/sh zhikun

WORKDIR /app

# Copy backend JAR (explicitly renamed in build stage)
COPY --from=backend-build /build/backend/target/app.jar ./app.jar

# Copy frontend build output (served by Spring Boot as static resources)
COPY --from=frontend-build /build/frontend/dist ./static/

# Copy python-service source (PythonProcessManager starts it as subprocess)
COPY python-service/src ./python-service/src/
COPY python-service/requirements.txt ./python-service/
COPY python-service/pyproject.toml ./python-service/

# Setup Python virtual environment
RUN python3 -m venv /app/python-service/.venv && \
    /app/python-service/.venv/bin/pip install --no-cache-dir --upgrade pip && \
    /app/python-service/.venv/bin/pip install --no-cache-dir -r /app/python-service/requirements.txt

# Create symlink so PythonProcessManager can resolve 'python' command
RUN ln -sf /app/python-service/.venv/bin/python /usr/local/bin/python

# Copy MCP capability registry
COPY configuration/ ./configuration/

# Copy entrypoint
COPY docker-entrypoint.sh ./
RUN chmod +x docker-entrypoint.sh

# Create data and log directories
RUN mkdir -p /app/data /app/workspace /app/log /app/log/debug /app/log/mcp && \
    chown -R zhikun:zhikun /app

USER zhikun

# ---- Environment defaults (overridable via docker-compose / .env) ----
ENV JAVA_OPTS="-Xms256m -Xmx1024m --enable-preview" \
    SPRING_PROFILES_ACTIVE="production" \
    PYTHON_SERVICE_PATH="/app/python-service" \
    PYTHONPATH="/app/python-service/src" \
    MCP_REGISTRY_PATH="/app/configuration/mcp/mcp_capability_registry.json" \
    ALLOW_PRIVATE_NETWORK="true" \
    LOG_DIR="/app/log"

EXPOSE 8080

# Health check using curl (installed above)
HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
    CMD curl -sf http://localhost:8080/actuator/health || exit 1

ENTRYPOINT ["./docker-entrypoint.sh"]
