FROM postgres:16

# Install pgvector extension
RUN apt-get update && \
    apt-get install -y postgresql-16-pgvector && \
    rm -rf /var/lib/apt/lists/*

# 초기화 스크립트 복사
# 컨테이너가 처음 시작될 때 init.sql 내용이 자동 실행되어 스키마를 생성합니다.
COPY init.sql /docker-entrypoint-initdb.d/
