# =============================================================================
# AI Code Assistant — Multi-stage Docker Build
# =============================================================================

# ---- Stage 1: Build Backend ----
FROM eclipse-temurin:21-jdk AS backend-build
WORKDIR /build

# Cache Maven dependencies
COPY backend/pom.xml ./backend/
COPY backend/.mvn ./backend/.mvn/
COPY backend/mvnw ./backend/
RUN cd backend && chmod +x mvnw && ./mvnw dependency:go-offline -B

# Build application
COPY backend/src ./backend/src/
RUN cd backend && ./mvnw package -DskipTests -B

# ---- Stage 2: Build Frontend ----
FROM node:20-alpine AS frontend-build
WORKDIR /build/frontend

COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci --ignore-scripts

COPY frontend/ ./
RUN npm run build

# ---- Stage 3: Runtime ----
FROM eclipse-temurin:21-jre AS runtime

LABEL maintainer="AI Code Assistant Team"
LABEL description="AI Code Assistant Backend Service"

# Install Python for python-service integration + ripgrep for GrepTool
RUN apt-get update && \
    apt-get install -y --no-install-recommends python3 python3-pip python3-venv ripgrep && \
    apt-get clean && \
    rm -rf /var/lib/apt/lists/*

# Create non-root user
RUN groupadd -r appuser && useradd -r -g appuser -d /app appuser

WORKDIR /app

# Copy backend JAR
COPY --from=backend-build /build/backend/target/*.jar app.jar

# Copy frontend build output
COPY --from=frontend-build /build/frontend/dist ./static/

# Copy python-service
COPY python-service/ ./python-service/

# Setup Python virtual environment
RUN python3 -m venv /app/python-service/.venv && \
    /app/python-service/.venv/bin/pip install --no-cache-dir -r /app/python-service/requirements.txt && \
    /app/python-service/.venv/bin/python -m playwright install chromium --with-deps

# Set ownership
RUN chown -R appuser:appuser /app

USER appuser

# Environment
ENV JAVA_OPTS="-Xms256m -Xmx1024m --enable-preview"
ENV PYTHON_SERVICE_PATH="/app/python-service"
ENV SPRING_PROFILES_ACTIVE="production"

# Health check (wget — curl not available in jre base image)
HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --retries=3 \
    CMD wget -q --spider http://localhost:8080/api/health || exit 1

EXPOSE 8080

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
