#!/bin/bash

# 1. 서버에서 최신 코드 가져오기
echo "Pulling latest code from GitHub..."
git pull origin main

# 2. Gradle 빌드 (테스트 제외)
echo "Building the project..."
./gradlew clean build -x test

# 3. 애플리케이션 실행
echo "Starting the application..."
nohup java -jar /home/ubuntu/BE/build/libs/be-0.0.1-SNAPSHOT.jar > /home/ubuntu/output.log 2>&1 &

# 4. 배포 완료 메시지
echo "Deployment complete!"
