#!/bin/bash
APP_NAME="be-0.0.1-SNAPSHOT.jar"
APP_PATH="/home/ubuntu/BE/build/libs/$APP_NAME"
LOG_PATH="/var/log/be/be.log"

# 0. 로그 디렉토리 생성 (없으면)
sudo mkdir -p /var/log/be
sudo chown ubuntu:ubuntu /var/log/be

echo "[1/4] Stopping existing application..."

# 실행 중인 기존 java 프로세스 종료
PID=$(pgrep -f $APP_NAME)

if [ -n "$PID" ]; then
  echo "Killing process $PID"
  kill -9 $PID
else
  echo "No running process found."
fi

echo "[2/4] Pulling latest code from GitHub..."
git pull origin main

echo "[3/4] Building..."
./gradlew clean build -x test

echo "[4/4] Starting application..."
nohup java -jar $APP_PATH >> $LOG_PATH 2>&1 &

echo "Deployment complete!"
