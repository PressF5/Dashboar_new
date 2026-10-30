CREATE TABLE publication_snapshot (
  id BIGSERIAL PRIMARY KEY,
  snapshot_date DATE NOT NULL UNIQUE,
  source_import_id BIGINT REFERENCES import_batch(id),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE publication_msu_statistics (
  id BIGSERIAL PRIMARY KEY,
  snapshot_id BIGINT NOT NULL REFERENCES publication_snapshot(id) ON DELETE CASCADE,
  msu_id BIGINT NOT NULL REFERENCES msu(id),
  source_code VARCHAR(40),
  social_publications BIGINT,
  website_publications BIGINT,
  parent_chat_publications BIGINT,
  thematic_videos BIGINT,
  publication_reach BIGINT,
  organizations_percent NUMERIC(6,2),
  CONSTRAINT uk_publication_snapshot_msu UNIQUE(snapshot_id, msu_id)
);
CREATE INDEX idx_publication_snapshot_date ON publication_snapshot(snapshot_date DESC);
