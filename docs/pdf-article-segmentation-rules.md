# PDF 기사 분리 규칙 정의서

## 1. 목적
- PDF 신문 지면에서 기사 단위를 안정적으로 분리한다.
- 광고/공지/구인 등 비기사 영역은 기사 본문과 분리한다.
- 1면 이어짐 기사까지 병합해 최종 기사 데이터를 생성한다.

## 2. 기준 규칙

### A. 페이지 단위 기본 구조 규칙
- `A-1 페이지 헤더 존재`
- 각 페이지 상단의 면번호/섹션명/날짜/호수는 기사 본문이 아니므로 제거한다.
- 예: `2 행정 2026년 1월 9일 금요일·1825호`, `2026년 1월 9일 금요일·1825호 종합 5`

- `A-2 페이지별 성격 차이`
- 1면: 주요 기사 요약 + 이어짐
- 2~10면: 일반 기사 중심
- 11면: 공지/채용/알림 비중 높음
- 12~13면: 장터/부동산/구인광고 중심
- 페이지 성격별로 분리/필터 강도를 다르게 적용한다.

### B. 기사 시작 규칙
- `B-1 큰 제목이 기사 시작점`
- 본문 대비 큰 폰트, 굵은 글씨, 짧은 줄 수, 제목 하단 본문/요약 존재를 시작 신호로 본다.
- 제목 블록 탐지를 1순위로 둔다.

- `B-2 제목 아래 요약/부제 포함`
- 제목 직하단의 한 줄 요약/부제/핵심 문장은 본문과 분리하되 동일 기사에 귀속한다.

### C. 기사 종료 규칙
- `C-1 기자명/이메일은 강한 종료 신호`
- 기사 말미의 기자명 + 이메일 패턴을 종료 힌트로 사용한다.
- 예시 정규식:
```regex
[가-힣]{2,4}\s+[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+
```

### D. 1면 기사 이어짐 규칙
- `D-1 1면 이어짐 표식`
- `▶ 기사 N면 이어짐` 발견 시 대상 페이지를 기록한다.

- `D-2 내지 이어짐 표식`
- `▷ 1면 ‘...’ 이어짐 기사` 형태를 탐지한다.
- 병합 기준:
- 1면 이어짐 표식의 목표 페이지 일치
- 제목 유사도
- 기자명 일치 여부

### E. 사진/캡션 규칙
- `E-1 사진은 기사 내부 요소`
- 기사 내/하단 이미지 영역을 기사 요소로 유지한다.

- `E-2 캡션 추정`
- 이미지 bbox 바로 아래의 짧은 텍스트 bbox를 캡션 후보로 묶는다.

### F. 광고/비기사 영역 규칙
- `F-1 광고 시각/문맥 특징`
- 배경 박스 강조, 전화번호 대형 표기, 가격/상호/홍보문구 반복, 비문장형 나열 구조를 광고 신호로 본다.

- `F-2 장터면 별도 처리`
- 12~13면은 기본적으로 기사 분리 대상에서 제외하거나 `장터/광고`로 분류한다.

### G. 다단 편집 규칙
- `G-1 좌표 기반 읽기 순서 복원`
- PDF 텍스트 추출 순서를 그대로 신뢰하지 않고 컬럼 인식 + 블록화로 재정렬한다.

- `G-2 공간 인접성 우선`
- 같은 제목 하단, 같은 컬럼/인접 컬럼, 다음 큰 제목 전까지를 동일 기사로 묶는다.

## 3. 실제 구현 처리 순서

### 1단계. 블록 추출
- 추출 항목: `page`, `text`, `bbox`, `fontSize`, `fontName`, `isBold`, `isImage`

### 2단계. 헤더/푸터 제거
- 제거 대상: 면 번호, 섹션명, 날짜/호수, 반복 신문명, 하단 고정 광고

### 3단계. 광고/비기사 분리
- 판단 요소: 전화번호 반복, 가격/면적/월세/보증금/채용 키워드, 배경 박스, 광고성 짧은 문구 밀집

### 4단계. 제목 후보 탐지
- 점수 요소: 큰 폰트, bold, 짧은 줄, 상대적으로 상단, 주변 여백

### 5단계. 본문 그룹화
- 기준: 유사 폰트 크기, 같은 컬럼, 세로 인접, 문장형 텍스트

### 6단계. 기자명/이메일 탐지
- 기사 종료 블록 판정에 반영

### 7단계. 이어짐 기사 병합
- `▶ 기사 N면 이어짐`, `▷ 1면 ... 이어짐 기사`, 제목 유사도, 기자명 일치 여부 사용

### 8단계. 최종 JSON 생성
- 서비스 필드명 규칙에 맞춰 출력

## 4. 서비스 기준 JSON 스키마 예시

아래는 기존 제안 키를 현재 서비스 네이밍으로 맞춘 예시다.

```json
{
  "id": "2026-01-09_p1_a1",
  "paperId": 123,
  "pageStart": 1,
  "pageEnd": 2,
  "section": "행정",
  "title": "농어촌 기본소득과 인구 5만 회복 코앞에 둔 옥천…면 발전 계획 수립나서는 군",
  "subtitle": "7일부터 접수 시작, 첫날 1500명 이상 접수",
  "content": "....",
  "summary": "....",
  "reporter": "김기연",
  "email": "kite@okinews.com",
  "continuedFromFront": true,
  "captions": ["..."],
  "imagePath": "article-image/123/1.jpg",
  "keywords": ["옥천", "기본소득", "인구"]
}
```

## 5. 의사코드

```python
for page in pdf.pages:
    blocks = extract_blocks(page)  # text, bbox, font, image

    header_blocks = detect_header(blocks)
    footer_blocks = detect_footer(blocks)
    blocks = remove(blocks, header_blocks + footer_blocks)

    ad_blocks = detect_ads(blocks)
    article_candidate_blocks = remove(blocks, ad_blocks)

    title_blocks = detect_titles(article_candidate_blocks)
    reporter_blocks = detect_reporters(article_candidate_blocks)
    continuation_marks = detect_continuation_marks(article_candidate_blocks)

    grouped_articles = group_body_under_titles(
        title_blocks,
        article_candidate_blocks,
        reporter_blocks
    )

    attach_images_and_captions(grouped_articles, article_candidate_blocks)

merge_continued_articles(grouped_articles_across_pages)
export_json(grouped_articles)
```
