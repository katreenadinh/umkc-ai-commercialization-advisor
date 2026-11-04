package edu.umkc.advisor.service;

import edu.umkc.advisor.dto.AskResponse;
import edu.umkc.advisor.exception.NotFoundException;
import edu.umkc.advisor.model.*;
import edu.umkc.advisor.repository.DraftRepository;
import edu.umkc.advisor.safety.ReferralDecision;
import edu.umkc.advisor.safety.ReferralService;

import java.time.Instant;
import java.util.*;

/** Draft lifecycle. Every operation requires the owner token; unknown or foreign drafts look identical (not found). */
public final class DraftService {
    public static final int MAX_FIELD_CHARS = 4000;
    public static final int MAX_SUMMARY_CHARS = 60_000;

    private final DraftRepository repo;
    private final ChecklistService checklist;
    private final DisclosureReviewService review;
    private final DraftSummaryBuilder summary;
    private final DocxExporter docx = new DocxExporter();
    private final ReferralService referral;
    private final List<DraftSummaryBuilder.SourceRef> sources;
    private final int maxDrafts;

    public DraftService(DraftRepository repo, ChecklistService checklist, DisclosureReviewService review, DraftSummaryBuilder summary,
                        ReferralService referral, List<DraftSummaryBuilder.SourceRef> sources, int maxDraftsPerOwner) {
        this.repo = repo; this.checklist = checklist; this.review = review; this.summary = summary;
        this.referral = referral; this.sources = sources; this.maxDrafts = maxDraftsPerOwner;
    }

    public Draft create(String owner, Workflow workflow, String title) {
        if (repo.countByOwner(owner) >= maxDrafts) throw new IllegalStateException("Draft limit reached (" + maxDrafts + "). Please delete an old draft first.");
        String t = title == null || title.isBlank() ? "Untitled" : title.strip();
        if (t.length() > 120) t = t.substring(0, 120);
        return repo.create(new Draft(UUID.randomUUID().toString(), owner, t, workflow, Map.of(), null, null, Instant.now()));
    }

    public Draft require(String id, String owner) {
        return repo.find(id, owner).orElseThrow(() -> new NotFoundException("Draft not found."));
    }

    public List<Draft> list(String owner) { return repo.listByOwner(owner); }

    public void delete(String id, String owner) { require(id, owner); repo.delete(id); }

    /** Replaces the saved answer for one field (the researcher's edit). Empty text with no mark clears the field. */
    public Draft updateAnswer(String id, String owner, String fieldId, String text, ResearcherMark mark) {
        Draft d = require(id, owner);
        ChecklistField f = checklist.find(fieldId).filter(x -> x.appliesTo(d.workflow()))
                .orElseThrow(() -> new IllegalArgumentException("Unknown field."));
        String t = text == null ? "" : text.strip();
        if (t.length() > MAX_FIELD_CHARS) throw new IllegalArgumentException("Answer is too long (max " + MAX_FIELD_CHARS + " characters).");
        ResearcherMark m = mark == null ? ResearcherMark.NONE : mark;
        if (m == ResearcherMark.NOT_APPLICABLE && !f.notApplicableAllowed()) throw new IllegalArgumentException("This item cannot be marked not applicable.");
        Map<String, FieldAnswer> answers = new LinkedHashMap<>(d.answers());
        if (t.isEmpty() && m == ResearcherMark.NONE) answers.remove(fieldId); else answers.put(fieldId, new FieldAnswer(t, m));
        repo.saveAnswers(id, answers);
        if (fieldId.equals("title") && !t.isEmpty()) repo.saveTitle(id, t.length() > 120 ? t.substring(0, 120) : t);
        return require(id, owner);
    }

    public DisclosureReviewService.ReviewResult review(String id, String owner) {
        Draft d = require(id, owner);
        var r = review.review(d);
        repo.saveReview(id, review.toJson(r));
        return r;
    }

    /** Regenerates the preparation summary from the latest saved answers (and the latest review if it is still current). */
    public String generateSummary(String id, String owner) {
        Draft d = require(id, owner);
        var cached = review.parse(d.reviewJson());
        String md = summary.build(d, review.isFresh(d, cached) ? cached : null, sources);
        repo.saveSummary(id, md);
        return md;
    }

    public void saveSummary(String id, String owner, String markdown) {
        require(id, owner);
        if (markdown == null || markdown.isBlank()) throw new IllegalArgumentException("Summary is empty.");
        if (markdown.length() > MAX_SUMMARY_CHARS) throw new IllegalArgumentException("Summary is too long.");
        repo.saveSummary(id, markdown);
    }

    public String exportMarkdown(String id, String owner) { return requireSummary(id, owner); }

    public byte[] exportDocx(String id, String owner) throws java.io.IOException { return docx.export(requireSummary(id, owner)); }

    private String requireSummary(String id, String owner) {
        Draft d = require(id, owner);
        if (d.summaryMd() == null || d.summaryMd().isBlank()) throw new IllegalArgumentException("Generate the summary first.");
        return d.summaryMd();
    }

    /** Mandatory-rule referral over everything the researcher has saved (used after edits). */
    public AskResponse.Referral referralFor(Draft d) {
        StringBuilder all = new StringBuilder();
        d.answers().values().forEach(a -> all.append(a.text()).append('\n'));
        ReferralDecision r = referral.evaluate(all.toString());
        return new AskResponse.Referral(r.referralRequired(), r.primary().name(), r.referralNote());
    }
}
