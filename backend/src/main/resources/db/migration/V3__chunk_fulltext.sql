-- Keyword side of hybrid retrieval. Generated so ingest does not have to write tsv.
ALTER TABLE chunks
  ADD COLUMN IF NOT EXISTS tsv tsvector
  GENERATED ALWAYS AS (to_tsvector('english', coalesce(section, '') || ' ' || text)) STORED;

CREATE INDEX IF NOT EXISTS chunks_tsv_idx ON chunks USING GIN (tsv);
