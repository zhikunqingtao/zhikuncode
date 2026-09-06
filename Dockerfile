# =============================================================================
# ZhikunCode — Multi-stage Production Docker Build
# Architecture: Single container (Java manages Python as subprocess)
# =============================================================================

# ---- Stage 1: Build Frontend ----
FROM node:22-alpine AS frontend-build
WORKDIR /build/frontend

ARG NPM_REGISTRY=https://registry.npmjs.org/
COPY frontend/package.json frontend/package-lock.json ./
RUN case "${NPM_REGISTRY}" in https://*) ;; \
        *) echo "NPM_REGISTRY must use HTTPS" >&2; exit 2 ;; \
    esac && \
    npm ci --ignore-scripts \
    --registry="${NPM_REGISTRY}" \
    --replace-registry-host=always

COPY frontend/src ./src/
COPY frontend/index.html frontend/vite.config.ts frontend/tsconfig.json ./
COPY frontend/tsconfig.node.json frontend/postcss.config.js frontend/tailwind.config.ts ./
COPY frontend/.env.production ./.env.production
RUN npm run build

# ---- Stage 2: Build Backend ----
FROM eclipse-temurin:21-jdk AS backend-build
WORKDIR /build

ARG MAVEN_REPOSITORY_URL=https://repo.maven.apache.org/maven2

# Cache Maven dependencies (layer caching optimization)
COPY backend/pom.xml ./backend/
COPY backend/.mvn ./backend/.mvn/
COPY backend/mvnw ./backend/
RUN repository="${MAVEN_REPOSITORY_URL%/}" && \
    case "$repository" in https://*) ;; \
        *) echo "MAVEN_REPOSITORY_URL must use HTTPS" >&2; exit 2 ;; \
    esac && \
    repository_path="${repository#https://}" && \
    case "$repository_path" in ""|*[!A-Za-z0-9._~:/-]*) \
        echo "MAVEN_REPOSITORY_URL contains unsupported characters" >&2; exit 2 ;; \
    esac && \
    sed -i \
        "s|https://repo.maven.apache.org/maven2|${repository}|g" \
        backend/.mvn/wrapper/maven-wrapper.properties && \
    grep -Fq "distributionUrl=${repository}/" \
        backend/.mvn/wrapper/maven-wrapper.properties && \
    grep -Fq "wrapperUrl=${repository}/" \
        backend/.mvn/wrapper/maven-wrapper.properties && \
    if [ "$repository" != 'https://repo.maven.apache.org/maven2' ]; then \
        mkdir -p /root/.m2; \
        printf '%s\n' \
            '<?xml version="1.0" encoding="UTF-8"?>' \
            '<settings xmlns="http://maven.apache.org/SETTINGS/1.0.0">' \
            '  <mirrors>' \
            '    <mirror>' \
            '      <id>docker-build-mirror</id>' \
            '      <name>Configured build mirror</name>' \
            "      <url>${repository}</url>" \
            '      <mirrorOf>*</mirrorOf>' \
            '    </mirror>' \
            '  </mirrors>' \
            '</settings>' \
            > /root/.m2/settings.xml; \
    fi
RUN cd backend && chmod +x mvnw && ./mvnw dependency:go-offline -B

# Build application JAR
COPY backend/src ./backend/src/
RUN cd backend && ./mvnw package -DskipTests -B \
    && mv target/ai-code-assistant-*.jar target/app.jar

# Official GitHub MCP binary, pinned to a release tag.
FROM ghcr.io/github/github-mcp-server:v1.11.0 AS github-mcp

# ---- Stage 3: Production Runtime ----
# Ubuntu 24.04 (noble) provides Python 3.12, matching pyproject.toml's
# supported range (>=3.11,<3.13). Jammy's Python 3.10 is not supported.
FROM eclipse-temurin:21-jre-noble AS runtime

ARG UBUNTU_MIRROR_HOST=
ARG PIP_INDEX_URL=https://pypi.org/simple

LABEL maintainer="ZhikunCode Team"
LABEL org.opencontainers.image.title="ZhikunCode"
LABEL org.opencontainers.image.description="AI-powered code assistant with multi-agent collaboration"
LABEL org.opencontainers.image.vendor="ZhikunCode"
LABEL org.opencontainers.image.source="https://github.com/zhikuncode/zhikuncode"

# Install runtime dependencies:
#   - python3 + venv + libmagic: Python subprocess for analysis/file inspection
#   - ripgrep: GrepTool backend
#   - curl: healthcheck
#   - git: Git tools
#   - tree-sitter runtime handled by python venv
RUN if [ -n "${UBUNTU_MIRROR_HOST}" ]; then \
        case "${UBUNTU_MIRROR_HOST}" in *[!A-Za-z0-9.-]*) \
            echo "UBUNTU_MIRROR_HOST must be a hostname" >&2; exit 2 ;; \
        esac; \
        mirror_replaced=false; \
        for source_file in /etc/apt/sources.list /etc/apt/sources.list.d/ubuntu.sources; do \
            if [ -f "$source_file" ] && grep -Eq 'https?://(archive|security|ports)\.ubuntu\.com' "$source_file"; then \
                sed -E -i \
                    "s#https?://(archive\.ubuntu\.com|security\.ubuntu\.com|ports\.ubuntu\.com)#https://${UBUNTU_MIRROR_HOST}#g" \
                    "$source_file"; \
                grep -Fq "https://${UBUNTU_MIRROR_HOST}/ubuntu" "$source_file" \
                    || { echo "Ubuntu mirror replacement verification failed" >&2; exit 2; }; \
                mirror_replaced=true; \
            fi; \
        done; \
        [ "$mirror_replaced" = true ] \
            || { echo "No supported Ubuntu source entry found" >&2; exit 2; }; \
    fi && \
    apt-get update && \
    apt-get install -y --no-install-recommends \
        python3 python3-pip python3-venv libmagic1 \
        ripgrep curl git && \
    python3 -c 'import sys; assert (3, 11) <= sys.version_info[:2] < (3, 13), sys.version' && \
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
COPY python-service/requirements.lock ./python-service/
COPY python-service/requirements.txt ./python-service/
COPY python-service/pyproject.toml ./python-service/

# Setup Python virtual environment
RUN case "${PIP_INDEX_URL}" in https://*) ;; \
        *) echo "PIP_INDEX_URL must use HTTPS" >&2; exit 2 ;; \
    esac && \
    python3 -m venv /app/python-service/.venv && \
    /app/python-service/.venv/bin/pip install --no-cache-dir \
        --index-url "${PIP_INDEX_URL}" pip==24.0 && \
    /app/python-service/.venv/bin/pip install --no-cache-dir \
        --index-url "${PIP_INDEX_URL}" \
        -r /app/python-service/requirements.lock

# Alibaba Cloud Ops has stricter FastMCP/Pydantic pins than the application,
# so isolate it from the Python analysis service.
RUN python3 -m venv /app/mcp-servers/alibaba-cloud-ops && \
    /app/mcp-servers/alibaba-cloud-ops/bin/pip install --no-cache-dir \
        --index-url "${PIP_INDEX_URL}" pip==24.0 && \
    /app/mcp-servers/alibaba-cloud-ops/bin/pip install --no-cache-dir \
        --index-url "${PIP_INDEX_URL}" \
        alibaba-cloud-ops-mcp-server==0.9.27

# Create symlink so PythonProcessManager can resolve 'python' command
RUN ln -sf /app/python-service/.venv/bin/python /usr/local/bin/python

# External MCP runtimes and stable project launchers.
COPY --from=github-mcp /server/github-mcp-server /usr/local/bin/github-mcp-server
COPY scripts/mcp/zhikun-github-mcp scripts/mcp/zhikun-alibaba-cloud-ops-mcp /usr/local/bin/
RUN chmod +x /usr/local/bin/github-mcp-server \
    /usr/local/bin/zhikun-github-mcp \
    /usr/local/bin/zhikun-alibaba-cloud-ops-mcp

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
