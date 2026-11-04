package edu.umkc.advisor.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.umkc.advisor.ai.LlmClient;
import edu.umkc.advisor.ai.ResponseValidator;
import edu.umkc.advisor.model.*;
import edu.umkc.advisor.repository.DraftRepository;
import edu.umkc.advisor.safety.ReferralService;

import java.time.Instant;
import java.util.*;

/** Shared fixtures. All data here is SYNTHETIC. */
final class TestKit {
    static final ObjectMapper M = new ObjectMapper();
    static final ReferralService.Contact CONTACT = new ReferralService.Contact("Test Office", "office@example.edu", "https://example.edu/x");

    static final class ScriptedLlm implements LlmClient {
        final Deque<String> replies = new ArrayDeque<>();
        final List<String> prompts = new ArrayList<>();
        ScriptedLlm(String... r) { replies.addAll(Arrays.asList(r)); }
        ScriptedLlm add(String r) { replies.add(r); return this; }
        public String complete(String s, String u, int m) { prompts.add(u); return replies.poll(); }
        int calls() { return prompts.size(); }
    }

    static final class MemRepo implements DraftRepository {
        final Map<String, Draft> drafts = new LinkedHashMap<>();
        final Map<String, List<Turn>> turns = new HashMap<>();
        public Draft create(Draft d) { drafts.put(d.id(), d); turns.put(d.id(), new ArrayList<>()); return d; }
        public Optional<Draft> find(String id, String owner) { return Optional.ofNullable(drafts.get(id)).filter(d -> d.ownerToken().equals(owner)); }
        public List<Draft> listByOwner(String o) { return drafts.values().stream().filter(d -> d.ownerToken().equals(o)).toList(); }
        public int countByOwner(String o) { return listByOwner(o).size(); }
        private void put(String id, java.util.function.UnaryOperator<Draft> f) { drafts.put(id, f.apply(drafts.get(id))); }
        public void saveAnswers(String id, Map<String, FieldAnswer> a) { put(id, d -> new Draft(d.id(), d.ownerToken(), d.title(), d.workflow(), a, d.summaryMd(), d.reviewJson(), Instant.now())); }
        public void saveTitle(String id, String t) { put(id, d -> new Draft(d.id(), d.ownerToken(), t, d.workflow(), d.answers(), d.summaryMd(), d.reviewJson(), Instant.now())); }
        public void saveSummary(String id, String s) { put(id, d -> new Draft(d.id(), d.ownerToken(), d.title(), d.workflow(), d.answers(), s, d.reviewJson(), Instant.now())); }
        public void saveReview(String id, String r) { put(id, d -> new Draft(d.id(), d.ownerToken(), d.title(), d.workflow(), d.answers(), d.summaryMd(), r, Instant.now())); }
        public void appendTurn(String id, Turn t) { turns.get(id).add(t); }
        public List<Turn> turns(String id) { return List.copyOf(turns.get(id)); }
        public void delete(String id) { drafts.remove(id); turns.remove(id); }
        Draft get(String id) { return drafts.get(id); }
    }

    final ChecklistService checklist = ChecklistService.fromClasspath();
    final ReferralService referral = new ReferralService(CONTACT);
    final MemRepo repo = new MemRepo();
    static final String OWNER = "11111111-2222-3333-4444-555555555555";

    Draft newDraft(Workflow w) {
        return repo.create(new Draft(UUID.randomUUID().toString(), OWNER, "Untitled", w, Map.of(), null, null, Instant.now()));
    }

    Draft withAnswers(Draft d, Map<String, FieldAnswer> a) { repo.saveAnswers(d.id(), a); return repo.get(d.id()); }

    DisclosureInterviewService interview(ScriptedLlm llm) {
        return new DisclosureInterviewService(checklist, referral, llm, new ResponseValidator(), repo, "sys", 800);
    }

    DisclosureReviewService reviewer(ScriptedLlm llm) {
        return new DisclosureReviewService(checklist, referral, llm, new ResponseValidator(), "sys", 800);
    }

    static String interviewJson(List<Map<String, String>> updates, String message, String... next) {
        try { return M.writeValueAsString(Map.of("updates", updates, "assistantMessage", message, "nextFieldIds", List.of(next))); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }

    static Map<String, String> upd(String id, String text, String mark) { return Map.of("fieldId", id, "text", text, "mark", mark); }

    static String reviewJson(Map<String, String>... entries) {
        try { return M.writeValueAsString(Map.of("fields", List.of(entries))); } catch (Exception e) { throw new IllegalStateException(e); }
    }

    static Map<String, String> rev(String id, String status, String cur, String missing, String follow) {
        return Map.of("fieldId", id, "status", status, "currentInformation", cur, "missingDetails", missing, "followUpQuestion", follow);
    }

    static FieldAnswer text(String t) { return new FieldAnswer(t, ResearcherMark.NONE); }
}
