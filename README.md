# QRoad Backend (BE)

QRoad 백엔드 서비스입니다.  
신문 PDF를 입력받아 기사 단위로 분리하고, LLM으로 요약/키워드를 분석한 뒤 사용자/관리자 API를 제공합니다.

## 1. 기술 스택

- Java 17
- Spring Boot 3.5.x
- Spring Web, Spring Data JPA, Spring Security (JWT)
- PostgreSQL 16 + pgvector
- OpenAI API (`gpt-4o-mini`, `text-embedding-3-small`)
- AWS S3 (Presigned URL 업로드)
- Swagger/OpenAPI (`springdoc`)
- Actuator + Prometheus metrics

## 2. 주요 기능

- PDF 기반 발행 파이프라인
- 기사 분리, 요약, 키워드 추출 (LLM)
- 기사 감정 공감 토글
- 관련 기사/정책 매핑
- 제보 등록(사용자) 및 제보 확인(관리자)
- JWT 기반 관리자 인증
- 비로그인 사용자 UUID 쿠키(`qroad_uid`) 발급/유지

## 3. 프로젝트 구조

```text
src/main/java/com/qroad/be
|- config        # Security, Swagger, OpenAI, AWS, Async 설정
|- controller    # REST 컨트롤러
|- domain        # JPA 엔티티
|- dto           # 요청/응답 DTO
|- external      # 외부 연동(OpenAI, 정책 API)
|- pdf           # PDF 텍스트 추출
|- progress      # 비동기 발행 진행률 관리
|- repository    # JPA Repository
|- security      # JWT/쿠키 식별 필터
`- service       # 비즈니스 로직
```

## 4. 환경 변수

보안을 위해 실제 값은 저장소에 넣지 않습니다. 아래 변수명만 참고하세요.

```env
DB_URL=
DB_USERNAME=
DB_PASSWORD=

OPENAI_API_KEY=

AWS_S3_BUCKET=
AWS_REGION=
AWS_S3_PRESIGN_EXP_MINUTES=

JWT_SECRET=
```

참고:
- `JWT_SECRET`은 HS256 기준 최소 32바이트 이상이어야 합니다.
- 비밀값은 `.env`, Secret Manager, CI/CD Secret, 서버 환경 변수로만 관리하세요.

## 5. 로컬 DB 실행 (Docker)

`db/` 폴더의 PostgreSQL + pgvector 구성을 사용합니다.

```bash
cd db
docker build -t qroad-db .

docker run -d \
  --name qroad-db \
  -e POSTGRES_PASSWORD=<POSTGRES_PASSWORD> \
  -e POSTGRES_DB=<POSTGRES_DB> \
  -p 5432:5432 \
  qroad-db
```

스키마는 `db/init.sql` 기준으로 초기화됩니다.

## 6. 실행 방법

```bash
# Windows
gradlew.bat bootRun

# macOS/Linux
./gradlew bootRun
```

- 활성 프로필: `dev`
- Swagger UI: `http://localhost:8080/swagger-ui/index.html`
- Actuator: `http://localhost:8080/actuator`

## 7. API 분류

### 7.1 사용자 API

- `GET /api/qr/{paper_id}`: 지면(기사 목록) 조회
- `GET /api/articles/{article_id}`: 기사 상세 조회
- `POST /api/articles/{articleId}/emotions`: 감정 공감 토글
- `POST /api/reports`: 제보 등록

### 7.2 관리자 API

- 인증
  - `POST /api/admin/login`
  - `POST /api/admin/register`
- 발행
  - `POST /api/admin/publications/upload-url`
  - `POST /api/admin/publications`
  - `GET /api/admin/publications/{jobId}/progress`
  - `GET /api/admin/publications`
  - `GET /api/admin/publications/{paperId}`
  - `POST /api/admin/publications/finalize-file`
  - `POST /api/admin/articles/{id}` (기사 요약/키워드 수정)
- 제보 확인
  - `GET /api/admin/reports`
  - `GET /api/admin/reports/{reportId}`
- 정책 동기화
  - `POST /api/policy/sync`

## 8. 비로그인 사용자 UUID 쿠키

- 모든 요청에서 `qroad_uid` 쿠키를 확인합니다.
- 쿠키가 없거나 값이 유효한 UUID가 아니면 새 UUID를 발급합니다.
- 발급된 UUID는 재방문 사용자 식별에 사용합니다.

쿠키 정책:
- Name: `qroad_uid`
- `HttpOnly=true`
- `SameSite=Lax`
- `Path=/`
- `Max-Age=365일`
- `Secure`: HTTPS 요청일 때만 적용

## 9. 발행 파이프라인

1. 클라이언트가 `upload-url` API로 S3 업로드 URL을 발급받습니다.
2. PDF를 S3 `temp/...pdf` 경로로 업로드합니다.
3. `POST /api/admin/publications` 호출로 비동기 작업을 시작합니다.
4. 서버가 PDF를 읽고 텍스트를 추출합니다.
5. LLM이 기사 분리 및 제목/기자/요약/키워드를 분석합니다.
6. 기사/키워드/연관 데이터를 저장합니다.
7. 클라이언트는 진행률 API를 폴링합니다 (`PROCESSING` / `DONE` / `FAILED`).
8. 완료 시 파일을 `paper/{paperId}.pdf`로 finalize 합니다.

## 10. 인증 및 보안

- 인증 방식: JWT Bearer
- 공개 경로:
  - `/api/admin/login`, `/api/admin/register`
  - `/api/qr/**`, `/api/articles/**`
  - `POST /api/reports`
- 그 외 경로는 인증이 필요합니다.

## 11. 빌드 확인

```bash
./gradlew classes -x test
```

현재 상태:
- 빌드는 성공합니다.

## 배포 체크리스트
- 배포 전 최종 점검 문서: [pre-deploy-checklist.md](docs/pre-deploy-checklist.md)
