package edu.umkc.advisor.retrieval;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** PostgreSQL + pgvector implementation. Cosine distance via the HNSW index created in V1__init.sql. */
public class PgVectorStore implements VectorStore {
    private final JdbcTemplate jdbc;

    public PgVectorStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override @Transactional
    public void replaceSource(SourceRecord s, List<Chunk> chunks, List<float[]> embeddings) {
        if (chunks.size() != embeddings.size()) throw new IllegalArgumentException("chunks/embeddings size mismatch");
        UUID sid = UUID.fromString(s.id());
        jdbc.update("""
            INSERT INTO sources (id, title, url, source_type, content_hash, enabled)
            VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT (id) DO UPDATE SET title = EXCLUDED.title, url = EXCLUDED.url,
              source_type = EXCLUDED.source_type, content_hash = EXCLUDED.content_hash, fetched_at = now()
            """, sid, s.title(), s.url(), s.type(), s.contentHash(), s.enabled());
        jdbc.update("DELETE FROM chunks WHERE source_id = ?", sid);
        for (int i = 0; i < chunks.size(); i++) {
            Chunk c = chunks.get(i);
            jdbc.update("""
                INSERT INTO chunks (id, source_id, chunk_index, section, page, text, embedding)
                VALUES (?, ?, ?, ?, ?, ?, ?::vector)
                """, UUID.fromString(c.id()), sid, c.index(), c.section(), c.page(), c.text(),
                    Vectors.toPgLiteral(embeddings.get(i)));
        }
    }

    @Override
    public List<ScoredChunk> search(float[] q, int k) {
        String lit = Vectors.toPgLiteral(q);
        return jdbc.query("""
            SELECT c.id AS cid, c.source_id, c.chunk_index, c.section, c.page, c.text,
                   s.title, s.url, s.source_type, s.content_hash, s.enabled,
                   1 - (c.embedding <=> ?::vector) AS score
            FROM chunks c JOIN sources s ON s.id = c.source_id
            WHERE s.enabled
            ORDER BY c.embedding <=> ?::vector
            LIMIT ?
            """, (rs, n) -> {
                SourceRecord src = new SourceRecord(rs.getString("source_id"), rs.getString("title"), rs.getString("url"),
                        rs.getString("source_type"), rs.getString("content_hash"), rs.getBoolean("enabled"));
                int page = rs.getInt("page");
                Chunk ch = new Chunk(rs.getString("cid"), rs.getString("source_id"), rs.getInt("chunk_index"),
                        rs.getString("section"), rs.wasNull() ? null : page, rs.getString("text"));
                return new ScoredChunk(ch, src, rs.getDouble("score"));
            }, lit, lit, k);
    }

    @Override
    public List<ScoredChunk> searchKeyword(String query, int k) {
        if (query == null || query.isBlank() || k <= 0) return List.of();
        return jdbc.query("""
            SELECT c.id AS cid, c.source_id, c.chunk_index, c.section, c.page, c.text,
                   s.title, s.url, s.source_type, s.content_hash, s.enabled,
                   ts_rank_cd(c.tsv, plainto_tsquery('english', ?)) AS score
            FROM chunks c JOIN sources s ON s.id = c.source_id
            WHERE s.enabled AND c.tsv @@ plainto_tsquery('english', ?)
            ORDER BY score DESC
            LIMIT ?
            """, (rs, n) -> {
                SourceRecord src = new SourceRecord(rs.getString("source_id"), rs.getString("title"), rs.getString("url"),
                        rs.getString("source_type"), rs.getString("content_hash"), rs.getBoolean("enabled"));
                int page = rs.getInt("page");
                Chunk ch = new Chunk(rs.getString("cid"), rs.getString("source_id"), rs.getInt("chunk_index"),
                        rs.getString("section"), rs.wasNull() ? null : page, rs.getString("text"));
                return new ScoredChunk(ch, src, rs.getDouble("score"));
            }, query, query, k);
    }

    @Override
    public List<SourceRecord> listSources() {
        return new ArrayList<>(jdbc.query(
            "SELECT id, title, url, source_type, content_hash, enabled FROM sources ORDER BY title",
            (rs, n) -> new SourceRecord(rs.getString("id"), rs.getString("title"), rs.getString("url"),
                    rs.getString("source_type"), rs.getString("content_hash"), rs.getBoolean("enabled"))));
    }

    @Override
    public List<Chunk> listChunks(String sourceId) {
        UUID sid = UUID.fromString(sourceId);
        return jdbc.query("""
            SELECT id, source_id, chunk_index, section, page, text
            FROM chunks WHERE source_id = ?
            ORDER BY chunk_index
            """, (rs, n) -> {
                int page = rs.getInt("page");
                return new Chunk(rs.getString("id"), rs.getString("source_id"), rs.getInt("chunk_index"),
                        rs.getString("section"), rs.wasNull() ? null : page, rs.getString("text"));
            }, sid);
    }

    @Override public void setEnabled(String id, boolean enabled) {
        jdbc.update("UPDATE sources SET enabled = ? WHERE id = ?", enabled, UUID.fromString(id));
    }

    @Override public void deleteSource(String id) { jdbc.update("DELETE FROM sources WHERE id = ?", UUID.fromString(id)); }
}
