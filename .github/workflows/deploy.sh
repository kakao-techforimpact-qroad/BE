#!/bin/bash
set -euo pipefail

APP_NAME="be-0.0.1-SNAPSHOT.jar"
APP_PATH="/home/ubuntu/BE/build/libs/${APP_NAME}"
LOG_PATH="/var/log/be/be.log"
SPRING_AUTOCONFIGURE_EXCLUDE="org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration"
GRACEFUL_TIMEOUT_SECONDS=20

echo "[0/5] Preparing log directory..."
sudo mkdir -p /var/log/be
sudo chown ubuntu:ubuntu /var/log/be

echo "[1/5] Updating source (main)..."
git fetch origin main
git checkout main
git pull --ff-only origin main

echo "[2/5] Stopping existing application..."
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

echo "[3/5] Building..."
./gradlew clean build -x test

echo "[4/5] Starting application..."
export SPRING_AUTOCONFIGURE_EXCLUDE
nohup java -jar "${APP_PATH}" >> "${LOG_PATH}" 2>&1 &

echo "[5/5] Deployment complete."
