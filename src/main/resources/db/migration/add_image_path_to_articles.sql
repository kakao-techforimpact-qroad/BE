-- articles 테이블에 image_path 컬럼 추가
-- 2026-03-25 feat: 기사별 이미지 추출 및 S3 업로드 기능 추가 (커밋 5f88fd7)
ALTER TABLE articles ADD COLUMN IF NOT EXISTS image_path VARCHAR(500);
