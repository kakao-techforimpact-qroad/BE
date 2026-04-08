FROM gradle:8.10.2-jdk17 AS build
WORKDIR /app

COPY gradle ./gradle
COPY gradlew gradlew.bat build.gradle settings.gradle ./
RUN chmod +x gradlew

COPY src ./src
RUN ./gradlew bootJar --no-daemon -x test

FROM eclipse-temurin:17-jre-jammy
WORKDIR /app

RUN apt-get update \
  && apt-get install -y --no-install-recommends curl python3 python3-pip python-is-python3 \
  && rm -rf /var/lib/apt/lists/*

COPY --from=build /app/build/libs/*.jar /app/app.jar
COPY scripts /app/scripts
RUN python -m pip install --no-cache-dir -r /app/scripts/pdf_segmenter/requirements.txt

ENV PDF_SEGMENTER_PYTHON=python \
    PDF_SEGMENTER_SCRIPT=scripts/pdf_segmenter/main.py \
    PDF_SEGMENTER_TIMEOUT_SECONDS=900 \
    PDF_SEGMENTER_DEBUG=false

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
