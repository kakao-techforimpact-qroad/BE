-- init.sql
-- pgvector 확장 생성 (최상단에 위치)
CREATE EXTENSION IF NOT EXISTS vector;

-- 1. admins 테이블 생성
CREATE TABLE admins (
    id BIGSERIAL PRIMARY KEY,
    login_id VARCHAR(100) NOT NULL UNIQUE,
    password VARCHAR(255) NOT NULL,
    press_company VARCHAR(100),
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'EXPIRED')),
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);

-- 2. papers 테이블 생성
CREATE TABLE papers (
    id BIGSERIAL PRIMARY KEY,
    published_date DATE,
    title VARCHAR(255) NOT NULL,
    content TEXT NOT NULL,
    file_path VARCHAR(255) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'EXPIRED')),
    admin_id BIGINT REFERENCES admins(id) ON DELETE SET NULL,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);

-- 3. qr_codes 테이블 생성
CREATE TABLE qr_codes (
    id BIGSERIAL PRIMARY KEY,
    qr_key VARCHAR(100) NOT NULL UNIQUE,
    qr_image_url VARCHAR(255),
    target_url VARCHAR(255) NOT NULL,
    expires_at TIMESTAMP,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'EXPIRED')),
    paper_id BIGINT REFERENCES papers(id) ON DELETE SET NULL,
    admin_id BIGINT REFERENCES admins(id) ON DELETE SET NULL,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);

-- 4. articles 테이블 생성
CREATE TABLE articles (
    id BIGSERIAL PRIMARY KEY,
    title VARCHAR(255) NOT NULL,
    summary TEXT,
    link TEXT NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'EXPIRED')),
    reporter VARCHAR(100),
    paper_id BIGINT REFERENCES papers(id) ON DELETE SET NULL,
    admin_id BIGINT REFERENCES admins(id) ON DELETE SET NULL,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW(),
    content TEXT
);

-- 6. keywords 테이블 생성
CREATE TABLE keywords (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(100) NOT NULL UNIQUE,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);

-- 7. article_keywords 테이블 생성 (N:M 관계)
CREATE TABLE article_keywords (
    id BIGSERIAL PRIMARY KEY,
    article_id BIGINT NOT NULL REFERENCES articles(id) ON DELETE CASCADE,
    keyword_id BIGINT NOT NULL REFERENCES keywords(id) ON DELETE CASCADE,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);

-- 8. policy 테이블 생성
CREATE TABLE policy (
    id BIGSERIAL PRIMARY KEY,
    title VARCHAR(100) NOT NULL,
    sub_title VARCHAR(100),
    content TEXT,
    minister_name VARCHAR(100),
    original_url VARCHAR(255) NOT NULL,
    registration_date TIMESTAMP,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'EXPIRED')),
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);

-- 9. article_related 테이블 생성 (기사-기사 연관 관계)
CREATE TABLE article_related (
    id BIGSERIAL PRIMARY KEY,
    article_id BIGINT NOT NULL REFERENCES articles(id) ON DELETE CASCADE,
    related_article_id BIGINT NOT NULL REFERENCES articles(id) ON DELETE CASCADE,
    score DOUBLE PRECISION,
    batch_date DATE NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);
-- 인덱스 추가
CREATE INDEX idx_article_related_article_id ON article_related(article_id);
CREATE INDEX idx_article_related_related_article_id ON article_related(related_article_id);

-- 10. policy_keyword_related 테이블 생성 (정책-키워드 연관 관계)
CREATE TABLE policy_keyword_related (
    id BIGSERIAL PRIMARY KEY,
    keyword_id BIGINT NOT NULL REFERENCES keywords(id) ON DELETE CASCADE,
    policy_id BIGINT NOT NULL REFERENCES policy(id) ON DELETE CASCADE,
    score DOUBLE PRECISION,
    batch_date DATE NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);
-- 인덱스 추가
CREATE INDEX idx_policy_related_keyword_id ON policy_keyword_related(keyword_id);
CREATE INDEX idx_policy_related_policy_id ON policy_keyword_related(policy_id);

-- 11. vector_articles 테이블 생성 (벡터 저장소)
CREATE TABLE vector_articles (
    id BIGSERIAL PRIMARY KEY,
    article_id BIGINT NOT NULL UNIQUE REFERENCES articles(id) ON DELETE CASCADE,
    title TEXT,
    published_date DATE,
    vector vector(1536) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);

-- 12. 벡터 검색 성능을 위한 HNSW 인덱스 생성
CREATE INDEX vector_articles_hnsw_idx
ON vector_articles
USING hnsw (vector vector_cosine_ops);

