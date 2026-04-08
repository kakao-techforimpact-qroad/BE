# 배포 전 최종 체크리스트

## 1. 커밋/푸시 전 보안 점검
- 민감정보(키/토큰/비밀번호/개인키)가 코드에 하드코딩되지 않았는지 확인
- 다음 파일이 Git에 포함되지 않는지 확인
- `.env`, `*.env`
- `application-prod.properties` 등 운영 설정 원본
- `*.pem`, `*.key`, `.secret/`
- `git status`로 추적 대상 파일 최종 확인
- PR/Push 시 `Secret Scan (Gitleaks)` 워크플로우 통과 확인

## 2. “올려도 되는 것 / 안 되는 것”

### 올려도 되는 것
- 애플리케이션 소스 코드(Java/Python)
- Dockerfile, docker-compose, 배포 스크립트
- 설정 템플릿/문서 (`README`, `docs/*`, `*.example`)
- 환경변수 참조 형태 설정 (`${DB_PASSWORD}`, `${OPENAI_API_KEY}` 등)

### 올리면 안 되는 것
- 실제 비밀값이 들어간 설정 파일
- 로컬 개발자 PC 전용 파일(개인 경로, 개인 인증서)
- DB 덤프/로그/임시파일 중 민감 정보 포함 파일
- 개인키, 토큰, 액세스 키 원문

## 3. 환경변수/시크릿 준비
- 운영 환경에서 아래 항목이 주입되는지 확인
- `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`
- `OPENAI_API_KEY`
- `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`
- `AWS_S3_BUCKET`, `AWS_REGION`, `AWS_S3_PRESIGN_EXP_MINUTES`
- `JWT_SECRET`
- Python 분리기 관련(필요 시)
- `PDF_SEGMENTER_PYTHON`
- `PDF_SEGMENTER_SCRIPT`
- `PDF_SEGMENTER_DEBUG` (선택)

## 4. Docker 이미지 점검
- 최신 커밋 기준으로 이미지가 새로 빌드되었는지 확인
- 런타임에 Python + PyMuPDF 포함 여부 확인
- 컨테이너 시작 후 기사 분리 스크립트 실행 가능 여부 확인

## 5. 배포 직전 기능 점검
- 기사 발행 파이프라인 정상 동작 확인
- 업로드 URL 발급 -> PDF 업로드 -> 발행 작업 시작
- 진행률 조회 -> 완료 -> 상세 조회
- 오류 로그에 시크릿/토큰이 노출되지 않는지 확인

## 6. 롤백 준비
- 직전 정상 이미지 태그 기록
- 장애 시 롤백 절차(이미지 태그 변경 후 재배포) 확인
- 롤백 담당자/연락 경로 확인

## 7. 배포 후 확인
- `/actuator/health` 정상
- 핵심 API 응답 정상
- 로그/메트릭(오류율, 지연시간) 이상 징후 없음
