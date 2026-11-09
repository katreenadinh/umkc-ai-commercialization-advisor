package edu.umkc.advisor.service;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Conversation-quality rules applied in Java, because a small local model will not always follow style instructions.
 * Nothing here touches safety logic: referral decisions, output guard and grounding checks are unchanged.
 */
public final class ConversationStyle {
    private ConversationStyle() {}

    private static final int CI = Pattern.CASE_INSENSITIVE;

    /** Stock openers and praise the interview should not lean on. */
    private static final Pattern STOCK_OPENER = Pattern.compile(
            "^(?:(?:thank you|thanks)(?: so much)?(?: for (?:sharing|that|explaining|the (?:details?|information|explanation)|letting me know|your (?:answer|response|explanation))(?: that| this| it| all that)?)?"
          + "|(?:that'?s|that is) great to hear"
          + "|(?:that'?s|that is|this is) (?:a |an |really |very |so |such an? )?(?:good|great|interesting|fascinating|wonderful|impressive|helpful|excellent|exciting|encouraging|promising)(?: (?:point|question|idea|work|information|start|step))?"
          + "|(?:that'?s |that is |this is )?noted(?: with thanks)?"
          + "|(?:good|great|excellent|wonderful|fantastic|awesome|interesting|fascinating|exciting)(?: (?:question|answer|point|work))?"
          + "|what an? (?:interesting|great|good|fascinating) (?:idea|project|question)"
          + "|certainly)\\s*[.!,:;\\-\u2013\u2014]+\\s*", CI);

    /** Praise that shows up after a short ack: "Got it. That's a great start! How does it work?" */
    private static final Pattern INTERIOR_PRAISE = Pattern.compile(
            "(?<=[.!?])\\s+(?:(?:that'?s|that is) great to hear"
          + "|(?:that'?s|that is|this is) (?:a |an |really |very |so )?(?:good|great|interesting|fascinating|wonderful|excellent|exciting|encouraging|promising)(?: (?:point|question|idea|work|information|start|step))?"
          + "|(?:that'?s|that is) exciting)\\s*[.!]+\\s*", CI);

    /** Empty praise that makes a reply sound like a form, not a colleague. */
    private static final Pattern PARAPHRASE_OPENER = Pattern.compile(
            "(?i)^(?:it )?sounds like\\b"
          + "|^(?:you(?:'re| are) working on)\\b"
          + "|^(?:so,?[ ']*)?(?:the |your )?(?:device|work|invention|system|sensor|method|material)\\b.{0,48}\\buses\\b"
          + "|^(?:so,?[ ']*)?(?:it|this|that)\\s+(?:uses|reacts)\\b");

    private static final Pattern FILLER_FRAME = Pattern.compile(
            "(?i)\\s+(?:is|sounds like) (?:a |an )?(?:clear |good |great |strong )?(?:starting point|first step|key component)(?=[.!]|$)"
          );
    private static final Pattern COMPOUND_QUESTION = Pattern.compile(
            "(?:,\\s*(?:and|or)\\s+(?:who|what|where|how|why|which|when|can|could|is|are|do|does)"
          + "|\\s+or\\s+(?:who|what|where|how|why|which|when)\\b"
          + "|(?:,\\s*|\\s+)and\\s+why\\s+it'?s\\s+important"
          + "|\\s+and\\s+how\\s+it\\s+(?:sends|transmits|connects|works|functions|detects|identifies)"
          + "|(?:,\\s*|\\s+)and\\s+who\\s+(?:runs\\s+into|faces|experiences|it'?s\\s+for))\\b[^?.!\\n]*\\?", CI);

    private static final Pattern QUESTION_STARTER = Pattern.compile(
            "^\\s*(?:what|how|who|when|why|where|which|can|could|should|would|is|are|do|does|did|have|has)\\b", CI);

    private static final Pattern REDUNDANT_DISCLAIMER = Pattern.compile(
            "\\s*(?:official determinations are made by [^\n.!?]*|only (?:university|patent|qualified) staff can (?:make|determine)[^\n.!?]*)[.!?]", CI);

    private static final Pattern LIST_LINE = Pattern.compile("(?m)^\\s*(?:[-*\u2022]|\\d+[.)])\\s+\\S");
    private static final Pattern CHECKLIST_TALK = Pattern.compile(
            "\\b(checklist|fieldId|field id|form field|next field|following questions|please answer|remaining questions)\\b", CI);
    static final int MAX_CHARS = 1400;

    private static final List<String> BRIDGES = List.of("Got it.", "Okay.", "Makes sense.", "Understood.", "Good to know.");
    private static final List<String> SKIP_ACKS = List.of(
            "No problem, we can come back to that anytime.", "Sure, we can leave that for now.", "That's fine, we'll skip it for now.");

    /** After an "unknown", the most natural next topic is a gentler, related one (see the brief's examples B and C). */
    static final Map<String, String> COMPANION = Map.of(
            "evidence_testing", "development_stage",
            "difference_from_others", "development_stage",
            "closest_products", "development_stage",
            "potential_inventors", "inventor_contributions");
    private static final Map<String, String> SOFT_QUESTION = Map.of(
            "technical_description", "Could you tell me how it works? For example, does it use a measurement, a chemical or material reaction, or another method?",
            "development_stage", "Have you started building or testing it?",
            "closest_products", "Have you come across any existing approaches that try to do something similar?",
            "inventor_contributions", "Could you briefly describe what each person contributed?",
            "evidence_testing", "Have you tested the current design or material samples, and what did you observe?",
            "potential_licensees", "Has any of the work already been shared with them — a description, data, or materials?",
            "applications_users", "Who might benefit most from this, or in what kind of setting?",
            "previous_public_disclosures", "Do you remember roughly when it was shared, and whether it included how it works?");
    private static final Map<String, String> UNKNOWN_ACK = Map.of(
            "evidence_testing", "That's okay. We can still document what you've developed so far.",
            "difference_from_others", "That's completely fine. You don't need to have completed a comparison of existing technologies to discuss your research. We can return to that later. For now, I'd like to understand the current development of your work.",
            "closest_products", "That's understandable.",
            "prior_art", "That's understandable.");
    private static final Pattern MECHANISM_CUE = Pattern.compile(
            "(?i)\\b(reacts?|reaction|uses a .{0,40}material|special material|when .{0,30}(?:are )?present|changes (?:color|when)|produces? a signal)\\b");
    private static final Map<String, String> COLLEAGUE_TURN = Map.of(
            "technical_description", "That sounds like a project worth talking through. Understanding the method will help us document it. Could you tell me how it works? For example, does it use a measurement, a chemical or material reaction, or another method?",
            "technical_mechanism", "That helps me understand the general idea. When that effect happens, how can you tell? For example, is there a visible change, a signal, or a measurement?",
            "inventor_contributions", "Several people have contributed to the project. For a disclosure, it's important to document what each person did so the university can review inventorship. Could you briefly describe what each person contributed?",
            "evidence_testing", "Those early observations are still useful to document, even if the work is not far along. Have you tested the current design or material samples, and what did you observe?",
            "potential_licensees", "Outside interest is worth noting, mainly so we know whether any of the work has already left the group. Has any of the work already been shared with them — a description, data, or materials?",
            "previous_public_disclosures", "Anything already shared publicly is useful to document. Do you remember roughly when it was shared, and whether it included how it works?",
            "development_stage", "A working prototype is not required for this conversation. We can still document a design, samples, or whatever exists now. Could you describe what you have in front of you so far?",
            "applications_users", "Possible uses are just possibilities at this stage, not a conclusion about a market. Who might benefit most from this, or in what kind of setting?",
            "difference_from_others", "Not every part has to be brand new — a combination or improvement can still matter. Have you come across any existing approaches that try to do something similar?",
            "summary_general", "Early-stage work is still worth talking through. Could you tell me a little more about your research idea?");

    /** Removes stock openers, echoes, compound questions, and repeated openers. */
    public static String polish(String text, List<String> previousAssistantMessages) {
        return polish(text, previousAssistantMessages, null);
    }

    public static String polish(String text, List<String> previousAssistantMessages, String userMessage) {
        String         t = stripStockOpeners(text == null ? "" : text.strip());
        t = FILLER_FRAME.matcher(t).replaceAll("").replaceAll(" {2,}", " ").strip();
        if (!t.isEmpty()) t = Character.toUpperCase(t.charAt(0)) + t.substring(1);
        t = stripEcho(t, userMessage);
        t = t.replaceAll("(?i)this public disclosure", "what was shared");
        t = t.replaceAll("(?i)an abstract is considered a form of public disclosure\\.?\\s*", "");
        t = t.replaceAll("(?i)\\bvaluable intellectual property\\b", "potential intellectual property");
        t = t.replaceAll("(?i)\\b(?:can|could) (?:still )?be valuable\\b", "can still matter");
        t = t.replaceAll("(?i)\\bto be valuable\\b", "");
        t = t.replaceAll("(?i)\\ba novel solution\\b", "a possible approach");
        t = t.replaceAll("(?i)\\boffers a novel solution\\b", "can still matter even if it builds on existing work");
        t = t.replaceAll("(?i)\\bpotential licensee[s]?\\b", "company that may be interested");
        t = t.replaceAll(" {2,}", " ").replaceAll(" \\.", ".");
        t = simplifyCompoundQuestion(t);
        t = keepFirstQuestion(t);
        return varyRepeatedOpener(t, previousAssistantMessages);
    }

    static String stripEcho(String text, String userMessage) {
        if (text == null || userMessage == null || userMessage.isBlank()) return text;
        String[] parts = text.split("(?<=[.!?])\\s+");
        StringBuilder keep = new StringBuilder();
        for (String part : parts) {
            String s = part.strip();
            if (s.isEmpty()) continue;
            boolean question = s.contains("?");
            boolean shortAck = s.length() < 24 && !question;
            boolean fragment = !question && s.length() < 55 && s.split("\\s+").length <= 8
                    && s.matches("(?i)^the\\b.*")
                    && !s.matches("(?i).*\\b(is|are|was|were|can|could|will|would|helps?|means?|matters?|does|do)\\b.*");
            boolean paraphrase = PARAPHRASE_OPENER.matcher(s).find();
            boolean echo = !question && !shortAck && (
                    (TextOverlap.coverage(s, userMessage) >= 0.72 && TextOverlap.coverage(userMessage, s) >= 0.45)
                    || (paraphrase && TextOverlap.coverage(s, userMessage) >= 0.50));
            if (echo || fragment) continue;
            if (!keep.isEmpty()) keep.append(' ');
            keep.append(s);
        }
        String out = keep.toString().strip();
        return out.isEmpty() ? text : Character.toUpperCase(out.charAt(0)) + out.substring(1);
    }

    static String simplifyCompoundQuestion(String text) {
        if (text == null || !text.contains("?")) return text;
        int lastQ = text.lastIndexOf('?');
        int start = 0;
        for (int i = lastQ - 1; i >= 0; i--) {
            char c = text.charAt(i);
            if (c == '.' || c == '!' || c == '\n') {
                start = i + 1;
                break;
            }
        }
        String prefix = text.substring(0, start);
        String question = text.substring(start, lastQ + 1);
        if (!QUESTION_STARTER.matcher(question).find()) {
            return text;
        }
        Matcher m = COMPOUND_QUESTION.matcher(question);
        if (m.find()) {
            String simplifiedQuestion = question.substring(0, m.start()).stripTrailing() + "?";
            return prefix + simplifiedQuestion + text.substring(lastQ + 1);
        }
        return text;
    }

    public static String stripRedundantDisclaimer(String text) {
        if (text == null || text.isBlank()) return text;
        return REDUNDANT_DISCLAIMER.matcher(text).replaceAll("").replaceAll(" {2,}", " ").strip();
    }

    static String stripStockOpeners(String text) {
        String t = text;
        for (int i = 0; i < 4; i++) {
            Matcher m = STOCK_OPENER.matcher(t);
            if (m.find()) {
                String rest = t.substring(m.end()).strip();
                if (rest.isEmpty()) return text;       // the whole message was the opener: leave it, caller will treat as unusable
                t = Character.toUpperCase(rest.charAt(0)) + rest.substring(1);
                continue;
            }
            Matcher inner = INTERIOR_PRAISE.matcher(t);
            if (!inner.find()) break;
            String rebuilt = (t.substring(0, inner.start()) + " " + t.substring(inner.end())).replaceAll(" {2,}", " ").strip();
            if (rebuilt.isEmpty()) return text;
            t = rebuilt;
        }
        return t;
    }

    /** One follow-up reads like a colleague; two in a row reads like a form. Keep a "For example" clarifier. */
    static String keepFirstQuestion(String text) {
        if (text == null || text.isBlank()) return text;
        int first = text.indexOf('?');
        if (first < 0) return text;
        int second = text.indexOf('?', first + 1);
        if (second < 0) return text;
        String after = text.substring(first + 1).strip();
        if (after.toLowerCase().startsWith("for example")) return text;
        return text.substring(0, first + 1).strip();
    }

    static String varyRepeatedOpener(String text, List<String> previous) {
        String first = firstSentence(text);
        if (first.length() == text.length()) return text;
        String rest = text.substring(first.length()).strip();
        if (!rest.contains("?")) return text;
        for (String p : previous) {
            if (p == null || p.isBlank()) continue;
            String pf = firstSentence(p.strip());
            if (norm(pf).equals(norm(first)) || sameFirstWords(pf, first)) return rest;
        }
        return text;
    }

    /** True when the message reads like a form or a list rather than a conversational turn. */
    public static boolean isFormLike(String text) {
        return LIST_LINE.matcher(text).find() || CHECKLIST_TALK.matcher(text).find() || text.length() > MAX_CHARS;
    }

    public static String bridge(int assistantTurnCount) { return BRIDGES.get(Math.floorMod(assistantTurnCount, BRIDGES.size())); }

    public static String skipAck(int assistantTurnCount) { return SKIP_ACKS.get(Math.floorMod(assistantTurnCount, SKIP_ACKS.size())); }

    public static String unknownAck(String fieldId) {
        return UNKNOWN_ACK.getOrDefault(fieldId == null ? "" : fieldId, "That's completely fine. Not knowing yet is normal, and I've noted it.");
    }

    /** A gentler phrasing for the question that follows an "unknown", or null if there is no special phrasing. */
    public static String softQuestion(String fieldId) { return SOFT_QUESTION.get(fieldId); }

    static boolean looksLikeMechanism(String msg) {
        return msg != null && MECHANISM_CUE.matcher(msg).find();
    }

    /** True when the follow-up is a blank "how does it work" with no detail from what they said. */
    public static boolean genericInterviewQuestion(String text) {
        if (text == null || !text.contains("?")) return false;
        String q = text.substring(Math.max(0, firstQuestionStart(text))).toLowerCase();
        int end = q.indexOf('?');
        if (end >= 0) q = q.substring(0, end + 1);
        if (q.matches(".*\\b(detect|measur|react|signal|threshold|conductiv|component|wavelength|voltage|contribut|shared|test|sample|prototype|design)\\w*.*"))
            return false;
        return (q.contains("how") && q.contains("work"))
                || q.contains("tell me more")
                || q.contains("tell me a bit more")
                || q.contains("tell me a little more");
    }

    /** A full 3–4 sentence colleague turn for this topic, or null if there is no template. */
    public static String colleagueTurn(String fieldId, String userMessage) {
        if (fieldId == null) return null;
        if ("technical_description".equals(fieldId) && looksLikeMechanism(userMessage)) {
            return COLLEAGUE_TURN.get("technical_mechanism");
        }
        return COLLEAGUE_TURN.get(fieldId);
    }

    /** True when the reply is still just an acknowledgement plus a question. */
    public static boolean isThinTurn(String text) {
        if (text == null || text.isBlank()) return true;
        if (!text.contains("?")) return false;
        String exp = text.substring(0, Math.max(0, firstQuestionStart(text))).strip();
        if (exp.isBlank()) return true;
        if (exp.matches("(?i)^(got it|okay|ok|makes sense|understood|good to know|thanks(?: for sharing that)?)(?:[.!]\\s*(?:got it|okay|ok|makes sense|understood|good to know))*\\s*[.!]\\s*$"))
            return true;
        if (exp.length() < 90 && exp.matches("(?i).*(?:great start|great to hear|that'?s exciting|that'?s interesting).*"))
            return true;
        return PARAPHRASE_OPENER.matcher(exp).find() && exp.length() < 180;
    }

    private static int firstQuestionStart(String text) {
        int q = text.indexOf('?');
        if (q < 0) return 0;
        for (int i = q - 1; i >= 0; i--) {
            char c = text.charAt(i);
            if (c == '.' || c == '!' || c == '\n') return i + 1;
        }
        return 0;
    }

    /** If the model only wrote a short ack plus a question, keep their follow-up and drop the empty ack. */
    public static String expandThinReply(String text, String fieldId, String userMessage) {
        if (text == null || text.isBlank() || !isThinTurn(text)) return text;
        String questions = keepQuestionSentences(text);
        if (questions.isBlank()) return text;
        String q = keepFirstQuestion(questions);
        if (q.isBlank()) return text;
        if ("technical_description".equals(fieldId) && looksLikeMechanism(userMessage)
                && !q.toLowerCase().contains("how can you tell")
                && !q.toLowerCase().contains("visible change")) {
            return "When that effect happens, how can you tell? For example, is there a visible change, a signal, or a measurement?";
        }
        if ("technical_description".equals(fieldId) && !q.toLowerCase().contains("for example")
                && q.toLowerCase().contains("how")
                && !q.toLowerCase().contains("happen")
                && !q.toLowerCase().contains("when the")) {
            q = q + " For example, does it use a measurement, a chemical or material reaction, or another method?";
        }
        return q;
    }

    static String framingSentences(String turn) {
        if (turn == null) return "";
        StringBuilder b = new StringBuilder();
        for (String part : turn.split("(?<=[.!?])\\s+")) {
            String s = part.strip();
            if (s.isEmpty() || s.contains("?")) continue;
            if (!b.isEmpty()) b.append(' ');
            b.append(s);
        }
        return b.toString();
    }

    public static String expandThinReply(String text, String fieldId) {
        return expandThinReply(text, fieldId, null);
    }

    static String keepQuestionSentences(String text) {
        if (text == null || !text.contains("?")) return "";
        StringBuilder q = new StringBuilder();
        for (String part : text.split("(?<=[.!?])\\s+")) {
            String s = part.strip();
            if (s.contains("?")) {
                if (!q.isEmpty()) q.append(' ');
                q.append(s);
            }
        }
        return q.toString();
    }

    private static int lastQuestionStart(String text) {
        int lastQ = text.lastIndexOf('?');
        for (int i = lastQ - 1; i >= 0; i--) {
            char c = text.charAt(i);
            if (c == '.' || c == '!' || c == '\n' || c == '?') return i + 1;
        }
        return 0;
    }

    private static String firstSentence(String t) {
        Matcher m = Pattern.compile("^.*?[.!?](?=\\s|$)", Pattern.DOTALL).matcher(t);
        return m.find() ? m.group() : t;
    }

    private static boolean sameFirstWords(String a, String b) {
        String[] x = norm(a).split(" "), y = norm(b).split(" ");
        return x.length >= 2 && y.length >= 2 && x[0].equals(y[0]) && x[1].equals(y[1]);
    }

    private static String norm(String s) { return s.toLowerCase().replaceAll("[^a-z0-9]+", " ").strip(); }
}
