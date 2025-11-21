📘 PostgreSQL + pgvector 개발 환경 (Docker)

이 프로젝트는 PostgreSQL 16 + pgvector 확장 + 초기 스키마 자동 생성을 위한 Docker 기반 개발 DB 환경을 제공합니다.
아래 절차를 따라 하면 팀원들은 동일한 DB 환경을 로컬에서 즉시 구성할 수 있습니다.

📁 디렉토리 구조
/db
│
├── Dockerfile
└── init.sql

🚀 1. Docker 이미지 빌드

아래 명령어를 실행하여 DB Docker 이미지를 생성합니다.

cd db
docker build -t qroad-db .


qroad-db → 생성될 Docker 이미지 이름

빌드 시 자동으로 pgvector 설치 및 init.sql 복사 완료

🐘 2. Docker 컨테이너 실행
docker run -d \
--name qroad-db \
-e POSTGRES_PASSWORD=admin \
-e POSTGRES_DB=QRoad \
-p 5432:5432 \
qroad-db

환경 변수 설명
변수	설명
POSTGRES_PASSWORD	DB postgres 계정 비밀번호
POSTGRES_DB	최초 생성될 DB 이름
🔌 3. DB 접속 정보
host: localhost
port: 5432
database: QRoad
user: postgres
password: admin


원하는 경우 비밀번호와 포트는 각자 로컬 환경에 맞게 바꿔도 됩니다.

📄 4. pgvector 설치 확인

컨테이너에 접속:

docker exec -it qroad-db psql -U postgres -d QRoad


확장 설치 여부 확인:

SELECT extname FROM pg_extension;


vector가 보이면 pgvector 설치 완료입니다.

📑 5. 초기 스키마 자동 생성

init.sql은 컨테이너가 처음 생성될 때 단 한 번만 자동 실행됩니다.

포함된 내용:

pgvector 확장 생성

모든 테이블 생성

인덱스 생성

vector(1536) 칼럼 및 HNSW 인덱스 포함

init.sql을 수정한 후 다시 적용하고 싶다면?

기존 컨테이너 삭제 → 다시 실행:

docker rm -f qroad-db
docker run ...

🧹 6. Docker 정리 명령어 (선택 사항)

컨테이너 삭제:

docker rm -f qroad-db


이미지 삭제:

docker rmi qroad-db

🎉 완료!
이 문서대로 실행하면 팀원들은 모두 동일한 PostgreSQL + pgvector 개발 환경을 쉽게 구축할 수 있습니다.