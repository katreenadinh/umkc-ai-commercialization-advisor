package edu.umkc.advisor.repository;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.umkc.advisor.model.*;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.*;

/** PostgreSQL implementation. Draft text is never logged. */
public class JdbcDraftRepository implements DraftRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    public JdbcDraftRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override public Draft create(Draft d) {
        jdbc.update("INSERT INTO drafts (id, owner_token, title, workflow, answers) VALUES (?, ?, ?, ?, ?::jsonb)",
                UUID.fromString(d.id()), UUID.fromString(d.ownerToken()), d.title(), d.workflow().name(), json(d.answers()));
        return find(d.id(), d.ownerToken()).orElseThrow();
    }

    @Override public Optional<Draft> find(String id, String owner) {
        UUID uid, uo;
        try { uid = UUID.fromString(id); uo = UUID.fromString(owner); } catch (IllegalArgumentException e) { return Optional.empty(); }
        List<Draft> l = jdbc.query("SELECT * FROM drafts WHERE id = ? AND owner_token = ?", (rs, n) -> map(rs), uid, uo);
        return l.stream().findFirst();
    }

    @Override public List<Draft> listByOwner(String owner) {
        try { return jdbc.query("SELECT * FROM drafts WHERE owner_token = ? ORDER BY updated_at DESC", (rs, n) -> map(rs), UUID.fromString(owner)); }
        catch (IllegalArgumentException e) { return List.of(); }
    }

    @Override public int countByOwner(String owner) {
        Integer c = jdbc.queryForObject("SELECT count(*) FROM drafts WHERE owner_token = ?", Integer.class, UUID.fromString(owner));
        return c == null ? 0 : c;
    }

    @Override public void saveAnswers(String id, Map<String, FieldAnswer> a) {
        jdbc.update("UPDATE drafts SET answers = ?::jsonb, updated_at = now() WHERE id = ?", json(a), UUID.fromString(id));
    }
    @Override public void saveTitle(String id, String t) { jdbc.update("UPDATE drafts SET title = ?, updated_at = now() WHERE id = ?", t, UUID.fromString(id)); }
    @Override public void saveSummary(String id, String md) { jdbc.update("UPDATE drafts SET summary_md = ?, updated_at = now() WHERE id = ?", md, UUID.fromString(id)); }
    @Override public void saveReview(String id, String j) { jdbc.update("UPDATE drafts SET review = ?::jsonb WHERE id = ?", j, UUID.fromString(id)); }

    @Override public void appendTurn(String id, Turn t) {
        jdbc.update("INSERT INTO interview_turns (id, draft_id, role, content, field_ids) VALUES (?, ?, ?, ?, ?)",
                UUID.randomUUID(), UUID.fromString(id), t.role(), t.content(), String.join(",", t.fieldIds()));
        jdbc.update("UPDATE drafts SET updated_at = now() WHERE id = ?", UUID.fromString(id));
    }

    @Override public List<Turn> turns(String id) {
        return jdbc.query("SELECT role, content, field_ids FROM interview_turns WHERE draft_id = ? ORDER BY created_at, id",
                (rs, n) -> {
                    String f = rs.getString("field_ids");
                    return new Turn(rs.getString("role"), rs.getString("content"),
                            f == null || f.isBlank() ? List.of() : Arrays.asList(f.split(",")));
                }, UUID.fromString(id));
    }

    @Override public void delete(String id) { jdbc.update("DELETE FROM drafts WHERE id = ?", UUID.fromString(id)); }

    private Draft map(java.sql.ResultSet rs) throws java.sql.SQLException {
        try {
            Map<String, FieldAnswer> answers = mapper.readValue(rs.getString("answers"), new TypeReference<Map<String, FieldAnswer>>() {});
            return new Draft(rs.getString("id"), rs.getString("owner_token"), rs.getString("title"), Workflow.valueOf(rs.getString("workflow")),
                    answers, rs.getString("summary_md"), rs.getString("review"), rs.getTimestamp("updated_at").toInstant());
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) { throw new IllegalStateException("Corrupt draft data", e); }
    }

    private String json(Object o) {
        try { return mapper.writeValueAsString(o); } catch (com.fasterxml.jackson.core.JsonProcessingException e) { throw new IllegalStateException(e); }
    }
}
