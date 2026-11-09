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
          + "|(?:that'?s|that is|this is) (?:a |an |really |very |so |such an? )?(?:good|great|interesting|fascinating|wonderful|impressive|helpful|excellent)(?: (?:point|question|idea|work|information))?"
          + "|(?:that'?s |that is |this is )?noted(?: with thanks)?"
          + "|(?:good|great|excellent|wonderful|fantastic|awesome|interesting|fascinating)(?: (?:question|answer|point|work))?"
          + "|what an? (?:interesting|great|good|fascinating) (?:idea|project|question))\\s*[.!,:;\\-\u2013\u2014]+\\s*", CI);

    private static final Pattern COMPOUND_QUESTION = Pattern.compile(
            "(?:,\\s*(?:and|or)\\s+(?:who|what|where|how|why|which|when|can|could|is|are|do|does)"
          + "|\\s+or\\s+(?:who|what|where|how|why|which|when)\\b"
          + "|(?:,\\s*|\\s+)and\\s+why\\s+it'?s\\s+important"
          + "|\\s+and\\s+how\\s+it\\s+(?:sends|transmits|connects|works|functions)"
          + "|(?:,\\s*|\\s+)and\\s+who\\s+(?:runs\\s+into|faces|experiences|it'?s\\s+for))\\b[^?.!\\n]*\\?", CI);

    private static final Pattern QUESTION_STARTER = Pattern.compile(
            "^\\s*(?:what|how|who|when|why|where|which|can|could|should|would|is|are|do|does|did|have|has)\\b", CI);

    private static final Pattern REDUNDANT_DISCLAIMER = Pattern.compile(
            "\\s*(?:official determinations are made by [^\n.!?]*|only (?:university|patent|qualified) staff can (?:make|determine)[^\n.!?]*)[.!?]", CI);

    private static final Pattern LIST_LINE = Pattern.compile("(?m)^\\s*(?:[-*\u2022]|\\d+[.)])\\s+\\S");
    private static final Pattern CHECKLIST_TALK = Pattern.compile(
            "\\b(checklist|fieldId|field id|form field|next field|following questions|please answer|remaining questions)\\b", CI);
    static final int MAX_CHARS = 700;

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
            "development_stage", "Have you built an early prototype, or is the idea still at the design stage?",
            "closest_products", "What existing products or approaches have you looked at so far? We can record what you know and leave the comparison open.",
            "inventor_contributions", "Could you briefly describe what each person contributed to developing it?",
            "evidence_testing", "Have you tested the current design or material samples, and what did you observe?");
    private static final Map<String, String> UNKNOWN_ACK = Map.of(
            "evidence_testing", "That's okay. We can still document what you've developed so far.",
            "difference_from_others", "That's understandable.",
            "closest_products", "That's understandable.",
            "prior_art", "That's understandable.");

    /** Removes stock openers, echoes, compound questions, and repeated openers. */
    public static String polish(String text, List<String> previousAssistantMessages) {
        return polish(text, previousAssistantMessages, null);
    }

    public static String polish(String text, List<String> previousAssistantMessages, String userMessage) {
        String         t = stripStockOpeners(text == null ? "" : text.strip());
        t = stripEcho(t, userMessage);
        t = t.replaceAll("(?i)this public disclosure", "what was shared");
        t = t.replaceAll("(?i)an abstract is considered a form of public disclosure\\.?\\s*", "");
        t = simplifyCompoundQuestion(t);
        return varyRepeatedOpener(t, previousAssistantMessages);
    }

    static String stripEcho(String text, String userMessage) {
        if (text == null || userMessage == null || userMessage.isBlank()) return text;
        String first = firstSentence(text);
        if (first.length() == text.length() || first.contains("?")) return text;
        String normUser = norm(userMessage);
        String normFirst = norm(first);
        boolean isEchoIntro = normFirst.startsWith("so ") || normFirst.startsWith("you mentioned that ")
                || normFirst.startsWith("sounds like ") || normFirst.startsWith("it sounds like ")
                || normFirst.startsWith("as you mentioned ") || normFirst.startsWith("as you said ");
        if (isEchoIntro) {
            int matches = 0;
            String[] words = normUser.split("\\s+");
            for (String w : words) {
                if (w.length() > 3 && normFirst.contains(w)) matches++;
            }
            if (matches >= 2) {
                String rest = text.substring(first.length()).strip();
                return rest.isEmpty() ? text : Character.toUpperCase(rest.charAt(0)) + rest.substring(1);
            }
        }
        return text;
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
        for (int i = 0; i < 2; i++) {
            Matcher m = STOCK_OPENER.matcher(t);
            if (!m.find()) break;
            String rest = t.substring(m.end()).strip();
            if (rest.isEmpty()) return text;       // the whole message was the opener: leave it, caller will treat as unusable
            t = Character.toUpperCase(rest.charAt(0)) + rest.substring(1);
        }
        return t;
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
