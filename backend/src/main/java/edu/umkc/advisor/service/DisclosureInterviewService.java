package edu.umkc.advisor.service;

import edu.umkc.advisor.ai.InterviewPayload;
import edu.umkc.advisor.ai.LlmClient;
import edu.umkc.advisor.ai.ResponseValidator;
import edu.umkc.advisor.dto.AskResponse;
import edu.umkc.advisor.exception.InvalidModelOutputException;
import edu.umkc.advisor.model.*;
import edu.umkc.advisor.repository.DraftRepository;
import edu.umkc.advisor.safety.OutputGuard;
import edu.umkc.advisor.safety.ReferralDecision;
import edu.umkc.advisor.safety.ReferralService;

import java.util.*;
import java.util.regex.Pattern;

/**
 * Conversational, priority-guided interview. The LLM extracts and phrases; Java owns safety, state and validation:
 * referral rules run first, extracted text must be grounded in the researcher's own message, "unknown"/"not applicable"
 * marks require the researcher's own words, and skip/"I don't know" are handled without the model.
 */
public final class DisclosureInterviewService {

    public record Reply(String assistantMessage, List<String> updatedFieldIds, List<String> askedFieldIds,
                        AskResponse.Referral referral, boolean urgent, Progress progress, boolean complete) {}

    public static final int MAX_MESSAGE_CHARS = 3000;
    static final String COMPLETE_MESSAGE =
            "Thank you, that covers every item on my checklist. Some may be marked as not yet known or skipped, and that is "
          + "completely fine. When you're ready, open Review to see what's complete and what could use more detail, or "
          + "generate your preparation summary. You can come back and add or change anything at any time.";

    private static final Pattern UNKNOWN_CUE = Pattern.compile(
            "\\b(not\\s+yet|haven'?t|have\\s+not|hasn'?t|has\\s+not|(?:don'?t|do\\s+not)\\s+(?:really\\s+|actually\\s+)?know|unknown"
          + "|not\\s+(?:really\\s+|quite\\s+|entirely\\s+|completely\\s+)?(?:sure|certain)|unsure|no\\s+idea"
          + "|untested|not\\s+tested|yet\\s+to|never\\s+(?:been\\s+)?tested|no\\s+results|n/a|can'?t\\s+say)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern NA_CUE = Pattern.compile(
            "(not\\s+applicable|n/a|does(?:n'?t|\\s+not)\\s+apply|\\bnone\\b|\\bnothing\\b|\\bnever\\b"
          + "|\\bno\\s+(?:external\\s+|outside\\s+|third[- ]party\\s+)?(?:funding|funds|grants?|sponsors?|collaborators?|agreements?|materials|conflicts?|companies|documents?|publications?|disclosures?|prototype|sales?|offers?)\\b)", Pattern.CASE_INSENSITIVE);
    private static final Pattern SKIP_CUE = Pattern.compile("\\b(skip|pass|rather\\s+not|prefer\\s+not|next\\s+question|move\\s+on|later)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern SKIP_CMD = Pattern.compile(
            "^(skip(\\s+(this|that)(\\s+(one|question))?)?|pass|next|next\\s+question|move\\s+on|let'?s\\s+move\\s+on|come\\s+back(\\s+to\\s+(this|that))?\\s+later|ask\\s+me\\s+later)$");
    private static final Pattern UNKNOWN_CMD = Pattern.compile(
            "^(i\\s+)?(do\\s+not\\s+know|don'?t\\s+know|have\\s+no\\s+idea|no\\s+idea|not\\s+sure|i'?m\\s+not\\s+sure|unsure|unknown|not\\s+yet|not\\s+known\\s+yet|i\\s+don'?t\\s+know\\s+yet)(\\s+(sorry|honestly|yet))?$");

    private final ChecklistService checklist;
    private final ReferralService referral;
    private final LlmClient llm;
    private final ResponseValidator validator;
    private final DraftRepository repo;
    private final String systemPrompt;
    private final int maxTokens;
    private final OutputGuard guard = new OutputGuard();

    public DisclosureInterviewService(ChecklistService checklist, ReferralService referral, LlmClient llm,
                                      ResponseValidator validator, DraftRepository repo, String systemPrompt, int maxTokens) {
        this.checklist = checklist; this.referral = referral; this.llm = llm; this.validator = validator;
        this.repo = repo; this.systemPrompt = systemPrompt; this.maxTokens = maxTokens;
    }

    // ------------------------------------------------------------------ public API

    public Reply start(Draft d) {
        String text; List<String> asked;
        if (d.workflow() == Workflow.UPDATE_EXISTING) {
            text = "Hi! I'm here to help you describe an update to something you've already disclosed. There are no wrong answers. "
                 + "Which disclosure are you updating, and what has changed or improved since you submitted it? Whether a change "
                 + "needs a new disclosure is a call for UMKC staff, but I can help you describe it clearly. "
                 + "This is a prototype, so please stick to non-confidential or sample information for now.";
            asked = List.of("existing_disclosure_ref", "what_changed");
        } else {
            text = "Hi! I'm here to help you talk through your work and get ready for UMKC's invention disclosure process. "
                 + "There are no wrong answers, and early-stage ideas are welcome. To start, in your own words, what have you "
                 + "been working on? A sentence or two is plenty. This is a prototype, so please stick to non-confidential or "
                 + "sample information for now.";
            asked = List.of("summary_general");
        }
        repo.appendTurn(d.id(), Turn.assistant(text, asked));
        return new Reply(text, List.of(), asked, new AskResponse.Referral(false, "NONE", null), false, progress(d), false);
    }

    public Reply handle(Draft d, String message) {
        String msg = message == null ? "" : message.strip();
        if (msg.isEmpty()) throw new IllegalArgumentException("Message is empty.");
        if (msg.length() > MAX_MESSAGE_CHARS) throw new IllegalArgumentException("Message is too long.");
        repo.appendTurn(d.id(), Turn.user(msg));
        List<Turn> turns = repo.turns(d.id());
        List<String> lastAsked = lastAssistantFields(turns);
        Map<String, FieldAnswer> answers = new LinkedHashMap<>(d.answers());
        ReferralDecision rule = referral.evaluate(msg);

        // 1. Mandatory rules run first and cannot be overridden by the model.
        if (rule.blockLlm()) return blocked(d, rule, msg, answers, lastAsked);

        // 2. Deterministic skip / "I don't know" handling.
        String norm = msg.toLowerCase().replaceAll("[^a-z' ]", " ").replaceAll("\\s+", " ").strip();
        boolean skip = SKIP_CMD.matcher(norm).matches(), unknown = UNKNOWN_CMD.matcher(norm).matches();
        if ((skip || unknown) && !lastAsked.isEmpty()) {
            List<String> changed = new ArrayList<>();
            for (String id : lastAsked) {
                var f = checklist.find(id);
                if (f.isEmpty() || !f.get().appliesTo(d.workflow())) continue;
                FieldAnswer old = answers.getOrDefault(id, FieldAnswer.EMPTY);
                if (old.hasText()) continue;
                answers.put(id, new FieldAnswer("", skip ? ResearcherMark.SKIPPED : ResearcherMark.NOT_YET_KNOWN));
                changed.add(id);
            }
            repo.saveAnswers(d.id(), answers);
            int turnsSoFar = (int) turns.stream().filter(t -> t.role().equals("ASSISTANT")).count();
            String firstChanged = changed.isEmpty() ? null : changed.get(0);
            String ack = skip ? ConversationStyle.skipAck(turnsSoFar) : ConversationStyle.unknownAck(firstChanged);
            String preferred = !skip && firstChanged != null ? ConversationStyle.COMPANION.get(firstChanged) : null;
            return finish(d, answers, ack, List.of(), changed, rule, null, preferred);
        }

        // 3. LLM extraction + next question, validated in Java.
        InterviewPayload payload = callModel(d, answers, turns, lastAsked, msg);
        List<String> updated = payload == null ? List.of() : applyUpdates(d, answers, payload.updates(), msg);
        repo.saveAnswers(d.id(), answers);
        String text = payload == null
                ? "Thanks, I've saved that in our conversation. I had trouble sorting it into your disclosure fields automatically, "
                + "so you may want to check or add it later under Review."
                : payload.assistantMessage();
        List<String> next = payload == null ? List.of() : payload.nextFieldIds();
        return finish(d, answers, text, next, updated, rule, payload, null);
    }

    public Reply skip(Draft d, String fieldId) {
        var f = checklist.find(fieldId).filter(x -> x.appliesTo(d.workflow()))
                .orElseThrow(() -> new IllegalArgumentException("Unknown field."));
        Map<String, FieldAnswer> answers = new LinkedHashMap<>(d.answers());
        if (!answers.getOrDefault(f.id(), FieldAnswer.EMPTY).hasText()) answers.put(f.id(), new FieldAnswer("", ResearcherMark.SKIPPED));
        repo.saveAnswers(d.id(), answers);
        return finish(d, answers, ConversationStyle.skipAck(repo.turns(d.id()).size()), List.of(), List.of(f.id()), referral.evaluate(""), null, null);
    }

    public Progress progress(Draft d) { return progress(d.workflow(), d.answers()); }

    public Progress progress(Workflow w, Map<String, FieldAnswer> answers) {
        int total = 0, na = 0, addressed = 0;
        for (ChecklistField f : checklist.applicable(w)) {
            total++;
            FieldAnswer a = answers.getOrDefault(f.id(), FieldAnswer.EMPTY);
            if (a.mark() == ResearcherMark.NOT_APPLICABLE) { na++; continue; }
            if (a.isAddressed()) addressed++;
        }
        return Progress.of(addressed, total, na, 0);
    }

    public List<ChecklistField> remaining(Workflow w, Map<String, FieldAnswer> answers) {
        return checklist.applicable(w).stream().filter(f -> !answers.getOrDefault(f.id(), FieldAnswer.EMPTY).isAddressed()).toList();
    }

    // ------------------------------------------------------------------ internals

    private Reply blocked(Draft d, ReferralDecision rule, String msg, Map<String, FieldAnswer> answers, List<String> lastAsked) {
        boolean imminent = rule.primary() == ReferralDecision.Trigger.IMMINENT_DISCLOSURE;
        String text;
        List<String> updated = List.of();
        if (imminent) {
            // Keep the researcher's own sentences, verbatim, so nothing is lost. No model involved.
            String sentences = String.join(" ", referral.disclosureSentences(msg));
            if (!sentences.isBlank() && checklist.find("public_disclosures_list").filter(f -> f.appliesTo(d.workflow())).isPresent()) {
                FieldAnswer old = answers.getOrDefault("public_disclosures_list", FieldAnswer.EMPTY);
                String merged = old.hasText() && !old.text().contains(sentences) ? old.text() + "\n" + sentences
                        : old.hasText() ? old.text() : sentences;
                answers.put("public_disclosures_list", new FieldAnswer(merged, ResearcherMark.NONE));
                repo.saveAnswers(d.id(), answers);
                updated = List.of("public_disclosures_list");
            }
            text = rule.blockingMessage() + "\n\nYour progress is saved. Whenever you're ready, we can pick up where we left off.";
        } else {
            text = rule.blockingMessage() + " Whenever you're ready, we can continue with questions about your work.";
        }
        repo.appendTurn(d.id(), Turn.assistant(text, lastAsked));
        return new Reply(text, updated, lastAsked,
                new AskResponse.Referral(imminent, rule.primary().name(), imminent ? rule.blockingMessage() : null),
                imminent, progress(d.workflow(), answers), false);
    }

    private InterviewPayload callModel(Draft d, Map<String, FieldAnswer> answers, List<Turn> turns, List<String> lastAsked, String msg) {
        String prompt = buildPrompt(d, answers, turns, lastAsked, msg);
        try {
            return validator.parseInterview(llm.complete(systemPrompt, prompt, maxTokens));
        } catch (InvalidModelOutputException first) {
            try {
                return validator.parseInterview(llm.complete(systemPrompt,
                        prompt + "\n\nYour previous reply did not match the JSON schema. Return only the JSON object.", maxTokens));
            } catch (InvalidModelOutputException second) {
                return null;
            }
        }
    }

    private String buildPrompt(Draft d, Map<String, FieldAnswer> answers, List<Turn> turns, List<String> lastAsked, String msg) {
        StringBuilder sb = new StringBuilder("<remaining_fields>\n");
        List<ChecklistField> rem = remaining(d.workflow(), answers);
        for (int i = 0; i < Math.min(8, rem.size()); i++) {
            ChecklistField f = rem.get(i);
            sb.append(f.id()).append(" | ").append(f.label()).append(" | ").append(f.description()).append('\n');
        }
        sb.append("</remaining_fields>\n<already_collected>\n");
        for (ChecklistField f : checklist.applicable(d.workflow())) {
            FieldAnswer a = answers.getOrDefault(f.id(), FieldAnswer.EMPTY);
            if (a.isAddressed()) sb.append(f.id()).append(": ").append(a.hasText() ? clip(clean(a.text()), 140) : a.mark().name()).append('\n');
        }
        sb.append("</already_collected>\n<last_questions_asked_about>").append(String.join(", ", lastAsked))
          .append("</last_questions_asked_about>\n<recent_conversation>\n");
        int from = Math.max(0, turns.size() - 7);
        for (int i = from; i < turns.size() - 1; i++) {   // the newest turn is the message itself
            Turn t = turns.get(i);
            sb.append(t.role().equals("USER") ? "RESEARCHER: " : "ASSISTANT: ").append(clip(clean(t.content()), 400)).append('\n');
        }
        sb.append("</recent_conversation>\n<avoid_starting_with>");
        int shown = 0;
        for (int i = turns.size() - 1; i >= 0 && shown < 3; i--) {
            if (!turns.get(i).role().equals("ASSISTANT")) continue;
            String[] w = clean(turns.get(i).content()).strip().split("\\s+");
            sb.append(shown++ > 0 ? " | " : "").append(String.join(" ", Arrays.copyOf(w, Math.min(4, w.length))));
        }
        sb.append("</avoid_starting_with>\n<researcher_message>\n").append(clean(msg)).append("\n</researcher_message>");
        return sb.toString();
    }

    private List<String> applyUpdates(Draft d, Map<String, FieldAnswer> answers, List<InterviewPayload.Update> updates, String msg) {
        List<String> changed = new ArrayList<>();
        for (InterviewPayload.Update u : updates) {
            Optional<ChecklistField> f = checklist.find(u.fieldId()).filter(x -> x.appliesTo(d.workflow()));
            if (f.isEmpty()) continue;
            String text = u.text() == null ? "" : u.text().strip();
            ResearcherMark mark = u.mark();
            // Marks require the researcher's own words.
            if (mark == ResearcherMark.NOT_YET_KNOWN && !UNKNOWN_CUE.matcher(msg).find()) mark = ResearcherMark.NONE;
            if (mark == ResearcherMark.NOT_APPLICABLE && !(f.get().notApplicableAllowed() && NA_CUE.matcher(msg).find())) mark = ResearcherMark.NONE;
            if (mark == ResearcherMark.SKIPPED && !SKIP_CUE.matcher(msg).find()) mark = ResearcherMark.NONE;
            // Text must be the researcher's own words: reject anything not grounded in the message.
            if (!text.isEmpty() && (TextOverlap.coverage(text, msg) < 0.85 || !GroundingCheck.unsupportedNumbers(text, msg, "").isEmpty())) text = "";
            if (text.isEmpty() && mark == ResearcherMark.NONE) continue;

            FieldAnswer old = answers.getOrDefault(f.get().id(), FieldAnswer.EMPTY);
            String merged = old.text();
            if (!text.isEmpty()) merged = merged.isEmpty() ? text : normalize(merged).contains(normalize(text)) ? merged : merged + "\n" + text;
            ResearcherMark newMark = mark != ResearcherMark.NONE ? mark : (text.isEmpty() ? old.mark() : ResearcherMark.NONE);
            answers.put(f.get().id(), new FieldAnswer(merged, newMark));
            changed.add(f.get().id());
        }
        return changed;
    }

    /**
     * Builds the reply. Three cases: (1) nothing left to ask, (2) the model's output is usable, (3) a deterministic
     * acknowledgement (skip, "I don't know", or model failure) followed by the next question. In case (2) the model's
     * message is only used if it targets a field that is still open (or was just discussed), so it cannot re-ask known
     * information, and it must read like conversation rather than a form.
     */
    private Reply finish(Draft d, Map<String, FieldAnswer> answers, String modelText, List<String> modelNext,
                         List<String> updated, ReferralDecision rule, InterviewPayload payload, String preferredNext) {
        List<ChecklistField> remaining = remaining(d.workflow(), answers);
        boolean complete = remaining.isEmpty();
        Set<String> allowedNext = new HashSet<>();
        remaining.forEach(f -> allowedNext.add(f.id()));
        allowedNext.addAll(updated);                       // a clarifying follow-up on what was just said is allowed
        List<String> next = new ArrayList<>();
        for (String id : modelNext) if (allowedNext.contains(id) && !next.contains(id) && next.size() < 2) next.add(id);

        List<Turn> history = repo.turns(d.id());
        int assistantTurns = (int) history.stream().filter(t -> t.role().equals("ASSISTANT")).count();
        List<String> previous = new ArrayList<>();
        for (int i = history.size() - 1; i >= 0 && previous.size() < 2; i--) if (history.get(i).role().equals("ASSISTANT")) previous.add(history.get(i).content());

        String text;
        if (complete) {
            text = COMPLETE_MESSAGE;
            next = List.of();
        } else if (payload != null) {
            String base = ConversationStyle.polish(cap(modelText), previous);
            ReferralService.Contact c = referral.contact();
            Set<String> allowed = new HashSet<>();
            if (c.url() != null) allowed.add(c.url());
            if (c.email() != null) allowed.add(c.email());
            boolean usable = !base.isBlank() && !next.isEmpty() && !ConversationStyle.isFormLike(base)
                    && !guard.scan(base, allowed).violated() && !repeatsRecent(d, base);
            if (usable) {
                text = base;
            } else {
                ChecklistField nf = remaining.get(0);
                text = ConversationStyle.bridge(assistantTurns) + " " + nf.starterQuestion();
                next = List.of(nf.id());
            }
        } else {
            ChecklistField nf = remaining.get(0);
            String question = nf.starterQuestion();
            if (preferredNext != null) {
                for (ChecklistField f : remaining) {
                    if (f.id().equals(preferredNext)) {
                        nf = f;
                        String soft = ConversationStyle.softQuestion(f.id());
                        question = soft != null ? soft : f.starterQuestion();
                        break;
                    }
                }
            }
            text = modelText + " " + question;
            next = List.of(nf.id());
        }
        boolean needsReferral = rule.referralRequired();
        if (needsReferral && rule.referralNote() != null) text = text + "\n\n" + rule.referralNote();
        repo.appendTurn(d.id(), Turn.assistant(text, next));
        return new Reply(text, updated, next,
                new AskResponse.Referral(needsReferral, rule.primary().name(), needsReferral ? rule.referralNote() : null),
                false, progress(d.workflow(), answers), complete);
    }

    private boolean repeatsRecent(Draft d, String text) {
        String n = normalize(text);
        List<Turn> turns = repo.turns(d.id());
        int seen = 0;
        for (int i = turns.size() - 1; i >= 0 && seen < 3; i--) {
            if (!turns.get(i).role().equals("ASSISTANT")) continue;
            seen++;
            if (normalize(turns.get(i).content()).startsWith(n) || n.startsWith(normalize(turns.get(i).content()))) return true;
        }
        return false;
    }

    private static List<String> lastAssistantFields(List<Turn> turns) {
        for (int i = turns.size() - 1; i >= 0; i--) if (turns.get(i).role().equals("ASSISTANT")) return turns.get(i).fieldIds();
        return List.of();
    }

    /** At most two questions per message. */
    static String cap(String text) {
        int q = 0;
        for (int i = 0; i < text.length(); i++) if (text.charAt(i) == '?' && ++q == 2) return text.substring(0, i + 1).strip();
        return text.strip();
    }

    private static String normalize(String s) { return s.toLowerCase().replaceAll("[^a-z0-9]+", " ").strip(); }
    private static String clean(String s) { return s.replace("<", "\u2039").replace(">", "\u203a"); }
    private static String clip(String s, int n) { return s.length() <= n ? s : s.substring(0, n) + "…"; }
}
