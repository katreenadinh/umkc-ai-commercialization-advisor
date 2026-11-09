package edu.umkc.advisor.controller;

import edu.umkc.advisor.dto.AskResponse;
import edu.umkc.advisor.exception.NotFoundException;
import edu.umkc.advisor.model.*;
import edu.umkc.advisor.repository.DraftRepository;
import edu.umkc.advisor.service.*;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.*;
import java.util.regex.Pattern;

/** All draft endpoints. Identify the browser with header X-Owner-Token (a random UUID the frontend generates once). */
@RestController
@RequestMapping("/api")
public class DraftController {
    private static final Pattern UUID_RE = Pattern.compile("^[0-9a-fA-F-]{36}$");

    private final DraftService drafts;
    private final DisclosureInterviewService interview;
    private final ChecklistService checklist;
    private final DraftRepository repo;
    private final NotesOrganizer organizer;

    public DraftController(DraftService drafts, DisclosureInterviewService interview, ChecklistService checklist, DraftRepository repo,
                           NotesOrganizer organizer) {
        this.drafts = drafts; this.interview = interview; this.checklist = checklist; this.repo = repo; this.organizer = organizer;
    }

    public record CreateRequest(Workflow workflow, String title) {}
    public record MessageRequest(String message) {}
    public record AnswerRequest(String text, ResearcherMark mark) {}
    public record SummaryRequest(String markdown) {}
    public record FieldView(String id, String section, String label, String description, String whyItHelps, boolean officialFormField,
                            String origin, String sourceRef, String humanReviewNote, boolean notApplicableAllowed, int priority,
                            String text, ResearcherMark mark) {}
    public record SectionView(String id, String title, InformationStatus status, String statusLabel, List<String> fieldIds) {}
    public record DraftView(String id, String title, Workflow workflow, List<FieldView> fields, List<SectionView> sections, boolean organizing,
                            List<Turn> transcript, Progress progress, String summaryMd, String updatedAt) {}
    public record ListItem(String id, String title, Workflow workflow, Progress progress, String updatedAt) {}
    public record AnswerResult(DraftView draft, AskResponse.Referral referral) {}
    public record CreateResult(DraftView draft, DisclosureInterviewService.Reply opening) {}

    @GetMapping("/checklist")
    public Map<String, Object> checklist() {
        return Map.of("formVersion", checklist.formVersion(), "inspectionStatus", checklist.inspectionStatus(), "fields", checklist.all());
    }

    @PostMapping("/drafts")
    public CreateResult create(@RequestHeader("X-Owner-Token") String token, @RequestBody CreateRequest req) {
        String owner = owner(token);
        Draft d = drafts.create(owner, req.workflow() == null ? Workflow.NEW_INVENTION : req.workflow(), req.title());
        var opening = interview.start(d);
        return new CreateResult(view(d), opening);
    }

    @GetMapping("/drafts")
    public List<ListItem> list(@RequestHeader("X-Owner-Token") String token) {
        return drafts.list(owner(token)).stream().map(d -> new ListItem(d.id(),
                d.answer("title").hasText() ? d.answer("title").text() : d.title(), d.workflow(), interview.progress(d), d.updatedAt().toString())).toList();
    }

    @GetMapping("/drafts/{id}")
    public DraftView get(@RequestHeader("X-Owner-Token") String token, @PathVariable String id) {
        return view(drafts.require(id, owner(token)));
    }

    @DeleteMapping("/drafts/{id}")
    public ResponseEntity<Void> delete(@RequestHeader("X-Owner-Token") String token, @PathVariable String id) {
        drafts.delete(id, owner(token));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/drafts/{id}/interview/messages")
    public DisclosureInterviewService.Reply message(@RequestHeader("X-Owner-Token") String token, @PathVariable String id, @RequestBody MessageRequest req) {
        String owner = owner(token);
        var reply = interview.handle(drafts.require(id, owner), req.message());
        organizer.organizeLater(id, owner);
        return reply;
    }

    @PostMapping("/drafts/{id}/interview/skip/{fieldId}")
    public DisclosureInterviewService.Reply skip(@RequestHeader("X-Owner-Token") String token, @PathVariable String id, @PathVariable String fieldId) {
        return interview.skip(drafts.require(id, owner(token)), fieldId);
    }

    @PutMapping("/drafts/{id}/answers/{fieldId}")
    public AnswerResult answer(@RequestHeader("X-Owner-Token") String token, @PathVariable String id, @PathVariable String fieldId, @RequestBody AnswerRequest req) {
        Draft d = drafts.updateAnswer(id, owner(token), fieldId, req.text(), req.mark());
        return new AnswerResult(view(d), drafts.referralFor(d));
    }

    @PostMapping("/drafts/{id}/review")
    public DisclosureReviewService.ReviewResult review(@RequestHeader("X-Owner-Token") String token, @PathVariable String id) {
        return drafts.review(id, owner(token));
    }

    @PostMapping("/drafts/{id}/summary")
    public Map<String, String> generate(@RequestHeader("X-Owner-Token") String token, @PathVariable String id) {
        return Map.of("markdown", drafts.generateSummary(id, owner(token)));
    }

    @PutMapping("/drafts/{id}/summary")
    public ResponseEntity<Void> saveSummary(@RequestHeader("X-Owner-Token") String token, @PathVariable String id, @RequestBody SummaryRequest req) {
        drafts.saveSummary(id, owner(token), req.markdown());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/drafts/{id}/export")
    public ResponseEntity<byte[]> export(@RequestHeader("X-Owner-Token") String token, @PathVariable String id,
                                         @RequestParam(defaultValue = "docx") String format) throws IOException {
        String owner = owner(token);
        if ("md".equalsIgnoreCase(format))
            return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"preparation-summary.md\"")
                    .contentType(MediaType.parseMediaType("text/markdown; charset=utf-8")).body(drafts.exportMarkdown(id, owner).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"preparation-summary.docx\"")
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.wordprocessingml.document"))
                .body(drafts.exportDocx(id, owner));
    }

    private DraftView view(Draft d) {
        List<FieldView> fs = new ArrayList<>();
        for (ChecklistField f : checklist.applicable(d.workflow())) {
            FieldAnswer a = d.answer(f.id());
            fs.add(new FieldView(f.id(), f.section(), f.label(), f.description(), f.whyItHelps(), f.officialFormField(), f.origin(), f.sourceRef(),
                    f.humanReviewNote(), f.notApplicableAllowed(), f.priority(), a.text(), a.mark()));
        }
        List<SectionView> secs = new ArrayList<>();
        for (ChecklistService.Section s : checklist.sections(d.workflow())) {
            InformationStatus status = InformationStatus.of(s.fields(), d::answer);
            secs.add(new SectionView(s.section().id(), s.section().title(), status, status.label,
                    s.fields().stream().map(ChecklistField::id).toList()));
        }
        return new DraftView(d.id(), d.answer("title").hasText() ? d.answer("title").text() : d.title(), d.workflow(), fs, secs, organizer.isOrganizing(d.id()), repo.turns(d.id()),
                interview.progress(d), d.summaryMd(), d.updatedAt().toString());
    }

    private static String owner(String token) {
        if (token == null || !UUID_RE.matcher(token).matches()) throw new NotFoundException("Draft not found.");
        return token;
    }
}
