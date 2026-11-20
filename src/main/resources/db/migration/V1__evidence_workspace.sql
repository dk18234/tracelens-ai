CREATE TABLE incidents (
  id VARCHAR(100) PRIMARY KEY,
  title VARCHAR(240) NOT NULL,
  severity VARCHAR(20) NOT NULL,
  summary TEXT NOT NULL,
  snapshot JSONB NOT NULL,
  revision INTEGER NOT NULL DEFAULT 1,
  source VARCHAR(40) NOT NULL DEFAULT 'synthetic-fixture',
  updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
CREATE TABLE runbooks (
  id VARCHAR(80) PRIMARY KEY,
  title VARCHAR(240) NOT NULL,
  body TEXT NOT NULL,
  content_hash CHAR(64) NOT NULL,
  search_vector TSVECTOR GENERATED ALWAYS AS (to_tsvector('english', title || ' ' || body)) STORED
);
CREATE INDEX runbooks_search_idx ON runbooks USING GIN(search_vector);
CREATE TABLE embeddings (
  document_id VARCHAR(80) NOT NULL REFERENCES runbooks(id),
  model VARCHAR(120) NOT NULL,
  content_hash CHAR(64) NOT NULL,
  vector DOUBLE PRECISION[] NOT NULL,
  PRIMARY KEY(document_id,model)
);
CREATE TABLE investigations (
  id UUID PRIMARY KEY,
  request_key VARCHAR(100) NOT NULL UNIQUE,
  fingerprint CHAR(64) NOT NULL,
  incident_id VARCHAR(100) NOT NULL REFERENCES incidents(id),
  incident_revision INTEGER NOT NULL,
  snapshot JSONB NOT NULL,
  question TEXT NOT NULL,
  status VARCHAR(24) NOT NULL DEFAULT 'QUEUED',
  lease_token UUID,
  lease_until TIMESTAMPTZ,
  result JSONB,
  error_code VARCHAR(60),
  error_message TEXT,
  created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
  completed_at TIMESTAMPTZ
);
CREATE INDEX investigations_queue_idx ON investigations(created_at) WHERE status='QUEUED';
