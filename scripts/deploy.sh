#!/bin/bash
set -euo pipefail

APP_DIR="${APP_DIR:-/home/ubuntu/BE}"
COMPOSE_FILE="${COMPOSE_FILE:-docker-compose.prod.yml}"
SPRING_AUTOCONFIGURE_EXCLUDE="${SPRING_AUTOCONFIGURE_EXCLUDE:-org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration}"
HEALTH_CHECK_URL="${HEALTH_CHECK_URL:-http://localhost:8080/actuator/health}"
HEALTH_CHECK_RETRIES="${HEALTH_CHECK_RETRIES:-30}"
HEALTH_CHECK_INTERVAL_SECONDS="${HEALTH_CHECK_INTERVAL_SECONDS:-2}"

: "${REGISTRY:?REGISTRY is required}"
: "${REGISTRY_USERNAME:?REGISTRY_USERNAME is required}"
: "${REGISTRY_PASSWORD:?REGISTRY_PASSWORD is required}"
: "${IMAGE_URI:?IMAGE_URI is required}"

echo "[1/5] Switching to app directory..."
cd "${APP_DIR}"

echo "[2/5] Logging in to container registry..."
echo "${REGISTRY_PASSWORD}" | docker login --username "${REGISTRY_USERNAME}" --password-stdin "${REGISTRY}"

echo "[3/5] Pulling and starting container..."
export SPRING_AUTOCONFIGURE_EXCLUDE
docker compose -f "${COMPOSE_FILE}" pull app
docker compose -f "${COMPOSE_FILE}" up -d app

echo "[4/5] Waiting for health check: ${HEALTH_CHECK_URL}"
for _ in $(seq 1 "${HEALTH_CHECK_RETRIES}"); do
  if curl -fsS "${HEALTH_CHECK_URL}" >/dev/null; then
    echo "Health check passed."
    echo "[5/5] Docker deployment complete."
    docker image prune -f >/dev/null 2>&1 || true
    exit 0
  fi

  sleep "${HEALTH_CHECK_INTERVAL_SECONDS}"
done

echo "Health check failed after ${HEALTH_CHECK_RETRIES} attempts."
echo "Recent container logs:"
docker compose -f "${COMPOSE_FILE}" logs --tail 120 app || true
exit 1
