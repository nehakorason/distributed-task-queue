-- Task Queue Database Schema
-- This runs on first container start; Spring JPA will manage subsequent migrations

CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

-- Jobs table (JPA will create/update this, but we define indexes here for performance)
CREATE TABLE IF NOT EXISTS jobs (
    id          VARCHAR(36)   PRIMARY KEY DEFAULT uuid_generate_v4()::VARCHAR,
    type        VARCHAR(100)  NOT NULL,
    payload     TEXT,
    status      VARCHAR(20)   NOT NULL DEFAULT 'PENDING',
    priority    VARCHAR(10)   NOT NULL DEFAULT 'NORMAL',
    retry_count INTEGER       NOT NULL DEFAULT 0,
    max_retries INTEGER       NOT NULL DEFAULT 3,
    worker_id   VARCHAR(100),
    result      TEXT,
    error_message TEXT,
    scheduled_at  TIMESTAMP,
    started_at    TIMESTAMP,
    completed_at  TIMESTAMP,
    created_at    TIMESTAMP   NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMP   NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_jobs_status     ON jobs(status);
CREATE INDEX IF NOT EXISTS idx_jobs_priority   ON jobs(priority);
CREATE INDEX IF NOT EXISTS idx_jobs_created_at ON jobs(created_at DESC);
CREATE INDEX IF NOT EXISTS idx_jobs_type       ON jobs(type);
CREATE INDEX IF NOT EXISTS idx_jobs_worker_id  ON jobs(worker_id);

-- Composite index for common query patterns
CREATE INDEX IF NOT EXISTS idx_jobs_status_created ON jobs(status, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_jobs_type_status    ON jobs(type, status);

-- Auto-update updated_at
CREATE OR REPLACE FUNCTION update_updated_at()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = NOW();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS jobs_updated_at ON jobs;
CREATE TRIGGER jobs_updated_at
    BEFORE UPDATE ON jobs
    FOR EACH ROW EXECUTE FUNCTION update_updated_at();
