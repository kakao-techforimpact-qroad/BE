CREATE TABLE reports (
    id              BIGSERIAL PRIMARY KEY,
    title           VARCHAR(255)    NOT NULL,
    content         TEXT            NOT NULL,
    reporter_contact VARCHAR(255),
    status          VARCHAR(20)     NOT NULL DEFAULT 'PENDING',
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ              DEFAULT now()
);

-- updated_at 자동 갱신 트리거
CREATE OR REPLACE FUNCTION update_reports_updated_at()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = now();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_reports_updated_at
    BEFORE UPDATE ON reports
    FOR EACH ROW
    EXECUTE FUNCTION update_reports_updated_at();
