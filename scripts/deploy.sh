#!/bin/bash
set -euo pipefail

APP_DIR="${APP_DIR:-/home/ubuntu/BE}"
COMPOSE_FILE="${COMPOSE_FILE:-docker-compose.prod.yml}"
SPRING_AUTOCONFIGURE_EXCLUDE="${SPRING_AUTOCONFIGURE_EXCLUDE:-org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration}"
HEALTH_CHECK_URL="${HEALTH_CHECK_URL:-http://localhost:8080/actuator/health}"
HEALTH_CHECK_RETRIES="${HEALTH_CHECK_RETRIES:-30}"
HEALTH_CHECK_INTERVAL_SECONDS="${HEALTH_CHECK_INTERVAL_SECONDS:-2}"
ROLLBACK_HEALTH_CHECK_RETRIES="${ROLLBACK_HEALTH_CHECK_RETRIES:-15}"
ROLLBACK_HEALTH_CHECK_INTERVAL_SECONDS="${ROLLBACK_HEALTH_CHECK_INTERVAL_SECONDS:-2}"
CONTAINER_NAME="${CONTAINER_NAME:-qroad-be}"

: "${REGISTRY:?REGISTRY is required}"
: "${REGISTRY_USERNAME:?REGISTRY_USERNAME is required}"
: "${REGISTRY_PASSWORD:?REGISTRY_PASSWORD is required}"
: "${IMAGE_URI:?IMAGE_URI is required}"
LATEST_IMAGE_URI="${LATEST_IMAGE_URI:-}"

echo "[1/5] Switching to app directory..."
cd "${APP_DIR}"

echo "[2/5] Logging in to container registry..."
echo "${REGISTRY_PASSWORD}" | docker login --username "${REGISTRY_USERNAME}" --password-stdin "${REGISTRY}"

CURRENT_IMAGE_URI="$(docker inspect -f '{{.Config.Image}}' "${CONTAINER_NAME}" 2>/dev/null || true)"

echo "[3/5] Pulling target image..."
TARGET_IMAGE_URI="${IMAGE_URI}"
if docker pull "${TARGET_IMAGE_URI}"; then
  echo "Pulled commit image: ${TARGET_IMAGE_URI}"
else
  if [[ -n "${LATEST_IMAGE_URI}" ]]; then
    echo "Commit image pull failed. Falling back to latest: ${LATEST_IMAGE_URI}"
    docker pull "${LATEST_IMAGE_URI}"
    TARGET_IMAGE_URI="${LATEST_IMAGE_URI}"
  else
    echo "Commit image pull failed and LATEST_IMAGE_URI is not set."
    exit 1
  fi
fi

echo "[4/5] Starting container..."
export SPRING_AUTOCONFIGURE_EXCLUDE
export IMAGE_URI="${TARGET_IMAGE_URI}"
docker compose -f "${COMPOSE_FILE}" up -d app

echo "[5/5] Waiting for health check: ${HEALTH_CHECK_URL}"
for _ in $(seq 1 "${HEALTH_CHECK_RETRIES}"); do
  if curl -fsS "${HEALTH_CHECK_URL}" >/dev/null; then
    echo "Health check passed."
    echo "Docker deployment complete. Running image: ${TARGET_IMAGE_URI}"
    docker image prune -f >/dev/null 2>&1 || true
    exit 0
  fi

  sleep "${HEALTH_CHECK_INTERVAL_SECONDS}"
done

echo "Health check failed after ${HEALTH_CHECK_RETRIES} attempts."

if [[ -n "${CURRENT_IMAGE_URI}" && "${CURRENT_IMAGE_URI}" != "${TARGET_IMAGE_URI}" ]]; then
  echo "Attempting rollback to previous image: ${CURRENT_IMAGE_URI}"
  docker pull "${CURRENT_IMAGE_URI}" || true
  export IMAGE_URI="${CURRENT_IMAGE_URI}"
  docker compose -f "${COMPOSE_FILE}" up -d app

  for _ in $(seq 1 "${ROLLBACK_HEALTH_CHECK_RETRIES}"); do
    if curl -fsS "${HEALTH_CHECK_URL}" >/dev/null; then
      echo "Rollback succeeded. Service is healthy on previous image."
      exit 1
    fi
    sleep "${ROLLBACK_HEALTH_CHECK_INTERVAL_SECONDS}"
  done

  echo "Rollback attempted but health check still failing."
else
  echo "No previous image available for rollback."
fi

echo "Recent container logs:"
docker compose -f "${COMPOSE_FILE}" logs --tail 120 app || true
exit 1
