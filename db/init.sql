-- init.sql
-- pgvector extension
CREATE EXTENSION IF NOT EXISTS vector;

-- 1) admins
CREATE TABLE admins (
    id BIGSERIAL PRIMARY KEY,
    login_id VARCHAR(100) NOT NULL UNIQUE,
    password VARCHAR(255) NOT NULL,
    press_company VARCHAR(100),
    status VARCHAR(10) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'EXPIRED')),
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);

-- 2) papers
CREATE TABLE papers (
    id BIGSERIAL PRIMARY KEY,
    published_date DATE,
    title VARCHAR(255) NOT NULL,
    content TEXT NOT NULL,
    file_path VARCHAR(255) NOT NULL,
    status VARCHAR(10) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'EXPIRED')),
    admin_id BIGINT REFERENCES admins(id) ON DELETE SET NULL,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);

-- 3) articles
CREATE TABLE articles (
    id BIGSERIAL PRIMARY KEY,
    title VARCHAR(255) NOT NULL,
    content TEXT,
    summary TEXT,
    link TEXT NOT NULL,
    status VARCHAR(10) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'EXPIRED')),
    reporter VARCHAR(100),
    paper_id BIGINT REFERENCES papers(id) ON DELETE SET NULL,
    admin_id BIGINT REFERENCES admins(id) ON DELETE SET NULL,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);

-- 4) keywords
CREATE TABLE keywords (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(100) NOT NULL UNIQUE,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);

-- 5) article_keywords
CREATE TABLE article_keywords (
    id BIGSERIAL PRIMARY KEY,
    article_id BIGINT NOT NULL REFERENCES articles(id) ON DELETE CASCADE,
    keyword_id BIGINT NOT NULL REFERENCES keywords(id) ON DELETE CASCADE,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW(),
    CONSTRAINT uk_article_keywords_article_keyword UNIQUE (article_id, keyword_id)
);

-- 6) article_related
CREATE TABLE article_related (
    id BIGSERIAL PRIMARY KEY,
    article_id BIGINT NOT NULL REFERENCES articles(id) ON DELETE CASCADE,
    related_article_id BIGINT NOT NULL REFERENCES articles(id) ON DELETE CASCADE,
    score DOUBLE PRECISION,
    batch_date DATE,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_article_related_article_id ON article_related(article_id);
CREATE INDEX idx_article_related_related_article_id ON article_related(related_article_id);

-- 7) article_emotions
CREATE TABLE article_emotions (
    id BIGSERIAL PRIMARY KEY,
    article_id BIGINT NOT NULL REFERENCES articles(id) ON DELETE CASCADE,
    emotion_type VARCHAR(20) NOT NULL,
    user_identifier VARCHAR(255) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW(),
    CONSTRAINT uk_article_user_emotion UNIQUE (article_id, user_identifier, emotion_type)
);
CREATE INDEX idx_article_emotion ON article_emotions(article_id, emotion_type);

-- 8) policy
CREATE TABLE policy (
    id BIGSERIAL PRIMARY KEY,
    title TEXT NOT NULL,
    sub_title TEXT,
    content TEXT,
    minister_name VARCHAR(100),
    original_url TEXT NOT NULL,
    registration_date TIMESTAMP,
    status VARCHAR(10) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'EXPIRED')),
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);

-- 9) policy_article_related
CREATE TABLE policy_article_related (
    id BIGSERIAL PRIMARY KEY,
    article_id BIGINT NOT NULL REFERENCES articles(id) ON DELETE CASCADE,
    policy_id BIGINT NOT NULL REFERENCES policy(id) ON DELETE CASCADE,
    score DOUBLE PRECISION,
    batch_date DATE NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'active' CHECK (status IN ('active', 'expired')),
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_policy_article_related_article_id ON policy_article_related(article_id);
CREATE INDEX idx_policy_article_related_policy_id ON policy_article_related(policy_id);

-- 10) vector_articles (used by JDBC in PaperService)
CREATE TABLE vector_articles (
    id BIGSERIAL PRIMARY KEY,
    article_id BIGINT NOT NULL UNIQUE REFERENCES articles(id) ON DELETE CASCADE,
    title TEXT,
    published_date DATE,
    vector vector(1536) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE INDEX vector_articles_hnsw_idx
ON vector_articles
USING hnsw (vector vector_cosine_ops);

-- 11) vector_policy
CREATE TABLE vector_policy (
    id BIGSERIAL PRIMARY KEY,
    policy_id BIGINT NOT NULL UNIQUE REFERENCES policy(id) ON DELETE CASCADE,
    title TEXT,
    vector vector(1536) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE INDEX vector_policy_hnsw_idx
ON vector_policy
USING hnsw (vector vector_cosine_ops);
