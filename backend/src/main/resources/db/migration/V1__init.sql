CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE sources (
  id            UUID PRIMARY KEY,
  title         TEXT NOT NULL,
  url           TEXT,
  source_type   TEXT NOT NULL,
  content_hash  TEXT NOT NULL,
  fetched_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  enabled       BOOLEAN NOT NULL DEFAULT TRUE,
  notes         TEXT
);

CREATE TABLE chunks (
  id           UUID PRIMARY KEY,
  source_id    UUID NOT NULL REFERENCES sources(id) ON DELETE CASCADE,
  chunk_index  INT  NOT NULL,
  section      TEXT,
  page         INT,
  text         TEXT NOT NULL,
  embedding    vector(384) NOT NULL
);
CREATE INDEX chunks_embedding_idx ON chunks USING hnsw (embedding vector_cosine_ops);
CREATE INDEX chunks_source_idx ON chunks(source_id);

-- No authentication in the prototype: drafts are reachable only with their random owner_token.
CREATE TABLE drafts (
  id            UUID PRIMARY KEY,
  owner_token   UUID NOT NULL,
  title         TEXT NOT NULL,
  workflow      TEXT NOT NULL,
  answers       JSONB NOT NULL DEFAULT '{}'::jsonb,
  summary_md    TEXT,
  review        JSONB,
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX drafts_owner_idx ON drafts(owner_token);

CREATE TABLE interview_turns (
  id          UUID PRIMARY KEY,
  draft_id    UUID NOT NULL REFERENCES drafts(id) ON DELETE CASCADE,
  role        TEXT NOT NULL,
  content     TEXT NOT NULL,
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
