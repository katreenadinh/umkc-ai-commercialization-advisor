package edu.umkc.advisor.safety;

import edu.umkc.advisor.safety.ReferralDecision.Topic;
import edu.umkc.advisor.safety.ReferralDecision.Trigger;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Mandatory referral rules. Pure Java, deterministic, no LLM involved.
 * Run on every user message (questions and interview answers) BEFORE any model call.
 */
public final class ReferralService {

    public record Contact(String officeName, String email, String url) {
        String render() {
            String office = blank(officeName) ? "[CONFIGURE: referral office name]" : officeName.trim();
            String mail = blank(email) ? "[CONFIGURE: referral email]" : email.trim();
            String link = blank(url) ? "" : " More information: " + url.trim();
            return office + " at " + mail + "." + link;
        }
        private static boolean blank(String s) { return s == null || s.isBlank(); }
    }

    private static final int CI = Pattern.CASE_INSENSITIVE;

    // ---- imminent / past public disclosure -------------------------------------------------
    private static final Pattern FIRST_PERSON = Pattern.compile(
            "\\b(i|i'm|im|i'll|i've|we|we're|we'll|we've|my|our|me|us)\\b", CI);

    private static final Pattern DISCLOSURE_ACT = Pattern.compile(
            "\\b(present(?:ing|ation)?|publish(?:ing|ed)?|publication"
            + "|submit(?:ting)?\\s+(?:a\\s+|the\\s+|my\\s+|our\\s+)?(?:paper|abstract|manuscript|article)"
            + "|(?:giv(?:e|ing)|have|having)\\s+(?:a|an|my|our)\\s+(?:talk|presentation|seminar|webinar|keynote)"
            + "|poster|keynote|demo(?:nstrat(?:e|ing|ion))?|show(?:ing|case|casing)?"
            + "|post(?:ing)?\\s+(?:it\\s+|this\\s+|my\\s+|our\\s+)?(?:\\w+\\s+)?(?:on|to)\\s+(?:arxiv|biorxiv|medrxiv|github|the\\s+web|social\\s+media|linkedin|twitter)"
            + "|preprint|defen[cs]e|open[- ]sourc(?:e|ing)|releas(?:e|ing)|launch(?:ing)?|pitch(?:ing)?"
            + "|(?:at|for|in)\\s+(?:a|the|an)\\s+(?:\\w+\\s+)?(?:conference|symposium|workshop|trade\\s*show|expo|competition|demo\\s+day)"
            + "|go(?:ing)?\\s+public|announc(?:e|ing))\\b", CI);

    private static final String DAYS = "monday|tuesday|wednesday|thursday|friday|saturday|sunday";
    private static final Pattern STRONG_FUTURE = Pattern.compile(
            "\\b(tomorrow|tonight|today|later\\s+today|this\\s+(?:week|weekend|month|morning|afternoon|evening|" + DAYS + ")"
            + "|next\\s+(?:week|month|weekend|quarter|" + DAYS + ")"
            + "|in\\s+(?:a|an|one|two|three|four|five|six|a\\s+few|a\\s+couple\\s+of|\\d+)\\s+(?:hours?|days?|weeks?|months?)"
            + "|soon|shortly|upcoming|(?:on|by)\\s+(?:" + DAYS + ")|scheduled|due)\\b", CI);
    private static final Pattern WEAK_FUTURE = Pattern.compile(
            "\\b(will|going\\s+to|gonna|about\\s+to|set\\s+to|plan(?:ning|s)?\\s+to|planned|intend(?:s|ing)?\\s+to|expected\\s+to|hope\\s+to)\\b|'ll\\b", CI);
    private static final Pattern QUESTION_START = Pattern.compile(
            "^\\s*(what|how|why|when|where|who|which|should|can|could|would|will|do|does|is|are|am)\\b", CI);

    private static final Pattern PAST_ACT = Pattern.compile(
            "\\b(published|presented|demonstrated|showed|posted|released|disclosed|announced|sold|open[- ]sourced|launched)\\b", CI);
    private static final Pattern PAST_CUE = Pattern.compile(
            "\\b(already|yesterday|previously|earlier|last\\s+(?:week|month|year|semester)"
            + "|(?:\\d+|a\\s+few|a\\s+couple\\s+of|two|three|several)\\s+(?:days?|weeks?|months?|years?)\\s+ago"
            + "|have|has|had|i've|we've)\\b", CI);

    // ---- individual determinations ----------------------------------------------------------
    private record TopicRule(Topic topic, Pattern pattern) {}
    private static final List<TopicRule> TOPIC_RULES = List.of(
        new TopicRule(Topic.PATENTABILITY, p(
            "\\b(?:is|are|was|would|will|could|can|might)\\s+(?:it|this|that|my|our|the|these)\\b[^.?!]{0,60}\\bpatent(?:able|ed)\\b"
            + "|\\bcan\\s+(?:i|we)\\s+(?:get\\s+a\\s+)?patent\\b"
            + "|\\bshould\\s+(?:i|we)\\s+(?:file\\s+(?:for\\s+)?(?:a\\s+)?patent|patent)\\b"
            + "|\\bwill\\s+(?:i|we)\\s+(?:get|receive|be\\s+granted)\\s+a\\s+patent\\b")),
        new TopicRule(Topic.OWNERSHIP, p(
            "\\bwho\\s+(?:owns|will\\s+own|has\\s+(?:the\\s+)?rights\\s+to)\\b[^.?!]{0,60}\\b(?:my|our|this|the\\s+(?:code|software|invention|prototype|device|discovery))\\b"
            + "|\\bdo\\s+(?:i|we)\\s+(?:still\\s+)?(?:own|have\\s+(?:the\\s+)?rights)\\b"
            + "|\\bdoes\\s+(?:the\\s+university|umkc|um|the\\s+curators|otc|ott)\\s+(?:own|have\\s+(?:the\\s+)?rights)\\b[^.?!]{0,60}\\b(?:my|our|this)\\b"
            + "|\\bcan\\s+(?:i|we)\\s+(?:keep|retain)\\s+(?:the\\s+)?(?:rights|ownership)\\b")),
        new TopicRule(Topic.INVENTORSHIP, p(
            "\\b(?:am|are)\\s+(?:i|we|they)\\s+(?:an?\\s+|the\\s+)?(?:co-?\\s?|sole\\s+|joint\\s+)?inventors?\\b"
            + "|\\bshould\\s+\\w+(?:\\s+\\w+)?\\s+(?:be\\s+)?(?:listed|named|included)\\s+as\\s+(?:an?\\s+)?(?:co-?\\s?)?inventor"
            + "|\\bdoes\\s+(?:my|our|this)\\s+[^.?!]{0,40}qualify\\s+as\\s+(?:an?\\s+)?(?:co-?\\s?)?inventor"
            + "|\\bis\\s+(?:my|our|the|this)\\s+\\w+\\s+(?:an?\\s+)?(?:co-?\\s?)?inventor")),
        new TopicRule(Topic.PROTECTION_GUARANTEE, p(
            "\\b(?:guarantee[sd]?|ensure[sd]?|secure[sd]?)\\b[^.?!]{0,40}\\b(?:protect(?:ion|ed)?|patent|rights|ownership)\\b"
            + "|\\b(?:will|does|would)\\s+(?:submitting|filing|disclosing|a\\s+disclosure|an\\s+invention\\s+disclosure)\\b[^.?!]{0,60}\\b(?:protect|secure|guarantee|ensure)")),
        new TopicRule(Topic.UNIVERSITY_DECISION, p(
            "\\bwill\\s+(?:the\\s+university|umkc|um|otc|ott|the\\s+office|they)\\s+(?:pursue|file|commercialize|license|patent|accept|take\\s+on|fund|move\\s+forward)")),
        new TopicRule(Topic.NOVELTY_OR_VALUE, p(
            "\\b(?:is|are|would|will)\\s+(?:it|this|that|my|our|the)\\b[^.?!]{0,50}\\b(?:novel|new\\s+enough|original\\s+enough|innovative\\s+enough|commercially\\s+viable|marketable|valuable|worth\\s+(?:patenting|commercializ\\w+|pursuing|licensing|anything))\\b"
            + "|\\bhow\\s+much\\s+(?:is|could|would|might)\\s+(?:my|our|this|it)\\b[^.?!]{0,40}\\bworth\\b"
            + "|\\bdoes\\s+(?:my|our|this)\\b[^.?!]{0,50}\\bhave\\s+(?:any\\s+)?(?:commercial\\s+)?(?:value|potential|market)\\b")));

    // ---- prompt-injection style attempts ----------------------------------------------------
    private static final Pattern INJECTION = Pattern.compile(
            "ignore\\s+(?:all\\s+|any\\s+|the\\s+|your\\s+)?(?:previous|prior|above|earlier|safety)\\s+(?:instructions|rules|guidelines|prompts?)"
            + "|disregard\\s+[^.?!]{0,40}(?:instructions|rules|guidelines)"
            + "|(?:reveal|show|print|repeat)\\s+(?:me\\s+)?(?:your\\s+)?(?:system|hidden|initial)\\s+(?:prompt|instructions)"
            + "|\\byou\\s+are\\s+now\\b|developer\\s+mode|\\bjailbreak"
            + "|(?:pretend|act)\\s+(?:that\\s+)?(?:you\\s+)?(?:have\\s+no|without|are\\s+not\\s+bound)[^.?!]{0,30}(?:rules|restrictions|guidelines)"
            + "|override\\s+(?:your\\s+)?(?:safety|rules|instructions)", CI);

    private final Contact contact;

    public ReferralService(Contact contact) { this.contact = contact; }

    private static Pattern p(String regex) { return Pattern.compile(regex, CI); }

    public ReferralDecision evaluate(String rawText) {
        if (rawText == null || rawText.isBlank()) return ReferralDecision.none();
        String text = rawText.replace('\u2019', '\'').replace('\u2018', '\'');
        Set<Trigger> triggers = EnumSet.noneOf(Trigger.class);
        Set<Topic> topics = EnumSet.noneOf(Topic.class);

        boolean imminent = false, past = false;
        for (String sentence : text.split("(?<=[.!?])\\s+|\\n+")) {
            if (sentence.isBlank()) continue;
            if (isImminent(sentence)) imminent = true;
            if (isPast(sentence)) past = true;
        }
        if (imminent) triggers.add(Trigger.IMMINENT_DISCLOSURE);
        if (past) triggers.add(Trigger.PAST_DISCLOSURE);
        if (INJECTION.matcher(text).find()) triggers.add(Trigger.INJECTION_ATTEMPT);
        for (TopicRule r : TOPIC_RULES) {
            if (r.pattern.matcher(text).find()) topics.add(r.topic);
        }
        if (!topics.isEmpty()) triggers.add(Trigger.INDIVIDUAL_DETERMINATION);
        if (triggers.isEmpty()) return ReferralDecision.none();

        Trigger primary = imminent ? Trigger.IMMINENT_DISCLOSURE
                : triggers.contains(Trigger.INJECTION_ATTEMPT) ? Trigger.INJECTION_ATTEMPT
                : triggers.contains(Trigger.INDIVIDUAL_DETERMINATION) ? Trigger.INDIVIDUAL_DETERMINATION
                : Trigger.PAST_DISCLOSURE;

        boolean block = primary == Trigger.IMMINENT_DISCLOSURE || primary == Trigger.INJECTION_ATTEMPT;
        String blocking = switch (primary) {
            case IMMINENT_DISCLOSURE -> imminentMessage();
            case INJECTION_ATTEMPT -> injectionMessage();
            default -> null;
        };
        return new ReferralDecision(triggers, primary, block, blocking, buildNote(triggers, topics), topics);
    }

    /** Used when the relevance step asks for staff review but no deterministic rule fired. */
    public String genericReferralNote() {
        return "Questions like this need an individual review by qualified university staff, so please check with them "
             + "directly. Early, informal conversations are welcome.\n\nContact: " + contact.render();
    }

    public Contact contact() { return contact; }

    static boolean isImminent(String sentence) {
        if (!FIRST_PERSON.matcher(sentence).find() || !DISCLOSURE_ACT.matcher(sentence).find()) return false;
        if (STRONG_FUTURE.matcher(sentence).find()) return true;
        boolean question = sentence.trim().endsWith("?") || QUESTION_START.matcher(sentence).find();
        return !question && !sentence.toLowerCase().contains(" if ") && WEAK_FUTURE.matcher(sentence).find();
    }

    static boolean isPast(String sentence) {
        return FIRST_PERSON.matcher(sentence).find() && PAST_ACT.matcher(sentence).find()
                && PAST_CUE.matcher(sentence).find();
    }

    private String imminentMessage() {
        return "Timing can matter when you plan to publish, present, or publicly share your work, so it is worth "
             + "contacting the university's commercialization office soon, ideally before the event.\n\n"
             + "UMKC's guidance says that once a discovery is published or publicly disclosed, foreign patent rights "
             + "can be lost and a one-year window to file in the United States begins. How that applies to your "
             + "specific work is something only the office can assess. It is not a reason to panic, and I can't tell you "
             + "that sharing is either safe or harmful. Staff can talk through your timeline with you.\n\n"
             + "You do not need to finish this conversation or have a polished description first. An early, rough "
             + "conversation is welcome.\n\nContact: " + contact.render();
    }

    private String injectionMessage() {
        return "I can't change my safety guidelines or how I work, but I'm happy to help with questions about "
             + "intellectual property, invention disclosure, and research commercialization at UMKC.";
    }

    private String buildNote(Set<Trigger> triggers, Set<Topic> topics) {
        List<String> parts = new ArrayList<>();
        if (!topics.isEmpty()) {
            List<String> labels = new ArrayList<>();
            for (Topic t : topics) labels.add(t.label);
            parts.add("I can explain general concepts, but I can't determine " + String.join("; ", labels)
                    + " for your specific situation. That needs a qualified person at the university.");
        }
        if (triggers.contains(Trigger.PAST_DISCLOSURE)) {
            parts.add("You mentioned that some of your work may already have been shared publicly. Please let the "
                    + "commercialization office know the dates and details, since earlier public sharing can affect options.");
        }
        if (triggers.contains(Trigger.IMMINENT_DISCLOSURE)) {
            parts.add("Because you mentioned an upcoming public disclosure, please contact the office promptly.");
        }
        if (parts.isEmpty()) return null;
        parts.add("Contact: " + contact.render());
        return String.join("\n\n", parts);
    }
}
