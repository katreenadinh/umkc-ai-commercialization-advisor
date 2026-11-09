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
            "Thank you — that covers the notes I use for a first conversation. Some may be marked as not yet known or skipped, "
          + "and that is completely fine. When you're ready, open Review to see what's complete and what could use more detail, or "
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
    private static final Pattern CLOSING_CUE = Pattern.compile(
            "(?i)\\b(goodbye|bye|bye-bye|see you|have a good (?:day|one)|i'?m (?:done|finished)|that'?s (?:all|everything)|talk to you later|catch you later|stop for now|that is all|signing off)\\b");
    private static final Pattern SUMMARY_CUE = Pattern.compile(
            "(?i)\\b(summar(?:y|ize)|how far along|what(?:'s| is) (?:left|missing|remaining|collected)|current status)\\b");
    private static final Pattern ACK_CUE = Pattern.compile(
            "(?i)^(okay|ok|sounds good|sounds great|makes sense|that makes sense|got it|understood|i see|alright|cool|great|good to know|noted|perfect|sure thing|will do|thank you(?: so much)?|thanks(?: a lot)?(?: for (?:that|explaining|the explanation|clarifying))?)(?:[,.]?\\s*(?:thanks|thank you))?[.!]?$");
    private static final Pattern CORRECTION_CUE = Pattern.compile(
            "(?i)\\b(actually|correction|i meant|i mean|not\\s+\\w+,?\\s+(?:it'?s|it is|but)|instead of|scratch that|replace|i made a mistake|rather than)\\b");
    /** Explicit abandonment of the current work, not a later-topic volunteer. */
    private static final Pattern TOPIC_SWITCH = Pattern.compile(
            "\\b(scratch\\s+that|let'?s\\s+start\\s+over|start\\s+over"
          + "|change\\s+(?:the\\s+)?(?:subject|topics?)|switch\\s+(?:the\\s+)?(?:subject|topics?)"
          + "|forget\\s+(?:all\\s+)?(?:of\\s+)?what\\s+i\\s+said|ignore\\s+what\\s+i\\s+(?:just\\s+)?said"
          + "|that\\s+was\\s+(?:the\\s+)?wrong\\s+(?:idea|invention|topic|one)"
          + "|different\\s+(?:subject|topic|idea|invention|work)|talk about (?:a |an )?(?:different|new|another)"
          + "|something else entirely|new\\s+topic|never\\s*mind(?:\\s+that)?)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern AWAITING_NEW_TOPIC = Pattern.compile(
            "(?i)(new topic|new invention|start over|cleared the earlier notes"
          + "|different (?:subject|topic|invention)|share the details of your new)");
    private static final Pattern WORK_DESCRIPTION = Pattern.compile(
            "(?i)\\b(i|we|i'?m|we're)\\s+(made|make|built|building|developed|created|designed|found|researched|have|invented|working on)\\b");

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
            return finish(d, answers, ack, List.of(), changed, rule, null, preferred, msg);
        }

        // 3. Topic change: wipe saved notes so the new work is not merged into the old one.
        boolean switchCue = TOPIC_SWITCH.matcher(msg).find();
        boolean followUpNewWork = awaitingNewTopic(turns) && hasSavedNotes(answers) && !looksLikeGeneralQuestion(msg);
        if (switchCue && looksLikeWorkDescription(stripTopicSwitch(msg))) {
            return resetTopic(d, msg, rule);
        }
        if (switchCue) {
            answers = new LinkedHashMap<>();
            repo.saveAnswers(d.id(), answers);
            repo.saveSummary(d.id(), null);
            repo.saveReview(d.id(), null);
            String firstId = openingField(d);
            InterviewPayload payload = callModel(d, answers, turns, List.of(firstId), msg);
            String text = payload == null
                    ? "Got it. Could you share the details of your new topic or invention?"
                    : payload.assistantMessage();
            return finish(d, answers, text, List.of(firstId), List.of(), rule, payload, null, msg);
        }
        if (followUpNewWork) {
            answers = new LinkedHashMap<>();
            repo.saveAnswers(d.id(), answers);
            repo.saveSummary(d.id(), null);
            repo.saveReview(d.id(), null);
            lastAsked = List.of(openingField(d));
        }

        // 4. LLM extraction + next question, validated in Java.
        InterviewPayload payload = callModel(d, answers, turns, lastAsked, msg);
        List<String> updated = payload == null || looksLikeGeneralQuestion(msg) ? List.of() : applyUpdates(d, answers, payload.updates(), msg);
        repo.saveAnswers(d.id(), answers);
        String text = payload == null
                ? "Thanks, I've saved that in our conversation. I had trouble sorting it into your disclosure fields automatically, "
                + "so you may want to check or add it later under Review."
                : payload.assistantMessage();
        List<String> next = payload == null ? List.of() : payload.nextFieldIds();
        return finish(d, answers, text, next, updated, rule, payload, null, msg);
    }

    public Reply skip(Draft d, String fieldId) {
        var f = checklist.find(fieldId).filter(x -> x.appliesTo(d.workflow()))
                .orElseThrow(() -> new IllegalArgumentException("Unknown field."));
        Map<String, FieldAnswer> answers = new LinkedHashMap<>(d.answers());
        if (!answers.getOrDefault(f.id(), FieldAnswer.EMPTY).hasText()) answers.put(f.id(), new FieldAnswer("", ResearcherMark.SKIPPED));
        repo.saveAnswers(d.id(), answers);
        return finish(d, answers, ConversationStyle.skipAck(repo.turns(d.id()).size()), List.of(), List.of(f.id()), referral.evaluate(""), null, null, "");
    }

    public Progress progress(Draft d) { return progress(d.workflow(), d.answers()); }

    public Progress progress(Workflow w, Map<String, FieldAnswer> answers) {
        int total = 0, na = 0, addressed = 0;
        for (ChecklistField f : checklist.coach(w)) {
            total++;
            FieldAnswer a = answers.getOrDefault(f.id(), FieldAnswer.EMPTY);
            if (a.mark() == ResearcherMark.NOT_APPLICABLE) { na++; continue; }
            if (a.isAddressed()) addressed++;
        }
        return Progress.of(addressed, total, na, 0);
    }

    public List<ChecklistField> remaining(Workflow w, Map<String, FieldAnswer> answers) {
        return checklist.coach(w).stream().filter(f -> !answers.getOrDefault(f.id(), FieldAnswer.EMPTY).isAddressed()).toList();
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
            text = rule.blockingMessage() + "\n\nWhat information, results, or demonstrations do you plan to share at the event, and when? We can note that for the office and continue talking through your work.";
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
        int assistantTurnsSoFar = (int) turns.stream().filter(t -> t.role().equals("ASSISTANT")).count();
        boolean hasAnyDescription = answers.getOrDefault("summary_general", FieldAnswer.EMPTY).hasText()
                || answers.getOrDefault("problem_solved", FieldAnswer.EMPTY).hasText()
                || answers.getOrDefault("technical_description", FieldAnswer.EMPTY).hasText();
        List<ChecklistField> orderedRem = new ArrayList<>(rem);
        if (assistantTurnsSoFar >= 1 || hasAnyDescription) {
            orderedRem.removeIf(f -> f.id().equals("summary_general") || f.id().equals("title"));
            if (orderedRem.isEmpty() && !rem.isEmpty()) orderedRem = new ArrayList<>(rem);
        }
        for (int i = 0; i < Math.min(8, orderedRem.size()); i++) {
            ChecklistField f = orderedRem.get(i);
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
            if (!text.isEmpty() && (TextOverlap.coverage(text, msg) < 0.70 || !GroundingCheck.unsupportedNumbers(text, msg, "").isEmpty())) text = "";
            if (text.isEmpty() && mark == ResearcherMark.NONE) continue;

            FieldAnswer old = answers.getOrDefault(f.get().id(), FieldAnswer.EMPTY);
            String merged = old.text();
            boolean isCorrection = CORRECTION_CUE.matcher(msg).find();
            if (!text.isEmpty()) {
                if (isCorrection || !old.hasText()) {
                    merged = text;
                } else if (!normalize(merged).contains(normalize(text))) {
                    merged = merged + "\n" + text;
                }
            }
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
                         List<String> updated, ReferralDecision rule, InterviewPayload payload, String preferredNext, String msg) {
        List<ChecklistField> remaining = remaining(d.workflow(), answers);
        boolean complete = remaining.isEmpty();
        Set<String> allowedNext = new HashSet<>();
        for (ChecklistField f : checklist.applicable(d.workflow())) {
            FieldAnswer a = answers.getOrDefault(f.id(), FieldAnswer.EMPTY);
            if (!a.isAddressed() || updated.contains(f.id())) {
                allowedNext.add(f.id());
            }
        }
        if (preferredNext != null) allowedNext.add(preferredNext);
        for (String u : updated) {
            String comp = ConversationStyle.COMPANION.get(u);
            if (comp != null) allowedNext.add(comp);
        }
        List<String> next = new ArrayList<>();
        for (String id : modelNext) if (allowedNext.contains(id) && !next.contains(id) && next.size() < 1) next.add(id);

        List<Turn> history = repo.turns(d.id());
        int assistantTurns = (int) history.stream().filter(t -> t.role().equals("ASSISTANT")).count();
        List<String> previous = new ArrayList<>();
        for (int i = history.size() - 1; i >= 0 && previous.size() < 2; i--) if (history.get(i).role().equals("ASSISTANT")) previous.add(history.get(i).content());

        boolean isClosing = CLOSING_CUE.matcher(msg).find();
        boolean isSummary = SUMMARY_CUE.matcher(msg).find();
        boolean isAck = ACK_CUE.matcher(msg.trim()).find();

        String text;
        if (complete) {
            text = COMPLETE_MESSAGE;
            next = List.of();
        } else if (payload != null) {
            String base = ConversationStyle.polish(cap(modelText), previous, msg);
            ReferralService.Contact c = referral.contact();
            Set<String> allowed = new HashSet<>();
            if (c.url() != null) allowed.add(c.url());
            if (c.email() != null) allowed.add(c.email());
            boolean userAskedQuestion = msg.trim().endsWith("?") || looksLikeGeneralQuestion(msg) || !rule.topics().isEmpty();
            boolean hasQuestion = base.contains("?");
            boolean validNext = !next.isEmpty();
            boolean isRepeat = repeatsRecent(d, base);
            boolean guardViolation = guard.scan(base, allowed).violated();
            boolean isForm = ConversationStyle.isFormLike(base);

            if (isClosing) {
                String exp = extractExplanation(base);
                text = (!exp.isBlank() && !guardViolation) ? exp
                        : (!base.isBlank() && !guardViolation && !hasQuestion) ? base
                        : "Goodbye! I've saved our progress. Feel free to come back anytime to continue working on your disclosure notes. Have a great day!";
                next = List.of();
            } else if (isSummary) {
                text = (!base.isBlank() && !guardViolation) ? base : buildDraftSummary(d, answers);
                next = List.of();
            } else if (isAck) {
                text = (!base.isBlank() && !guardViolation && !hasQuestion) ? base
                        : (!base.isBlank() && !guardViolation && !extractExplanation(base).isBlank()) ? extractExplanation(base)
                        : "Sounds good! Whenever you're ready, let me know what you'd like to explore next.";
                next = List.of();
            } else if (userAskedQuestion && !base.isBlank() && !isForm && !guardViolation) {
                String explanation = extractExplanation(base);
                if (!isRepeat && hasQuestion && validNext) {
                    text = base;
                } else if (!explanation.isBlank()) {
                    text = explanation;
                    next = List.of();
                } else {
                    text = base;
                    next = List.of();
                }
            } else {
                boolean invalidTarget = hasQuestion && !modelNext.isEmpty() && next.isEmpty();
                boolean usable = !base.isBlank() && !isForm && !guardViolation && !invalidTarget
                        && (!hasQuestion || (validNext && !isRepeat)) && (assistantTurns > 0 || hasQuestion);
                if (usable) {
                    text = base;
                    if (!hasQuestion) {
                        next = List.of();
                    }
                } else {
                    ChecklistField nf = selectNextField(remaining, assistantTurns, history);
                    String soft = nf != null ? ConversationStyle.softQuestion(nf.id()) : null;
                    String question = soft != null ? soft : (nf != null ? nf.starterQuestion() : "");
                    text = ConversationStyle.bridge(assistantTurns) + (question.isBlank() ? "" : " " + question);
                    next = nf != null ? List.of(nf.id()) : List.of();
                }
            }
        } else {
            if (isClosing) {
                text = "Goodbye! I've saved our progress. Feel free to come back anytime to continue working on your disclosure notes. Have a great day!";
                next = List.of();
            } else if (isSummary) {
                text = buildDraftSummary(d, answers);
                next = List.of();
            } else if (isAck) {
                text = "Sounds good! Whenever you're ready, let me know what you'd like to explore next.";
                next = List.of();
            } else {
                ChecklistField nf = selectNextField(remaining, assistantTurns, history);
                String question = nf != null ? nf.starterQuestion() : "";
                if (preferredNext != null) {
                    Optional<ChecklistField> pf = checklist.find(preferredNext).filter(x -> x.appliesTo(d.workflow()));
                    if (pf.isPresent()) {
                        nf = pf.get();
                        String soft = ConversationStyle.softQuestion(nf.id());
                        question = soft != null ? soft : nf.starterQuestion();
                    }
                }
                text = modelText + " " + question;
                next = nf != null ? List.of(nf.id()) : List.of();
            }
        }
        boolean needsReferral = rule.referralRequired();
        if (needsReferral && rule.referralNote() != null) {
            text = ConversationStyle.stripRedundantDisclaimer(text) + "\n\n" + rule.referralNote();
        }
        repo.appendTurn(d.id(), Turn.assistant(text, next));
        return new Reply(text, updated, next,
                new AskResponse.Referral(needsReferral, rule.primary().name(), needsReferral ? rule.referralNote() : null),
                false, progress(d.workflow(), answers), complete);
    }

    private ChecklistField selectNextField(List<ChecklistField> remaining, int assistantTurns, List<Turn> history) {
        if (remaining.isEmpty()) return null;
        List<String> recentFields = new ArrayList<>();
        if (history != null) {
            for (int i = history.size() - 1; i >= 0 && recentFields.size() < 4; i--) {
                if (history.get(i).role().equals("ASSISTANT")) {
                    recentFields.addAll(history.get(i).fieldIds());
                }
            }
        }
        for (ChecklistField f : remaining) {
            if (assistantTurns >= 1 && (f.id().equals("summary_general") || f.id().equals("title"))) continue;
            if (!recentFields.contains(f.id())) return f;
        }
        for (ChecklistField f : remaining) {
            if (assistantTurns >= 1 && (f.id().equals("summary_general") || f.id().equals("title"))) continue;
            return f;
        }
        return remaining.get(0);
    }

    private boolean repeatsRecent(Draft d, String text) {
        String n = normalize(text);
        String qCurr = extractQuestion(text);
        String normQCurr = normalize(qCurr);

        List<Turn> turns = repo.turns(d.id());
        int seen = 0;
        for (int i = turns.size() - 1; i >= 0 && seen < 4; i--) {
            Turn t = turns.get(i);
            if (!t.role().equals("ASSISTANT")) continue;
            seen++;
            String past = t.content();
            String normPast = normalize(past);
            String qPast = extractQuestion(past);
            String normQPast = normalize(qPast);

            if (normPast.equals(n) || (!n.isBlank() && normPast.startsWith(n)) || (!normPast.isBlank() && n.startsWith(normPast))) return true;
            if (n.length() > 25 && normPast.contains(n)) return true;
            if (normPast.length() > 25 && n.contains(normPast)) return true;

            if (!normQCurr.isBlank() && !normQPast.isBlank()) {
                if (normQCurr.equals(normQPast) || normPast.contains(normQCurr) || n.contains(normQPast) || isQuestionRepeat(normQCurr, normQPast)) {
                    return true;
                }
            }
        }
        return false;
    }

    static String extractQuestion(String text) {
        if (text == null || !text.contains("?")) return "";
        int lastQ = text.lastIndexOf('?');
        int start = 0;
        for (int i = lastQ - 1; i >= 0; i--) {
            char c = text.charAt(i);
            if (c == '.' || c == '!' || c == '\n' || c == '?') {
                start = i + 1;
                break;
            }
        }
        return text.substring(start, lastQ + 1).strip();
    }

    static String extractExplanation(String text) {
        if (text == null) return "";
        if (!text.contains("?")) return text.strip();
        int lastQ = text.lastIndexOf('?');
        int start = 0;
        for (int i = lastQ - 1; i >= 0; i--) {
            char c = text.charAt(i);
            if (c == '.' || c == '!' || c == '\n' || c == '?') {
                start = i + 1;
                break;
            }
        }
        return text.substring(0, start).strip();
    }

    static boolean isQuestionRepeat(String q1, String q2) {
        if (q1 == null || q2 == null || q1.isBlank() || q2.isBlank()) return false;
        if (q1.equals(q2)) return true;
        Set<String> stop = Set.of("could", "you", "tell", "me", "a", "little", "more", "about", "what", "how", "why", "who", "where", "which", "when", "the", "and", "in", "your", "to", "do", "does", "is", "are", "of", "for", "on", "it", "they", "this", "that");
        List<String> words1 = new ArrayList<>();
        for (String w : q1.split("\\s+")) {
            if (w.length() > 2 && !stop.contains(w)) words1.add(stem(w));
        }
        List<String> words2 = new ArrayList<>();
        for (String w : q2.split("\\s+")) {
            if (w.length() > 2 && !stop.contains(w)) words2.add(stem(w));
        }
        if (words1.isEmpty() || words2.isEmpty()) return false;
        int common = 0;
        for (String w1 : words1) {
            for (String w2 : words2) {
                if (w1.equals(w2)) {
                    common++;
                    break;
                }
            }
        }
        double sim1 = (double) common / words1.size();
        double sim2 = (double) common / words2.size();
        return (sim1 >= 0.75 && sim2 >= 0.60) || (sim2 >= 0.75 && sim1 >= 0.60);
    }

    private static String stem(String w) {
        if (w.endsWith("ing") && w.length() > 5) return w.substring(0, w.length() - 3);
        if (w.endsWith("ed") && w.length() > 4) return w.substring(0, w.length() - 2);
        if (w.endsWith("s") && w.length() > 3) return w.substring(0, w.length() - 1);
        return w;
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

    // ---- used by AdvisorService to route general IP/policy questions away from the interview ---
    private static final Pattern GUIDE_QUESTION = Pattern.compile(
            "^(what(?:'?s|s)?|how|who|when|why|where|which|can|could|should|is|are|do|does|would|will|explain|define|tell\\s+me)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern GUIDE_TOPIC = Pattern.compile(
            "\\b(patent\\w*|disclos\\w*|invent\\w*|licen[sc]\\w*|commerc\\w*|prior\\s+art"
          + "|preprint\\w*|trl|royalt\\w*|own(?:s|ed|ership)|copyright\\w*|trademark\\w*|tech(?:nology)?\\s*transfer"
          + "|intellectual\\s+property|\\bip\\b|otts?\\b|office|umkc|university|publish\\w*"
          + "|conference|public(?:ly)?\\s+shar\\w*|prototype\\w*|submit\\w*|require\\w*|form\\w*)\\b",
            Pattern.CASE_INSENSITIVE);

    static boolean looksLikeGeneralQuestion(String msg) {
        if (msg == null) return false;
        String t = msg.strip();
        if (t.length() < 8 || !GUIDE_TOPIC.matcher(t).find()) return false;
        int descriptive = 0;
        for (String part : t.split("(?<=[.!?])\\s+|\\n+")) {
            String s = part.strip();
            if (s.isEmpty()) continue;
            boolean question = s.endsWith("?") || GUIDE_QUESTION.matcher(s).find();
            if (!question && s.length() > 20) descriptive++;
        }
        if (descriptive > 0) return false;
        return GUIDE_QUESTION.matcher(t).find() || (t.endsWith("?") && t.length() < 280);
    }

    static String stripTopicSwitch(String msg) {
        String rest = TOPIC_SWITCH.matcher(msg).replaceAll(" ");
        rest = rest.replaceAll("(?i)^\\s*(actually|ok(?:ay)?|well|sorry|so|um|uh)[,.\\s]+", "");
        return rest.replaceAll("\\s+", " ").replaceAll("^[\\s,;:—-]+", "").strip();
    }

    static boolean looksLikeWorkDescription(String rest) {
        if (rest == null || rest.length() < 24) return false;
        return WORK_DESCRIPTION.matcher(rest).find() || rest.length() > 80;
    }

    static boolean awaitingNewTopic(List<Turn> turns) {
        for (int i = turns.size() - 1; i >= 0; i--) {
            if (turns.get(i).role().equals("ASSISTANT")) return AWAITING_NEW_TOPIC.matcher(turns.get(i).content()).find();
        }
        return false;
    }

    private static boolean hasSavedNotes(Map<String, FieldAnswer> answers) {
        return answers.values().stream().anyMatch(FieldAnswer::isAddressed);
    }

    private static String openingField(Draft d) {
        return d.workflow() == Workflow.UPDATE_EXISTING ? "what_changed" : "summary_general";
    }

    private Reply resetTopic(Draft d, String msg, ReferralDecision rule) {
        Map<String, FieldAnswer> answers = new LinkedHashMap<>();
        List<String> changed = new ArrayList<>();
        String rest = stripTopicSwitch(msg);
        String firstId = openingField(d);
        if (!rest.isBlank()) {
            answers.put(firstId, new FieldAnswer(rest, ResearcherMark.NONE));
            changed.add(firstId);
        }
        repo.saveAnswers(d.id(), answers);
        repo.saveSummary(d.id(), null);
        repo.saveReview(d.id(), null);
        String ack = rest.isBlank()
                ? "No problem, we'll start over. I've cleared the earlier notes."
                : "Got it — we'll start over with this. I've cleared the earlier notes.";
        return finish(d, answers, ack, List.of(), changed, rule, null, null, msg);
    }

    private String buildDraftSummary(Draft d, Map<String, FieldAnswer> answers) {
        StringBuilder sb = new StringBuilder("Here is a summary of what we have in your notes so far:\n\n");
        int count = 0;
        for (ChecklistField f : checklist.coach(d.workflow())) {
            FieldAnswer a = answers.getOrDefault(f.id(), FieldAnswer.EMPTY);
            if (a.isAddressed()) {
                count++;
                sb.append("- **").append(f.label()).append(":** ")
                  .append(a.hasText() ? a.text() : a.mark() == ResearcherMark.SKIPPED ? "Skipped for now" : "Not yet known")
                  .append("\n");
            }
        }
        if (count == 0) {
            sb.append("We haven't recorded specific disclosure details yet.\n");
        }
        sb.append("\nWhenever you're ready, let me know what you'd like to discuss next.");
        return sb.toString();
    }
}
