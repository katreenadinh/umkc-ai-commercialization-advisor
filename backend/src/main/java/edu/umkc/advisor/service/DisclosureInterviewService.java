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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Conversational, priority-guided interview. The LLM extracts and phrases; Java owns safety, state and validation:
 * referral rules run first, extracted text must be grounded in the researcher's own message, "unknown"/"not applicable"
 * marks require the researcher's own words, and skip/"I don't know" are handled without the model.
 */
public final class DisclosureInterviewService {

    public record Reply(String assistantMessage, List<String> updatedFieldIds, List<String> askedFieldIds,
                        AskResponse.Referral referral, boolean urgent, Progress progress, boolean complete) {}

    public static final int MAX_MESSAGE_CHARS = 3000;
    static final String CORRECTION_ACK = "Thanks for clarifying. I've updated your notes to use the corrected information.";
    static final String OFF_TOPIC_ACK =
            "I mainly help with intellectual property, research commercialization, and invention disclosures, so I can't help with that. "
          + "If you have a question about your research or potential IP, I can help with that.";
    static final String LAB_HELP_ACK =
            "That's outside what I can help with — I don't work through lab calculations or equations. Someone in your department, "
          + "or a lab or safety expert, would be the right person to do that kind of equation. If you have a question "
          + "about your research or potential IP, I can help with that.";
    static final String GREETING_ACK =
            "Hello! I'm here to help with intellectual property, research commercialization, and UMKC's invention disclosure process. "
          + "You can ask a general question or tell me about something you've developed.";
    static final String INVENTORSHIP_ANSWER =
            "Not necessarily. Being involved in a research project or helping with experiments does not automatically make someone an inventor. "
          + "Inventorship depends on the contributions made to the conception of the invention, and the university reviews that. "
          + "It's important to document everyone's contributions rather than assuming everyone involved is an inventor.";
    static final String EARLY_STAGE_IP =
            "Yes, an early-stage research idea can potentially lead to intellectual property. You don't necessarily need to have a finished "
          + "product or prototype before discussing your work with the university. What matters is understanding what you've developed "
          + "or are working toward. Could you tell me a little more about your research idea?";
    static final String RESULTS_NOT_REQUIRED =
            "You don't need to have all of your experimental results before submitting an invention disclosure. "
          + "The disclosure is a way to document what you've developed so far, including early tests or even a design. "
          + "You can add more detail later as the work continues.";
    static final String NOT_EVERYTHING_MUST_BE_NEW =
            "Not necessarily in the sense that every part must be completely new. Sometimes an invention involves a new combination, "
          + "improvement, or application of existing technologies. However, whether something qualifies for a particular type of IP "
          + "protection depends on the specific circumstances. Have you come across any existing approaches that try to do something similar?";
    static final String DISCLOSURE_VS_PATENT =
            "An invention disclosure is an internal process at UMKC for reporting a potential invention. It helps the university understand "
          + "the work so staff can review next steps. It is not the same as applying for a patent, which is a separate legal process that "
          + "happens later, if the university decides to pursue it.";
    static final String COMMERCIALIZATION_ANSWER =
            "Commercialization is the process of taking research toward something others can actually use — for example through further "
          + "development, licensing, or a new venture. It is separate from publishing the work or filing a patent. The university's "
          + "commercialization office reviews those options; that decision is not made in this conversation.";
    static final String PAUSE_ACK =
            "Of course. You can return whenever you're ready. What you've shared so far is saved, so you won't need to start over.";
    private static final Pattern GREETING_ONLY = Pattern.compile("(?i)^(hi|hello|hey|hi again|good (?:morning|afternoon|evening))[!., ]*$");
    private static final Pattern LAB_HELP = Pattern.compile(
            "(?i)((?:can you |could you )?(?:help me )?calculate\\b|\\bcalculation\\b|\\bequation\\b|solve (?:this |the )?(?:equation|calculation)|how much (?:chemical|chlorine|bleach)\\b)");
    private static final Pattern OFF_TOPIC = Pattern.compile(
            "(?i)\\b(weather|forecast|sports? scores?|who won|recipe|pizza|kfc|kentucky fried|mcdonald|burger|walmart|costco|netflix|homework|\\bpython\\b|binary search|write me (?:a )?(?:quick )?(?:script|code|program)|which do you prefer|favorite (?:food|team|movie|color|restaurant))\\b");
    private static final Pattern IN_SCOPE_HINT = Pattern.compile(
            "(?i)\\b(invent\\w*|patent\\w*|disclos\\w*|licen[sc]\\w*|commerc\\w*|prototype|my (?:device|research|work|invention)|\\bip\\b|umkc)\\b");
    private static final Pattern PROCESS_CUE = Pattern.compile(
            "(?i)\\b(purpose of|same (?:thing|as)|difference between|what(?:'?s| is)|explain what|what does .{0,24} mean"
          + "|do i need .{0,50}(before|to submit)|is it the same"
          + "|potential ip|considered potential|never existed before"
          + "|all (?:my |the )?(?:experimental )?results|before submitting|still be considered)\\b");
    private static final Pattern INVENTORSHIP_ASK = Pattern.compile(
            "(?i)\\b((?:should|does that mean).{0,48}inventors?|do they count as inventors|are we (?:all )?inventors)\\b");
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
            "(?i)\\b(goodbye|bye|bye-bye|see you|have a good (?:day|one)|i'?m (?:done|finished)|that'?s (?:all|everything)|talk to you later|catch you later|stop for now|that is all|signing off"
          + "|i (?:have to|need to|gotta) (?:leave|go|run)|can i come back|come back (?:and finish|later|another time)|finish this another time|pick this up later)\\b");
    private static final Pattern PAUSE_LANGUAGE = Pattern.compile(
            "(?i)\\b(come back|whenever you'?re ready|pick up|left off|another time|saved (?:our |your )?progress|won'?t need to start over)\\b");
    private static final Pattern SUMMARY_CUE = Pattern.compile(
            "(?i)\\b(summar(?:y|ize|ise)|recap|how far along|what(?:'s| is) (?:left|missing|remaining|collected)|current status"
          + "|(?:collected|gathered|recorded|covered|discussed|saved|have) so far"
          + "|what (?:am i|i'?m|i am) (?:still )?missing"
          + "|what (?:else )?(?:do you|you) (?:still )?need from me"
          + "|what (?:information|info|details|notes) (?:do )?(?:you )?(?:have|'ve|have you|you'?ve))\\b");
    /** The researcher is coming back to an earlier conversation. */
    private static final Pattern RESUME_CUE = Pattern.compile(
            "(?i)\\b(i'?m back|i am back|(?:continue|pick up|resume|carry on)(?: from)? where (?:we|i) left off|where were we"
          + "|let'?s (?:continue|keep going|pick (?:it |this )?(?:back )?up)|can we (?:continue|keep going|pick (?:it |this )?(?:back )?up))\\b");
    /** "What else do you need to know?": answered with one concrete question about the work itself. */
    private static final Pattern NEED_CUE = Pattern.compile(
            "(?i)\\bwhat (?:else |more )?(?:do|would|should) (?:you|i) (?:still )?need (?:to know|from me|to (?:add|share|tell))"
          + "|what (?:else|more) (?:should|can|could) i (?:tell|share|add)");
    /** Open questions about the work itself, in the order they are most useful. */
    private static final List<String> WORK_QUESTIONS = List.of(
            "technical_description", "difference_from_others", "evidence_testing", "development_stage", "closest_products", "problem_solved");
    /** Conversation order for remaining fields. Public disclosure and funding come after the work itself. */
    private static final List<String> CONVERSATION_ORDER = List.of(
            "technical_description", "problem_solved", "difference_from_others", "evidence_testing", "development_stage",
            "applications_users", "potential_inventors", "inventor_contributions", "public_disclosures_list", "funding");
    private static final Pattern CONTRIBUTOR_CUE = Pattern.compile(
            "(?i)\\b(graduate students?|grad students?|post-?docs?|colleagues?|co-?inventor"
          + "|working on this with|worked on this with)\\b");
    private static final Pattern COMPARISON_CUE = Pattern.compile(
            "(?i)\\b(exist(?:ing|ed)|unique|never existed|other (?:technologies|methods|approaches|products)"
          + "|how (?:is this |it )?(?:different|differ)|compar(?:e|ison)|new or unique)\\b");
    private static final Pattern PLACEHOLDER_NOTE = Pattern.compile(
            "(?i)\\bnot specified(?: in the conversation)?\\b|\\bnot (?:yet )?(?:mentioned|discussed|provided|stated)\\b"
          + "|\\bno information(?: given| provided)?\\b|\\bimplied importance\\b");
    private static final Pattern COMPANY_CUE = Pattern.compile(
            "(?i)\\b(a company|the company|companies|industry partner|licensee)\\b.{0,120}\\b(interest|interested|contacted|reached out)"
          + "|\\b(contacted .{0,60}company|company .{0,40}contacted)\\b");
    private static final Set<String> LATER_TOPICS = Set.of(
            "funding", "public_disclosures_list", "third_party", "agreements", "external_sponsors");
    /** A clear correction of something said earlier, as opposed to a passing "actually". */
    private static final Pattern STRONG_CORRECTION = Pattern.compile(
            "(?i)\\b(clarify|correction|correct (?:that|myself|something)|to be clear|i misspoke|i was wrong|i made a mistake|i meant"
          + "|that'?s (?:not right|wrong|incorrect)|(?:that|this) (?:was|is) (?:not right|wrong|incorrect)"
          + "|actually,? (?:we|i|it|they|there)(?:'?ve|'?re|'?s| have| are| is| was| were)? (?:haven'?t|have not|hasn'?t|didn'?t|did not|don'?t|do not|never|only|aren'?t|weren'?t|wasn'?t|isn'?t|not))\\b");
    private static final Pattern ACK_CUE = Pattern.compile(
            "(?i)^(okay|ok|sounds good|sounds great|makes sense|that makes sense|got it|understood|i see|alright|cool|great|good to know|noted|perfect|sure thing|will do|thank you(?: so much)?|thanks(?: a lot)?(?: for (?:that|explaining|the explanation|clarifying))?)(?:[,.]?\\s*(?:thanks|thank you))?[.!]?$");
    private static final Pattern CORRECTION_CUE = Pattern.compile(
            "(?i)\\b(actually|correction|clarify|i meant|i mean|not\\s+\\w+,?\\s+(?:it'?s|it is|but)|instead of|scratch that|replace|i made a mistake|rather than)\\b");
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
            "(?i)\\b(i|we|i'?m|we're)\\s+(made|make|built|building|developed|developing|created|designed|found|researched|have|invented|working on)\\b");
    private static final Pattern TESTING_CUE = Pattern.compile(
            "(?i)\\b((?:we|i) tested|tested it (?:using|with)|water samples|it seemed to work)\\b");
    private static final Pattern PROTOTYPE_CUE = Pattern.compile(
            "(?i)\\b(prototype|haven'?t built|have not built|only have a design|material samples)\\b");
    private static final Pattern INVENTED_SETTING = Pattern.compile(
            "(?i)\\b(hospitals?|households?|clinics?|factories|municipal plants?)\\b");

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

        // Summary and "I'm back" requests are answered from the saved notes, with no interview question added.
        if (isSummaryRequest(msg)) return finish(d, answers, "", List.of(), List.of(), rule, null, null, msg);
        if (RESUME_CUE.matcher(msg).find() && hasSavedNotes(answers)) return resume(d, answers, turns, rule, msg);
        if (NEED_CUE.matcher(msg).find()) {
            return finish(d, answers, "One thing that would help:", List.of(), List.of(), rule, null,
                    nextOpenWorkQuestion(d.workflow(), answers), msg);
        }
        if (GREETING_ONLY.matcher(msg).matches()) return finish(d, answers, GREETING_ACK, List.of(), List.of(), rule, null, null, msg);
        if (looksLikeLabHelp(msg)) return finish(d, answers, LAB_HELP_ACK, List.of(), List.of(), rule, null, null, msg);
        if (looksOffTopic(msg)) return finish(d, answers, OFF_TOPIC_ACK, List.of(), List.of(), rule, null, null, msg);

        // 2. Deterministic skip / "I don't know" handling.
        String norm = msg.toLowerCase().replaceAll("[^a-z' ]", " ").replaceAll("\\s+", " ").strip();
        boolean skip = SKIP_CMD.matcher(norm).matches(), unknown = UNKNOWN_CMD.matcher(norm).matches() || isUnknownReply(msg);
        List<String> unknownAbout = lastAsked;
        if ((skip || unknown) && unknownAbout.isEmpty()) unknownAbout = inferUnknownFields(turns, answers, d.workflow(), msg);
        if ((skip || unknown) && !unknownAbout.isEmpty()) {
            List<String> changed = new ArrayList<>();
            for (String id : unknownAbout) {
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
        List<String> updated = new ArrayList<>(payload == null || looksLikeGeneralQuestion(msg) || looksLikeProcessQuestion(msg)
                ? List.of() : applyUpdates(d, answers, payload.updates(), msg));
        if (mentionsContributors(msg) && !answers.getOrDefault("potential_inventors", FieldAnswer.EMPTY).hasText()) {
            answers.put("potential_inventors", new FieldAnswer(msg.strip(), ResearcherMark.NONE));
            updated.add("potential_inventors");
        }
        if (!answers.getOrDefault("summary_general", FieldAnswer.EMPTY).hasText()
                && looksLikeWorkDescription(msg)
                && !looksLikeProcessQuestion(msg)
                && !looksLikeAdvisorQuestion(msg)
                && msg.strip().length() >= 24) {
            answers.put("summary_general", new FieldAnswer(msg.strip(), ResearcherMark.NONE));
            if (!updated.contains("summary_general")) updated.add("summary_general");
        }
        repo.saveAnswers(d.id(), answers);
        String text = payload == null
                ? "Thanks, I've saved that in our conversation. I had trouble sorting it into your disclosure fields automatically, "
                + "so you may want to check or add it later under Review."
                : payload.assistantMessage();
        List<String> next = payload == null ? List.of() : payload.nextFieldIds();
        String preferred = preferredNextField(msg, answers, updated);
        return finish(d, answers, text, next, updated, rule, payload, preferred, msg);
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
        List<ChecklistField> orderedRem = conversationOrdered(rem);
        if (assistantTurnsSoFar >= 1 || hasAnyDescription) {
            orderedRem.removeIf(f -> f.id().equals("summary_general") || f.id().equals("title"));
            if (orderedRem.isEmpty() && !rem.isEmpty()) orderedRem = conversationOrdered(rem);
        } else {
            pinFront(orderedRem, "summary_general");
            pinFront(orderedRem, "what_changed");
        }
        String stayOn = topicOpenedBy(msg, answers);
        if (stayOn != null) {
            addIfOpen(orderedRem, stayOn, d, answers);
            pinFront(orderedRem, stayOn);
        }
        for (int i = 0; i < Math.min(8, orderedRem.size()); i++) {
            ChecklistField f = orderedRem.get(i);
            sb.append(f.id()).append(" | ").append(f.label()).append(" | ").append(f.description()).append('\n');
        }
        sb.append("</remaining_fields>\n<already_collected>\n");
        for (ChecklistField f : checklist.applicable(d.workflow())) {
            FieldAnswer a = answers.getOrDefault(f.id(), FieldAnswer.EMPTY);
            if (a.isAddressed()) sb.append(f.id()).append(": ").append(a.hasText() ? clip(clean(a.text()), 240) : a.mark().name()).append('\n');
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
        sb.append("</avoid_starting_with>\n<researcher_message>\n").append(clean(msg)).append("\n</researcher_message>\n");
        sb.append("<turn_instruction>\n");
        if (CLOSING_CUE.matcher(msg).find()) {
            sb.append("The researcher wants to pause or come back later. Acknowledge that, say their notes are saved, ");
            sb.append("and do not ask another interview question. Set nextFieldIds to [].\n");
        } else if (looksLikeAdvisorQuestion(msg)) {
            sb.append("The researcher asked YOU a question. Answer it in 3-5 sentences using <already_collected>. ");
            sb.append("Never repeat their question back to them. If they asked who would use the work, name possible ");
            sb.append("user groups grounded in their description and frame them as possibilities, not conclusions. ");
            sb.append("Set nextFieldIds to [] unless you ask a different follow-up.\n");
        } else if (INVENTORSHIP_ASK.matcher(msg).find()) {
            sb.append("They asked who counts as an inventor. Explain that project involvement is not the same as inventorship ");
            sb.append("and that the university reviews it. Do not decide. Do not ask another interview question. nextFieldIds: [].\n");
        } else if (looksLikeProcessQuestion(msg) || looksLikeGeneralQuestion(msg)) {
            sb.append("They asked a process or IP question. Answer it in 3-5 complete sentences in plain language. ");
            sb.append("Do not ask an interview question unless a short related follow-up is clearly useful. ");
            sb.append("If you are only answering, set nextFieldIds to [].\n");
        } else {
            sb.append("Write 3-5 helpful sentences about THIS message, like a colleague. Stay on the topic they just raised. ");
            sb.append("If they named what it is, ask how it works — not the problem they already stated. ");
            sb.append("If they named a mechanism, ask how that effect is observed or measured. ");
            sb.append("If they mentioned a company, ask whether any description, data, or materials were already shared. Do not ask for contact information. ");
            sb.append("Do not ask about papers, talks, posters, companies, or funding unless they just brought that up. ");
            sb.append("Do not reply with only an acknowledgement plus a question. Ask at most ONE question, and only if needed. ");
            sb.append("Match the Example turns in the system prompt: a short reason, then one follow-up, with an example when you ask how it works.\n");
        }
        sb.append("</turn_instruction>");
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

            String id = f.get().id();
            if (!text.isEmpty()) {
                String home = NotesOrganizer.homeFor(id, text);
                if (!home.equals(id) && checklist.find(home).filter(x -> x.appliesTo(d.workflow())).isPresent()) id = home;
            }
            FieldAnswer old = answers.getOrDefault(id, FieldAnswer.EMPTY);
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
            answers.put(id, new FieldAnswer(merged, newMark));
            changed.add(id);
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
        for (String id : modelNext) {
            if (LATER_TOPICS.contains(id) && !messageAboutLaterTopic(msg, id)) continue;
            if (id.equals("potential_licensees") && !mentionsCompany(msg)) continue;
            if (allowedNext.contains(id) && !next.contains(id) && next.size() < 1) next.add(id);
        }

        List<Turn> history = repo.turns(d.id());
        int assistantTurns = (int) history.stream().filter(t -> t.role().equals("ASSISTANT")).count();
        List<String> previous = new ArrayList<>();
        for (int i = history.size() - 1; i >= 0 && previous.size() < 2; i--) if (history.get(i).role().equals("ASSISTANT")) previous.add(history.get(i).content());

        boolean isClosing = CLOSING_CUE.matcher(msg).find();
        boolean isSummary = isSummaryRequest(msg);
        boolean isAck = ACK_CUE.matcher(msg.trim()).find();
        boolean isCorrection = isCorrection(msg);
        boolean isInventorship = INVENTORSHIP_ASK.matcher(msg).find();
        boolean isOffTopic = looksOffTopic(msg) || looksLikeLabHelp(msg) || GREETING_ONLY.matcher(msg).matches();
        boolean isProcess = looksLikeProcessQuestion(msg);
        boolean askedAdvisor = looksLikeAdvisorQuestion(msg);
        boolean usedGeneratedReply = false;
        boolean strippedJump = false;

        String text;
        if (complete && !isSummary) {
            text = COMPLETE_MESSAGE;
            next = List.of();
        } else if (payload != null) {
            String base = ConversationStyle.polish(cap(modelText), previous, msg);
            ReferralService.Contact c = referral.contact();
            Set<String> allowed = new HashSet<>();
            if (c.url() != null) allowed.add(c.url());
            if (c.email() != null) allowed.add(c.email());
            boolean userAskedQuestion = msg.trim().endsWith("?") || looksLikeGeneralQuestion(msg) || !rule.topics().isEmpty();
            boolean jumpedAhead = questionDrifts(base, msg);
            if (jumpedAhead) {
                String exp = extractExplanation(base);
                if (!exp.isBlank()) base = exp;
                next = List.of();
                strippedJump = true;
            }
            boolean hasQuestion = base.contains("?");
            boolean validNext = !next.isEmpty();
            boolean isRepeat = repeatsRecent(d, base);
            boolean guardViolation = guard.scan(base, allowed).violated();
            boolean isForm = ConversationStyle.isFormLike(base);

            if (isClosing) {
                boolean addressed = PAUSE_LANGUAGE.matcher(base).find();
                String exp = extractExplanation(base);
                text = (addressed && !guardViolation && !exp.isBlank()) ? exp
                        : (addressed && !guardViolation && !hasQuestion) ? base
                        : PAUSE_ACK;
                next = List.of();
            } else if (isOffTopic) {
                text = modelText;
                next = List.of();
            } else if (isInventorship) {
                String exp = extractExplanation(base);
                String candidate = !exp.isBlank() && !guardViolation ? exp
                        : (!base.isBlank() && !guardViolation ? base : INVENTORSHIP_ANSWER);
                String lower = candidate.toLowerCase(Locale.ROOT);
                text = (lower.contains("not necessarily") || lower.contains("does not automatically"))
                        ? candidate : INVENTORSHIP_ANSWER;
                next = List.of();
                usedGeneratedReply = !text.equals(INVENTORSHIP_ANSWER);
            } else if (isProcess) {
                String exp = extractExplanation(base);
                if (guardViolation) exp = "";
                String fallback = processFallback(msg, answers);
                boolean thin = !looksSubstantive(exp);
                text = (!thin && fallback == null) ? exp
                        : (fallback != null && (thin || fallbackFitsBetter(msg, exp))) ? fallback
                        : (!exp.isBlank() ? exp : fallback == null ? base : fallback);
                next = processNextFields(msg, text, answers);
                usedGeneratedReply = fallback == null || text.equals(exp);
            } else if (isSummary) {
                text = buildDraftSummary(d, answers);
                next = List.of();
            } else if (isCorrection) {
                String exp = extractExplanation(base);
                boolean relatedFollowUp = hasQuestion && validNext
                        && !Set.of("funding", "public_disclosures_list", "potential_inventors").contains(next.get(0));
                if (!guardViolation && !isForm && relatedFollowUp) {
                    text = base;
                    usedGeneratedReply = true;
                } else if (!exp.isBlank() && !guardViolation && !isForm) {
                    text = exp;
                    next = List.of();
                    usedGeneratedReply = true;
                } else {
                    text = CORRECTION_ACK;
                    next = List.of();
                }
            } else if (isAck) {
                text = (!base.isBlank() && !guardViolation && !hasQuestion) ? base
                        : (!base.isBlank() && !guardViolation && !extractExplanation(base).isBlank()) ? extractExplanation(base)
                        : "Sounds good! Whenever you're ready, let me know what you'd like to explore next.";
                next = List.of();
            } else if (askedAdvisor) {
                String chosen = chooseAdvisorAnswer(base, msg, answers, guardViolation, isForm);
                if (questionDrifts(chosen, msg) || asksForContactInfo(chosen)) {
                    String exp = extractExplanation(chosen);
                    if (!exp.isBlank()) chosen = exp;
                }
                text = chosen;
                next = chosen.contains("?") && validNext && !echoesAskedQuestion(base, msg)
                        && !questionDrifts(chosen, msg) ? next : List.of();
                usedGeneratedReply = !chosen.equals(answerFromNotes(answers));
            } else if (userAskedQuestion && !base.isBlank() && !isForm && !guardViolation) {
                String explanation = extractExplanation(base);
                if (echoesAskedQuestion(base, msg)) {
                    text = !explanation.isBlank() ? explanation : answerFromNotes(answers);
                    next = List.of();
                    usedGeneratedReply = !explanation.isBlank();
                } else if (!isRepeat && hasQuestion && validNext) {
                    text = base;
                    usedGeneratedReply = true;
                } else if (!explanation.isBlank()) {
                    text = explanation;
                    next = List.of();
                    usedGeneratedReply = true;
                } else {
                    text = base;
                    next = List.of();
                    usedGeneratedReply = true;
                }
            } else {
                boolean invalidTarget = hasQuestion && !modelNext.isEmpty() && next.isEmpty();
                boolean keepModel = !base.isBlank() && !isForm && !guardViolation && !invalidTarget
                        && !isRepeat && (assistantTurns > 0 || hasQuestion || looksSubstantive(base));
                if (keepModel) {
                    text = base;
                    if (!hasQuestion) next = List.of();
                    usedGeneratedReply = true;
                } else {
                    ChecklistField nf = selectNextField(remaining, assistantTurns, history);
                    text = ConversationStyle.bridge(assistantTurns) + (nf == null ? "" : " " + spokenQuestion(nf, msg));
                    next = nf != null ? List.of(nf.id()) : List.of();
                }
            }
        } else {
            if (isClosing) {
                text = PAUSE_ACK;
                next = List.of();
            } else if (isOffTopic) {
                text = modelText;
                next = List.of();
            } else if (isProcess) {
                text = modelText;
                next = List.of();
            } else if (isInventorship) {
                text = INVENTORSHIP_ANSWER;
                next = List.of();
            } else if (isSummary) {
                text = buildDraftSummary(d, answers);
                next = List.of();
            } else if (isCorrection && preferredNext == null) {
                text = CORRECTION_ACK;
                next = List.of();
            } else if (isAck) {
                text = "Sounds good! Whenever you're ready, let me know what you'd like to explore next.";
                next = List.of();
            } else if (askedAdvisor) {
                text = answerFromNotes(answers);
                next = List.of();
            } else {
                ChecklistField nf = selectNextField(remaining, assistantTurns, history);
                if (preferredNext != null) {
                    Optional<ChecklistField> pf = checklist.find(preferredNext).filter(x -> x.appliesTo(d.workflow()));
                    if (pf.isPresent()) nf = pf.get();
                }
                String question = nf == null ? "" : spokenQuestion(nf, msg);
                text = modelText + (question.isBlank() ? "" : " " + question);
                next = nf != null ? List.of(nf.id()) : List.of();
            }
        }
        boolean jumpedAhead = questionDrifts(text, msg) || asksForContactInfo(text)
                || (!next.isEmpty() && LATER_TOPICS.contains(next.get(0))
                && !messageAboutLaterTopic(msg, next.get(0)));
        if (payload != null && preferredNext != null && !isOffTopic && !isProcess && !isInventorship
                && !isClosing && !isSummary && !isAck && !askedAdvisor) {
            Optional<ChecklistField> pf = checklist.find(preferredNext).filter(x -> x.appliesTo(d.workflow()));
            if (pf.isPresent()) {
                boolean problemReask = "technical_description".equals(preferredNext) && asksAboutProblem(text);
                boolean reasksKnownWork = isCorrection && asksAbout(text, "technical_description")
                        && answers.getOrDefault("technical_description", FieldAnswer.EMPTY).isAddressed();
                boolean mechanismRepeat = ConversationStyle.looksLikeMechanism(msg)
                        && "technical_description".equals(preferredNext)
                        && !text.toLowerCase(Locale.ROOT).contains("how can you tell")
                        && !text.toLowerCase(Locale.ROOT).contains("visible change");
                boolean reasksBuildAfterNoPrototype = isCorrection && PROTOTYPE_CUE.matcher(msg).find()
                        && asksAbout(text, "development_stage")
                        && text.toLowerCase(Locale.ROOT).matches(".*\\b(started building|building or testing|have you (?:started |built ))\\b.*");
                boolean asksOmittedAbstractDetails = PAST_SHARE.matcher(msg).find()
                        && msg.toLowerCase(Locale.ROOT).matches(".*\\b(didn'?t include|did not include|not include all)\\b.*")
                        && text.toLowerCase(Locale.ROOT).matches(".*\\b(technical details you shared|details you (?:shared|included)|what (?:technical )?details)\\b.*");
                boolean offTopicQuestion = strippedJump || jumpedAhead || problemReask || reasksKnownWork
                        || mechanismRepeat || asksForContactInfo(text)
                        || reasksBuildAfterNoPrototype || asksOmittedAbstractDetails
                        || (isCorrection && asksAbout(text, "technical_description"));
                boolean keepFollowUp = usedGeneratedReply && text.contains("?") && !offTopicQuestion;
                if (!keepFollowUp) {
                    text = steerToTopic(text, pf.get(), msg);
                }
                next = List.of(preferredNext);
            }
        }
        if (payload != null && !isProcess && !isInventorship && !isClosing && !isSummary
                && !isOffTopic && !isAck && !askedAdvisor) {
            String field = !next.isEmpty() ? next.get(0) : preferredNext;
            text = ConversationStyle.expandThinReply(text, field, msg);
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
        if (assistantTurns == 0) {
            for (ChecklistField f : remaining) {
                if (f.id().equals("summary_general") || f.id().equals("what_changed")) return f;
            }
        }
        List<ChecklistField> ordered = conversationOrdered(remaining);
        ordered.removeIf(f -> LATER_TOPICS.contains(f.id()) && ordered.stream().anyMatch(o -> !LATER_TOPICS.contains(o.id())));
        List<String> recentFields = new ArrayList<>();
        if (history != null) {
            for (int i = history.size() - 1; i >= 0 && recentFields.size() < 4; i--) {
                if (history.get(i).role().equals("ASSISTANT")) {
                    recentFields.addAll(history.get(i).fieldIds());
                }
            }
        }
        for (ChecklistField f : ordered) {
            if (assistantTurns >= 1 && (f.id().equals("summary_general") || f.id().equals("title"))) continue;
            if (!recentFields.contains(f.id())) return f;
        }
        for (ChecklistField f : ordered) {
            if (assistantTurns >= 1 && (f.id().equals("summary_general") || f.id().equals("title"))) continue;
            return f;
        }
        return ordered.get(0);
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
    /** The researcher is asking the advisor for a view, not answering an interview question. */
    private static final Pattern ADVISOR_QUESTION = Pattern.compile(
            "(?i)\\b(who do you see|what do you (?:see|think|suggest)|who would you|what would you"
          + "|can you (?:suggest|see)|who(?:'s| is) this for|who would (?:use|benefit|buy)"
          + "|who might use|target (?:users?|market|customers?)|potential applications"
          + "|do you think .{0,80}(?:used|use outside)|could (?:this|it) (?:actually )?be used outside)\\b");
    private static final Pattern APPLICATIONS_QUESTION = Pattern.compile(
            "(?i)\\b(who .{0,24}(?:use|using|benefit|buy)|who(?:'s| is) this for|target (?:users?|market|customers?)"
          + "|potential applications|what applications|which industries"
          + "|could (?:this|it|the (?:device|work|invention)) (?:actually )?be used|used outside)\\b");
    private static final Pattern APPLICATION_MENTION = Pattern.compile(
            "(?i)\\b(useful in|use case|rural communit|households?|water treatment|in homes?)\\b");
    private static final Pattern PAST_SHARE = Pattern.compile(
            "(?i)\\b(published|posted|presented|last (?:semester|year|month)|already (?:published|shared|posted|presented))\\b");
    private static final Pattern PROBLEM_QUESTION = Pattern.compile(
            "(?i)\\b(what (?:problem|need)|the (?:specific )?problem|who runs into|need this (?:device|work|invention) addresses)\\b");
    private static final Pattern CONTACT_INFO = Pattern.compile(
            "(?i)\\b(contact information|contact info|email address|phone number)\\b");

    private static final Pattern GUIDE_QUESTION = Pattern.compile(
            "^(what(?:'?s|s)?|how|who|when|why|where|which|can|could|should|is|are|do|does|would|will|explain|define|tell\\s+me)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern GUIDE_TOPIC = Pattern.compile(
            "\\b(patent\\w*|disclos\\w*|invent\\w*|licen[sc]\\w*|commerc\\w*|prior\\s+art"
          + "|preprint\\w*|trl|royalt\\w*|own(?:s|ed|ership)|copyright\\w*|trademark\\w*|tech(?:nology)?\\s*transfer"
          + "|intellectual\\s+property|\\bip\\b|otts?\\b|office|umkc|university|publish\\w*"
          + "|conference|public(?:ly)?\\s+shar\\w*|prototype\\w*|submit\\w*|require\\w*|form\\w*)\\b",
            Pattern.CASE_INSENSITIVE);

    static boolean looksOffTopic(String msg) {
        if (msg == null || msg.isBlank()) return false;
        if (looksLikeWorkDescription(msg)) return false;
        return OFF_TOPIC.matcher(msg).find() && !IN_SCOPE_HINT.matcher(msg).find();
    }

    static boolean looksLikeLabHelp(String msg) {
        return msg != null && LAB_HELP.matcher(msg).find();
    }

    static boolean looksLikeProcessQuestion(String msg) {
        return looksLikeGeneralQuestion(msg) && PROCESS_CUE.matcher(msg).find();
    }

    private static final Pattern UNKNOWN_REPLY = Pattern.compile(
            "(?i)\\b(not sure|don'?t know|do not know|no idea|haven'?t looked|have not looked|haven'?t compared)\\b");

    static boolean isUnknownReply(String msg) {
        if (msg == null || msg.length() > 220 || msg.strip().endsWith("?")) return false;
        if (looksLikeWorkDescription(msg) || looksLikeGeneralQuestion(msg) || looksLikeAdvisorQuestion(msg)) return false;
        return UNKNOWN_REPLY.matcher(msg).find();
    }

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
        if (descriptive > 0) {
            String q = extractQuestion(t);
            return !q.isBlank() && GUIDE_TOPIC.matcher(q).find() && PROCESS_CUE.matcher(q).find();
        }
        return GUIDE_QUESTION.matcher(t).find() || (t.endsWith("?") && t.length() < 280);
    }

    static boolean looksLikeAdvisorQuestion(String msg) {
        if (msg == null || msg.isBlank()) return false;
        if (ADVISOR_QUESTION.matcher(msg).find() || APPLICATIONS_QUESTION.matcher(msg).find()) return true;
        String compact = msg.toLowerCase(Locale.ROOT).replaceAll("[^a-z]+", "");
        return compact.contains("youseeusing") || compact.contains("whodoyousee")
                || compact.contains("whowoulduse") || compact.contains("whomightuse")
                || compact.contains("whoisthisfor") || compact.contains("whatdoyouthink")
                || compact.contains("whowouldbenefit") || compact.contains("targetuser");
    }

    static boolean looksLikeApplicationsQuestion(String msg) {
        if (msg == null || msg.isBlank()) return false;
        if (APPLICATIONS_QUESTION.matcher(msg).find()) return true;
        String compact = msg.toLowerCase(Locale.ROOT).replaceAll("[^a-z]+", "");
        return compact.contains("youseeusing") || compact.contains("whowoulduse") || compact.contains("whomightuse")
                || compact.contains("whoisthisfor") || compact.contains("targetuser") || compact.contains("whowouldbenefit");
    }

    static boolean echoesAskedQuestion(String assistant, String user) {
        if (assistant == null || user == null) return false;
        String asked = extractQuestion(user);
        if (asked.isBlank()) asked = user;
        String replyQ = extractQuestion(assistant);
        String reply = replyQ.isBlank() ? assistant : replyQ;
        return isQuestionRepeat(normalize(reply), normalize(asked));
    }

    private static boolean hasWorkDescription(Map<String, FieldAnswer> answers) {
        return answers.getOrDefault("summary_general", FieldAnswer.EMPTY).hasText()
                || answers.getOrDefault("technical_description", FieldAnswer.EMPTY).hasText();
    }

    static String processFallback(String msg, Map<String, FieldAnswer> answers) {
        if (msg == null) return null;
        String m = msg.toLowerCase(Locale.ROOT);
        if ((m.contains("all") && (m.contains("result") || m.contains("experimental")) && (m.contains("before") || m.contains("submit")))
                || (m.contains("need") && m.contains("results") && m.contains("before")))
            return RESULTS_NOT_REQUIRED;
        if (m.contains("never existed")) return NOT_EVERYTHING_MUST_BE_NEW;
        if ((m.contains("purpose of") && m.contains("disclosure")) || (m.contains("same") && m.contains("patent") && m.contains("disclosure")))
            return DISCLOSURE_VS_PATENT;
        if (m.contains("commercializ") && (m.contains("mean") || m.contains("explain") || m.contains("what"))) {
            if (hasWorkDescription(answers)) {
                return COMMERCIALIZATION_ANSWER
                        + " In this conversation, that means exploring whether what you've described could move beyond the lab.";
            }
            return COMMERCIALIZATION_ANSWER;
        }
        if (m.contains("potential ip") || m.contains("considered potential") || (m.contains("early") && m.contains(" ip"))) {
            if (hasWorkDescription(answers)) {
                return "Yes. Early-stage work can still be potential intellectual property. A physical prototype is not required "
                        + "to talk through the idea or to start an invention disclosure.";
            }
            return EARLY_STAGE_IP;
        }
        return null;
    }

    private static boolean fallbackFitsBetter(String msg, String exp) {
        if (exp == null || exp.isBlank()) return true;
        String e = exp.toLowerCase(Locale.ROOT);
        String m = msg == null ? "" : msg.toLowerCase(Locale.ROOT);
        if (m.contains("result") && m.contains("before"))
            return !e.contains("don't need") && !e.contains("do not need") && !e.contains("not required");
        if (m.contains("never existed"))
            return e.contains("valuable") || (!e.contains("combination") && !e.contains("not necessarily"))
                    || !e.contains("similar");
        if ((m.contains("purpose of") && m.contains("disclosure")) || (m.contains("same") && m.contains("patent")))
            return e.contains("qualif") || e.contains("meets the criteria") || e.contains("we can discuss")
                    || e.contains("best path") || e.contains("protecting and commercializing") || e.contains("?");
        if (m.contains("commercializ") && (m.contains("mean") || m.contains("explain") || m.contains("what")))
            return true;
        if (m.contains("potential ip") || m.contains("considered potential"))
            return e.contains("valuable") || e.contains("problem") || e.contains("need")
                    || e.contains("best path") || !e.contains("prototype");
        return false;
    }

    static boolean looksSubstantive(String text) {
        if (text == null) return false;
        String body = extractExplanation(text);
        if (body.isBlank()) body = text.strip();
        if (body.length() >= 90) return true;
        return body.split("[.!]\\s+").length >= 2 && body.length() >= 50;
    }

    private String chooseAdvisorAnswer(String base, String msg, Map<String, FieldAnswer> answers,
                                       boolean guardViolation, boolean isForm) {
        if (looksLikeApplicationsQuestion(msg) && inventsUngroundedSettings(base, msg, answers)) {
            return answerFromNotes(answers);
        }
        String explanation = extractExplanation(base);
        boolean echo = echoesAskedQuestion(base, msg);
        boolean good = !base.isBlank() && !isForm && !guardViolation && !echo
                && looksSubstantive(explanation.isBlank() ? base : explanation);
        if (good) {
            if (base.contains("?") && echoesAskedQuestion(extractQuestion(base), msg)) return explanation;
            return explanation.isBlank() ? base : (echoesAskedQuestion(base, msg) ? explanation : base);
        }
        if (!explanation.isBlank() && !guardViolation && !echo && looksSubstantive(explanation)) return explanation;
        return answerFromNotes(answers);
    }

    private static boolean inventsUngroundedSettings(String text, String msg, Map<String, FieldAnswer> answers) {
        if (text == null || !INVENTED_SETTING.matcher(text).find()) return false;
        StringBuilder grounded = new StringBuilder(msg == null ? "" : msg);
        for (FieldAnswer a : answers.values()) {
            if (a.hasText()) grounded.append(' ').append(a.text());
        }
        String hay = grounded.toString().toLowerCase(Locale.ROOT);
        Matcher m = INVENTED_SETTING.matcher(text);
        while (m.find()) {
            if (!hay.contains(m.group().toLowerCase(Locale.ROOT))) return true;
        }
        return false;
    }

    /** Grounded possible users from saved notes. Possibilities only — never a market conclusion. */
    String answerFromNotes(Map<String, FieldAnswer> answers) {
        List<String> bits = new ArrayList<>();
        for (String id : List.of("summary_general", "problem_solved", "technical_description",
                "difference_from_others", "applications_users")) {
            FieldAnswer a = answers.getOrDefault(id, FieldAnswer.EMPTY);
            if (a.hasText()) bits.add(a.text().replaceAll("\\s+", " ").strip());
        }
        if (bits.isEmpty()) {
            return "I don't yet have enough of your description to suggest likely users with any confidence. "
                    + "A useful starting point is whoever faces the problem you are solving, and whoever would need a better "
                    + "version of today's method. Who did you have in mind, or what setting were you imagining?";
        }
        String core = clip(NotesOrganizer.dedupeSentences(bits.get(0)), 180);
        return "From what you've described — " + core + " — the people most likely to use this are the groups doing that "
                + "work today and anyone who needs a more reliable version of the current approach. Those are possibilities "
                + "based on your description, not a conclusion about the market or who will buy it. Does that match who you "
                + "had in mind, or were you thinking of a more specific user?";
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

    static boolean isSummaryRequest(String msg) {
        return msg != null && SUMMARY_CUE.matcher(msg).find();
    }

    /** A correction statement (not a question), which is acknowledged without starting a new topic. */
    static boolean isCorrection(String msg) {
        if (msg == null) return false;
        String t = msg.strip();
        return STRONG_CORRECTION.matcher(t).find() && !t.endsWith("?") && !looksLikeGeneralQuestion(t);
    }

    /** "I'm back": a short recap of the topics covered, then the question that was still open, or the next one. */
    private Reply resume(Draft d, Map<String, FieldAnswer> answers, List<Turn> turns, ReferralDecision rule, String msg) {
        List<String> covered = new ArrayList<>();
        for (ChecklistService.Section s : checklist.sections(d.workflow()))
            if (s.fields().stream().anyMatch(f -> answers.getOrDefault(f.id(), FieldAnswer.EMPTY).hasText()))
                covered.add(s.section().title().toLowerCase(Locale.ROOT));
        String recap = covered.isEmpty() ? "Welcome back!"
                : "Welcome back! So far we've covered " + joinWithAnd(covered) + ", and your notes are saved.";
        String open = lastUnansweredAskedField(turns, d, answers);
        if (open == null || DEFER_ON_RESUME.contains(open)) {
            String work = nextOpenWorkQuestion(d.workflow(), answers);
            if (work != null) open = work;
        }
        return finish(d, answers, recap + " Let's pick up where we left off.", List.of(), List.of(), rule, null, open, msg);
    }

    private static final Set<String> DEFER_ON_RESUME = Set.of(
            "funding", "public_disclosures_list", "potential_inventors", "third_party");

    private String lastUnansweredAskedField(List<Turn> turns, Draft d, Map<String, FieldAnswer> answers) {
        for (int i = turns.size() - 1; i >= 0; i--) {
            if (!turns.get(i).role().equals("ASSISTANT")) continue;
            for (String id : turns.get(i).fieldIds()) {
                if (checklist.find(id).filter(f -> f.appliesTo(d.workflow())).isPresent()
                        && !answers.getOrDefault(id, FieldAnswer.EMPTY).isAddressed()) return id;
            }
        }
        return null;
    }

    private String nextOpenWorkQuestion(Workflow w, Map<String, FieldAnswer> answers) {
        return WORK_QUESTIONS.stream()
                .filter(id -> checklist.find(id).filter(f -> f.appliesTo(w)).isPresent())
                .filter(id -> !answers.getOrDefault(id, FieldAnswer.EMPTY).isAddressed())
                .findFirst().orElse(null);
    }

    private String preferredNextField(String msg, Map<String, FieldAnswer> answers, List<String> updated) {
        return topicOpenedBy(msg, answers, updated);
    }

    /** The topic they just opened, so a fallback question stays there instead of jumping to papers or funding. */
    private String topicOpenedBy(String msg, Map<String, FieldAnswer> answers) {
        return topicOpenedBy(msg, answers, List.of());
    }

    private String topicOpenedBy(String msg, Map<String, FieldAnswer> answers, List<String> updated) {
        if (msg == null || INVENTORSHIP_ASK.matcher(msg).find() || looksLikeGeneralQuestion(msg))
            return null;
        if ((looksLikeApplicationsQuestion(msg) || mentionsApplications(msg))
                && (openFieldFor(answers, "applications_users") || updated.contains("applications_users")))
            return "applications_users";
        if (looksLikeAdvisorQuestion(msg)) return null;
        if (mentionsCompany(msg)
                && (openFieldFor(answers, "potential_licensees") || updated.contains("potential_licensees")))
            return "potential_licensees";
        if ((updated.contains("potential_inventors") || mentionsContributors(msg))
                && (openFieldFor(answers, "inventor_contributions") || updated.contains("inventor_contributions")
                    || updated.contains("potential_inventors")))
            return "inventor_contributions";
        if (messageAboutLaterTopic(msg, "public_disclosures_list")) {
            if (PAST_SHARE.matcher(msg).find()) return "previous_public_disclosures";
            if (openFieldFor(answers, "previous_public_disclosures")) return "previous_public_disclosures";
            if (openFieldFor(answers, "public_disclosures_list")) return "public_disclosures_list";
        }
        if (isCorrection(msg) && PROTOTYPE_CUE.matcher(msg).find()
                && (openFieldFor(answers, "development_stage") || updated.contains("development_stage")))
            return "development_stage";
        if (isCorrection(msg) && !answers.getOrDefault("evidence_testing", FieldAnswer.EMPTY).isAddressed())
            return "evidence_testing";
        if (isCorrection(msg) && !answers.getOrDefault("development_stage", FieldAnswer.EMPTY).isAddressed())
            return "development_stage";
        if (ConversationStyle.looksLikeMechanism(msg)
                && (openFieldFor(answers, "technical_description") || updated.contains("technical_description")
                    || answers.getOrDefault("summary_general", FieldAnswer.EMPTY).hasText()))
            return "technical_description";
        if (TESTING_CUE.matcher(msg).find()
                && (openFieldFor(answers, "evidence_testing") || updated.contains("evidence_testing")))
            return "evidence_testing";
        if (looksLikeWorkDescription(msg)
                && (openFieldFor(answers, "technical_description") || updated.contains("technical_description")))
            return "technical_description";
        return null;
    }

    private static boolean openFieldFor(Map<String, FieldAnswer> answers, String id) {
        return !answers.getOrDefault(id, FieldAnswer.EMPTY).isAddressed();
    }

    private List<String> inferUnknownFields(List<Turn> turns, Map<String, FieldAnswer> answers, Workflow w, String msg) {
        String last = "";
        for (int i = turns.size() - 1; i >= 0; i--) {
            if (turns.get(i).role().equals("ASSISTANT")) {
                last = turns.get(i).content();
                break;
            }
        }
        List<String> ids = new ArrayList<>();
        if (COMPARISON_CUE.matcher(last).find() || COMPARISON_CUE.matcher(msg == null ? "" : msg).find()) {
            if (openField(w, answers, "difference_from_others")) ids.add("difference_from_others");
            else if (openField(w, answers, "closest_products")) ids.add("closest_products");
        }
        if (ids.isEmpty() && msg != null && Pattern.compile("(?i)\\b(haven'?t tested|not tested)\\b").matcher(msg).find()) {
            if (openField(w, answers, "evidence_testing")) ids.add("evidence_testing");
        }
        return ids;
    }

    private boolean openField(Workflow w, Map<String, FieldAnswer> answers, String id) {
        return checklist.find(id).filter(f -> f.appliesTo(w)).isPresent()
                && !answers.getOrDefault(id, FieldAnswer.EMPTY).isAddressed();
    }

    private List<ChecklistField> conversationOrdered(List<ChecklistField> remaining) {
        Map<String, Integer> rank = new HashMap<>();
        for (int i = 0; i < CONVERSATION_ORDER.size(); i++) rank.put(CONVERSATION_ORDER.get(i), i);
        List<ChecklistField> copy = new ArrayList<>(remaining);
        copy.sort(Comparator
                .comparingInt((ChecklistField f) -> rank.getOrDefault(f.id(), 100 + f.priority()))
                .thenComparingInt(ChecklistField::order));
        return copy;
    }

    private void addIfOpen(List<ChecklistField> list, String id, Draft d, Map<String, FieldAnswer> answers) {
        if (list.stream().anyMatch(f -> f.id().equals(id))) return;
        checklist.find(id).filter(f -> f.appliesTo(d.workflow()))
                .filter(f -> !answers.getOrDefault(id, FieldAnswer.EMPTY).isAddressed())
                .ifPresent(list::add);
    }

    private static void pinFront(List<ChecklistField> list, String id) {
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).id().equals(id)) {
                ChecklistField f = list.remove(i);
                list.add(0, f);
                return;
            }
        }
    }

    static boolean mentionsContributors(String msg) {
        return msg != null && CONTRIBUTOR_CUE.matcher(msg).find() && !INVENTORSHIP_ASK.matcher(msg).find();
    }

    static boolean mentionsCompany(String msg) {
        return msg != null && COMPANY_CUE.matcher(msg).find();
    }

    static boolean messageAboutLaterTopic(String msg, String fieldId) {
        if (msg == null) return false;
        String t = msg.toLowerCase(Locale.ROOT);
        boolean funding = t.contains("grant") || t.contains("funding") || t.contains("sponsor")
                || t.contains("nsf") || t.contains("nih");
        boolean disclosure = Pattern.compile(
                "(?i)\\b(publish|published|abstract|poster|talk|conference|paper|preprint|thesis|website)\\b")
                .matcher(msg).find();
        if (fieldId == null) return funding || disclosure;
        return switch (fieldId) {
            case "funding", "external_sponsors" -> funding;
            case "public_disclosures_list" -> disclosure;
            default -> false;
        };
    }

    static boolean asksLaterTopic(String text) {
        if (text == null || !text.contains("?")) return false;
        String q = extractQuestion(text).toLowerCase(Locale.ROOT);
        return q.contains("paper") || q.contains("poster") || q.contains("talks") || q.contains("funding")
                || q.contains("grant") || q.contains("outside funding")
                || (q.contains("public") && (q.contains("shar") || q.contains("disclos")));
    }

    static boolean questionDrifts(String text, String msg) {
        if (text == null || !text.contains("?")) return false;
        String q = extractQuestion(text).toLowerCase(Locale.ROOT);
        boolean companyQ = q.contains("compan") || q.contains("licensee") || q.contains("contact information")
                || q.contains("contact info");
        if (companyQ && !mentionsCompany(msg)) return true;
        return asksLaterTopic(text) && !messageAboutLaterTopic(msg, null);
    }

    static boolean asksAbout(String text, String fieldId) {
        if (text == null || fieldId == null || !text.contains("?")) return false;
        String q = extractQuestion(text).toLowerCase(Locale.ROOT);
        return switch (fieldId) {
            case "inventor_contributions" -> q.contains("contribut");
            case "potential_licensees" -> !asksForContactInfo(text) && (q.contains("compan") || q.contains("shared")
                    || q.contains("technical detail") || q.contains("research material") || q.contains("collaborat"));
            case "evidence_testing" -> q.contains("test") || q.contains("sample") || q.contains("observ");
            case "development_stage" -> q.contains("build") || q.contains("prototype") || q.contains("design")
                    || q.contains("stage") || q.contains("testing");
            case "difference_from_others" -> q.contains("exist") || q.contains("similar") || q.contains("other")
                    || q.contains("differ");
            case "technical_description" -> !asksAboutProblem(text) && (q.contains("detect") || q.contains("how")
                    || q.contains("material") || q.contains("work") || q.contains("react") || q.contains("measur")
                    || q.contains("observ") || q.contains("happen") || q.contains("mechanism") || q.contains("approach"));
            case "applications_users" -> q.contains("use") || q.contains("user") || q.contains("setting")
                    || q.contains("communit") || q.contains("benefit") || q.contains("application")
                    || q.contains("constrain") || q.contains("industry");
            case "previous_public_disclosures" -> q.contains("when") || q.contains("shared") || q.contains("abstract")
                    || q.contains("contain") || q.contains("detail") || q.contains("poster") || q.contains("paper");
            default -> false;
        };
    }

    static boolean asksAboutProblem(String text) {
        if (text == null || !text.contains("?")) return false;
        return PROBLEM_QUESTION.matcher(extractQuestion(text)).find();
    }

    static boolean asksForContactInfo(String text) {
        if (text == null || !text.contains("?")) return false;
        return CONTACT_INFO.matcher(extractQuestion(text)).find();
    }

    static boolean mentionsApplications(String msg) {
        return msg != null && APPLICATION_MENTION.matcher(msg).find();
    }

    private List<String> processNextFields(String msg, String text, Map<String, FieldAnswer> answers) {
        if (text == null || !text.contains("?")) return List.of();
        String m = msg == null ? "" : msg.toLowerCase(Locale.ROOT);
        if (m.contains("never existed")) return List.of("difference_from_others");
        if (!hasWorkDescription(answers)) return List.of("summary_general");
        return List.of();
    }

    private String spokenQuestion(ChecklistField field, String msg) {
        if (field == null) return "";
        String soft = ConversationStyle.softQuestion(field.id());
        return soft != null ? soft : field.starterQuestion();
    }

    private String steerToTopic(String text, ChecklistField field, String msg) {
        String spoken;
        if (ConversationStyle.looksLikeMechanism(msg)) {
            spoken = "When that effect happens, how can you tell? For example, is there a visible change, a signal, or a measurement?";
        } else if (isCorrection(msg) && PROTOTYPE_CUE.matcher(msg).find()) {
            spoken = "Could you describe the design and the material samples you have so far?";
        } else if (PAST_SHARE.matcher(msg).find()
                && msg.toLowerCase(Locale.ROOT).matches(".*\\b(didn'?t include|did not include|not include all)\\b.*")) {
            spoken = ConversationStyle.softQuestion("previous_public_disclosures");
        } else {
            spoken = spokenQuestion(field, msg);
        }
        if (spoken == null || spoken.isBlank()) return text == null ? "" : text;
        String exp = extractExplanation(text);
        if (exp.isBlank() || thinExplanation(exp) || ConversationStyle.isThinTurn(text)) return spoken;
        if (isCorrection(msg) && PROTOTYPE_CUE.matcher(msg).find()
                && exp.toLowerCase(Locale.ROOT).contains("started building")) exp = "";
        if (exp.isBlank()) return spoken;
        return exp + " " + spoken;
    }

    private static boolean thinExplanation(String explanation) {
        if (explanation == null || explanation.isBlank()) return true;
        return explanation.length() < 80 && explanation.split("[.!]\\s+").length <= 1;
    }

    static String stripPlaceholderSentences(String text) {
        if (text == null) return "";
        StringBuilder keep = new StringBuilder();
        for (String part : text.split("(?<=[.!?])\\s+")) {
            String s = part.replaceAll("\\s+", " ").strip();
            if (s.isEmpty() || PLACEHOLDER_NOTE.matcher(s).find()) continue;
            if (!keep.isEmpty()) keep.append(' ');
            keep.append(s);
        }
        return keep.toString().strip();
    }

    private static String joinWithAnd(List<String> items) {
        if (items.size() <= 1) return String.join("", items);
        return String.join(", ", items.subList(0, items.size() - 1)) + (items.size() > 2 ? "," : "") + " and " + items.get(items.size() - 1);
    }

    /**
     * Progress summary for the chat, built from the saved notes rather than the model, so it never invents details or
     * turns into an interview question. Topics follow the nine note sections.
     */
    private String buildDraftSummary(Draft d, Map<String, FieldAnswer> answers) {
        List<String> collected = new ArrayList<>(), partial = new ArrayList<>(), notDiscussed = new ArrayList<>();
        for (ChecklistService.Section s : checklist.sections(d.workflow())) {
            String title = s.section().title();
            List<String> said = new ArrayList<>(), open = new ArrayList<>();
            for (ChecklistField f : s.fields()) {
                FieldAnswer a = answers.getOrDefault(f.id(), FieldAnswer.EMPTY);
                if (a.hasText()) {
                    String cleaned = stripPlaceholderSentences(a.text());
                    if (!cleaned.isBlank()) said.add(NotesOrganizer.dedupeSentences(cleaned).replaceAll("[.]$", ""));
                    else open.add(f.label().toLowerCase(Locale.ROOT));
                }
                else if (a.mark() == ResearcherMark.NOT_YET_KNOWN) open.add(f.label().toLowerCase(Locale.ROOT) + " (not yet known)");
                else if (a.mark() != ResearcherMark.NOT_APPLICABLE) open.add(f.label().toLowerCase(Locale.ROOT));
            }
            InformationStatus status = InformationStatus.of(s.fields(), id -> answers.getOrDefault(id, FieldAnswer.EMPTY));
            if (said.isEmpty()) {
                if (status == InformationStatus.UNKNOWN) partial.add("- **" + title + ":** not yet known");
                else notDiscussed.add(title);
                continue;
            }
            String together = NotesOrganizer.dedupeSentences(String.join(". ", said.subList(0, Math.min(3, said.size()))));
            collected.add("- **" + title + ":** " + clip(together.replaceAll("[.]$", ""), 260) + ".");
            if (status != InformationStatus.COMPLETE && !open.isEmpty())
                partial.add("- **" + title + ":** " + String.join(", ", open.subList(0, Math.min(2, open.size()))));
        }

        StringBuilder all = new StringBuilder();
        answers.values().forEach(a -> all.append(a.text()).append('\n'));
        List<String> review = new ArrayList<>();
        boolean disclosed = answers.getOrDefault("previous_public_disclosures", FieldAnswer.EMPTY).hasText()
                || answers.getOrDefault("public_disclosures_list", FieldAnswer.EMPTY).hasText()
                || referral.evaluate(all.toString()).triggers().contains(ReferralDecision.Trigger.PAST_DISCLOSURE);
        if (disclosed) review.add("- **Public disclosure:** you mentioned sharing this work publicly. The office should review what was shared and when.");
        if (Stream.of("potential_inventors", "inventor_contributions", "concept_participants")
                .anyMatch(id -> answers.getOrDefault(id, FieldAnswer.EMPTY).hasText()))
            review.add("- **Inventorship:** each person's contributions should be documented, and the university reviews who qualifies as an inventor.");
        if (Stream.of("external_sponsors", "external_collaborators", "agreements", "commercialization_opportunities")
                .anyMatch(id -> answers.getOrDefault(id, FieldAnswer.EMPTY).hasText()))
            review.add("- **Outside parties:** involvement or interest from companies or other outside groups should be reviewed by the office.");

        if (collected.isEmpty() && partial.isEmpty())
            return "We haven't recorded any details about your work yet. Whenever you're ready, tell me a little about what you're working on.";
        StringBuilder sb = new StringBuilder("Here's a summary of what we've discussed so far.");
        if (!collected.isEmpty()) sb.append("\n\n**Information collected:**\n").append(String.join("\n", collected));
        if (!partial.isEmpty()) sb.append("\n\n**Could use more discussion:**\n").append(String.join("\n", partial));
        if (!notDiscussed.isEmpty()) sb.append("\n\n**Not discussed yet:** ").append(String.join(", ", notDiscussed)).append('.');
        if (!review.isEmpty()) sb.append("\n\n**Items for the university to review:**\n").append(String.join("\n", review));
        sb.append("\n\nYou don't have to complete everything at once. We can continue with whichever topic you'd like.");
        return sb.toString();
    }
}
