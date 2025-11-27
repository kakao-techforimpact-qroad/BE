#!/bin/bash

# 1. 서버에서 최신 코드 가져오기
echo "Pulling latest code from GitHub..."
git pull origin main

# 2. Gradle 빌드 (테스트 제외)
echo "Building the project..."
./gradlew clean build -x test

# 3. 애플리케이션 실행 (JAR 파일이 /path/to/your-app.jar에 있다고 가정)
echo "Starting the application..."
nohup java -jar /home/ubuntu/be-0.0.1-SNAPSHOT.jar > output.log 2>&1 &

# 4. 배포 완료 메시지
echo "Deployment complete!"
