#!/bin/bash
set -euo pipefail

APP_NAME="be-0.0.1-SNAPSHOT.jar"
APP_PATH="/home/ubuntu/BE/build/libs/${APP_NAME}"
LOG_PATH="/var/log/be/be.log"
SPRING_AUTOCONFIGURE_EXCLUDE="org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration"
GRACEFUL_TIMEOUT_SECONDS=20
HEALTH_CHECK_URL="${HEALTH_CHECK_URL:-http://localhost:8080/actuator/health}"
HEALTH_CHECK_RETRIES="${HEALTH_CHECK_RETRIES:-30}"
HEALTH_CHECK_INTERVAL_SECONDS="${HEALTH_CHECK_INTERVAL_SECONDS:-2}"

echo "[0/6] Preparing log directory..."
sudo mkdir -p /var/log/be
sudo chown ubuntu:ubuntu /var/log/be

echo "[1/6] Updating source (main)..."
git fetch origin main
git checkout main
git pull --ff-only origin main

echo "[2/6] Stopping existing application..."
PID="$(pgrep -f "${APP_NAME}" || true)"

if [[ -n "${PID}" ]]; then
  echo "Sending SIGTERM to process ${PID}"
  kill "${PID}" || true

  for _ in $(seq 1 "${GRACEFUL_TIMEOUT_SECONDS}"); do
    if ! kill -0 "${PID}" 2>/dev/null; then
      break
    fi
    sleep 1
  done

  if kill -0 "${PID}" 2>/dev/null; then
    echo "Process ${PID} is still alive. Forcing SIGKILL."
    kill -9 "${PID}"
  fi
else
  echo "No running process found."
fi

echo "[3/6] Building..."
./gradlew clean build -x test

echo "[4/6] Starting application..."
export SPRING_AUTOCONFIGURE_EXCLUDE
nohup java -jar "${APP_PATH}" >> "${LOG_PATH}" 2>&1 &

echo "[5/6] Waiting for health check: ${HEALTH_CHECK_URL}"
for _ in $(seq 1 "${HEALTH_CHECK_RETRIES}"); do
  if curl -fsS "${HEALTH_CHECK_URL}" >/dev/null; then
    echo "Health check passed."
    echo "[6/6] Deployment complete."
    exit 0
  fi
  sleep "${HEALTH_CHECK_INTERVAL_SECONDS}"
done

echo "Health check failed after ${HEALTH_CHECK_RETRIES} attempts."
echo "Recent logs:"
tail -n 100 "${LOG_PATH}" || true
exit 1
