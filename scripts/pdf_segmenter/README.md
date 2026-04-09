# PDF 기사 분리기(MVP)

## 설치
```bash
pip install -r scripts/pdf_segmenter/requirements.txt
```

## 실행
```bash
python scripts/pdf_segmenter/main.py ^
  --pdf C:\path\to\newspaper.pdf ^
  --output scripts/pdf_segmenter/out/articles.json ^
  --debug-dir scripts/pdf_segmenter/out/debug ^
  --dump-pages
```

## 연동 모드(Spring 브리지용)
```bash
python scripts/pdf_segmenter/main.py --pdf C:\path\to\newspaper.pdf --stdout-json
```
- 입력: `--pdf <경로>`
- 출력: 표준출력(stdout) JSON 전용

## 출력 파일
- `articles.json`: 병합 완료 기사 목록
- `debug/pages_blocks.json`: 페이지별 원본 블록(`--dump-pages` 사용 시)
- `debug/pXX_raw_blocks.json`: 페이지별 원본 텍스트 블록
- `debug/pXX_clean_blocks.json`: 헤더/푸터/광고 제거 후 블록
- `debug/pXX_articles.json`: 페이지별 분리 결과
- `debug/summary.json`: 실행 요약
