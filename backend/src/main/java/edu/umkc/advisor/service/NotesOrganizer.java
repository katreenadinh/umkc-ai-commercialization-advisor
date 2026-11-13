package edu.umkc.advisor.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.umkc.advisor.ai.LlmClient;
import edu.umkc.advisor.model.*;
import edu.umkc.advisor.repository.DraftRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Rewrites a draft's notes after each researcher message: every fact is summarized and placed under the heading it fits
 * best, using the whole conversation. Runs in the background so the coach's reply is not delayed. A note is only saved if
 * it passes {@link NotesSummaryWriter#grounded}; headings the model leaves out keep what they had, and nothing changes
 * if the model fails or the draft was edited while the notes were being organized.
 */
public final class NotesOrganizer {
    private static final int MAX_NOTE_CHARS = 400;
    private static final int MAX_TURNS = 40;
    private static final int SECTIONS_PER_CALL = 3;
    private static final double MIN_SUPPORTED_WORDS = 0.5;
    private static final Pattern CONTENT_WORD = Pattern.compile("[a-z][a-z'’-]{3,}");
    private static final Set<String> STOPWORDS = Set.of("that", "this", "these", "those", "with", "from", "into", "when", "where",
            "which", "while", "their", "there", "they", "them", "then", "than", "have", "has", "had", "been", "being", "were", "will",
            "would", "could", "should", "about", "after", "before", "also", "only", "some", "such", "each", "other", "more", "most",
            "very", "what", "researcher", "researchers", "does", "doing", "done", "make", "makes", "made", "uses", "using", "used");
    private static final Pattern PAST_SHARING = Pattern.compile(
            "\\b(presented|published|posted|shared|showed|shown|demoed|demonstrated|gave|already|previously|ago|last (week|month|year|spring|summer|fall|winter|semester|term))\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern NO_INFO = Pattern.compile(
            "(?i)(n/?a|none|unknown|not (yet )?(mentioned|discussed|provided|specified|stated|known|applicable)|no (information|details?|mention)( (given|provided|mentioned))?)\\.?");
    private static final Pattern PLACEHOLDER_PHRASE = Pattern.compile(
            "(?i)not specified(?: in the conversation)?|implied importance|not (?:yet )?(?:mentioned|discussed|provided|stated) in the conversation");
    private static final Pattern FUTURE_SHARING = Pattern.compile(
            "\\b(will|plan|plans|planned|planning|going to|next|upcoming|soon|intends?|hopes?)\\b", Pattern.CASE_INSENSITIVE);
    private static final Set<String> DISCLOSURE_FIELDS = Set.of(
            "public_disclosures_list", "previous_public_disclosures", "disclosure_type", "disclosure_content",
            "disclosure_details", "date_first_public_disclosure");
    private static final Pattern LOOKS_LIKE_DISCLOSURE = Pattern.compile(
            "(?i)\\b(present(?:ing|ed|ation)?|publish(?:ing|ed)?|publication|paper|poster|talk|conference|symposium|abstract|preprint|thesis|theses"
          + "|website post|demo|disclos|arxiv|journal|seminar|webinar|open[- ]source)\\b");
    private static final Pattern LOOKS_LIKE_SUMMARY = Pattern.compile(
            "(?i)\\b(device|sensor|app(?:lication)?|method|packaging|invention|system|algorithm|filter)\\b");
    private static final Pattern LOOKS_LIKE_PROTOTYPE = Pattern.compile("(?i)\\bprototype");
    private static final Pattern PROBLEM_STATED = Pattern.compile(
            "(?i)\\b(the problem|problem is|the need|need being|challenge|limitation|who (?:has|faces|experiences)|struggle|forget because)\\b");
    private static final Set<String> PROBLEM_FIELDS = Set.of(
            "problem_solved", "problem_who", "problem_importance", "problem_challenges");
    /** Coach headings people skip away from; a bare "prototype" description may move from these to summary_general. */
    private static final Set<String> PROTOTYPE_TO_SUMMARY = Set.of(
            "funding", "agreements", "potential_inventors", "inventor_contributions", "difference_from_others",
            "applications_users", "problem_solved", "problem_who", "problem_importance", "problem_challenges",
            "technical_description", "development_stage", "public_disclosures_list", "previous_public_disclosures",
            "disclosure_type", "disclosure_content", "disclosure_details", "date_first_public_disclosure");
    private record TopicHint(String fieldId, Pattern pattern) {
        boolean matches(String text) { return pattern.matcher(text).find(); }
    }
    /** Specific topics first. Used when a note is sitting under the heading the coach asked about, not the topic of the answer. */
    private static final List<TopicHint> TOPICS = List.of(
            new TopicHint("funding", Pattern.compile("(?i)\\b(grant|grants|funding|funded|nsf|nih|usda|darpa|sbir|sponsor(?:ed|s)?|outside support|department funds)\\b")),
            new TopicHint("inventor_contributions", Pattern.compile("(?i)\\b(wrote the|designed the|built the|i did the|they did the|contributed)\\b")),
            new TopicHint("potential_inventors", Pattern.compile("(?i)\\b(grad(?:uate)? student|postdoc|professor|colleague|advisor|post-doc|who helped|co-inventor)\\b")),
            new TopicHint("inventor_departments", Pattern.compile("(?i)^(?=.*\\b(?:department of|school of|college of|chemistry department|engineering department)\\b)(?!.*\\b(?:student|professor|postdoc|colleague|inventor|helped)\\b).{0,120}$")),
            new TopicHint("concept_participants", Pattern.compile("(?i)\\b(helped shape|core ideas?|came up with the (?:idea|concept))\\b")),
            new TopicHint("grant_numbers", Pattern.compile("(?i)\\b(?:award|grant)\\s*(?:no\\.?|number|#)\\s*[A-Z0-9]")),
            new TopicHint("evidence_testing", Pattern.compile("(?i)\\b(tested|testing|tests|experiment|trial|samples?|tried it|composted|haven'?t tested)\\b")),
            new TopicHint("preliminary_results", Pattern.compile("(?i)\\b((?:the |our |preliminary )?results (?:showed|were|indicated)|we observed|it seemed to work|picked up)\\b")),
            new TopicHint("development_stage", Pattern.compile("(?i)\\b(design stage|early[- ]stage|nothing built|haven'?t built|working prototype|prototype stage|still (?:an )?idea|proof[- ]of[- ]concept)\\b")),
            new TopicHint("problem_challenges", Pattern.compile("(?i)\\b(challenges?|limitations?|obstacles?|hard to|difficult|making sure|constraint)\\b")),
            new TopicHint("problem_who", Pattern.compile("(?i)\\b(who (?:has|faces|experiences)|farmers|nurses|caretakers|linemen|utilities|patients)\\b")),
            new TopicHint("problem_importance", Pattern.compile("(?i)\\b(matters|important|illness(?:es)?|getting sick|without worrying|allows them|harm)\\b")),
            new TopicHint("difference_from_others", Pattern.compile("(?i)\\b(differ|unlike|existing (?:methods?|approaches|products)|compared to|other (?:methods?|approaches))\\b")),
            new TopicHint("problem_solved", Pattern.compile("(?i)\\b(the problem|problem is|the need|need being|waste water|no way to)\\b")),
            new TopicHint("agreements", Pattern.compile("(?i)\\b(mta|nda|agreement|material transfer)\\b")),
            new TopicHint("applications_users", Pattern.compile("(?i)\\b(could be used|outside the lab|(?:practical |possible |other )?applications|municipal)\\b")),
            new TopicHint("technical_description", Pattern.compile("(?i)\\b(it (?:uses|measures|detects)|works by|made of|probe|solar panel|chip|material that reacts|circuit)\\b")));
    private static final Pattern NAMED_TITLE = Pattern.compile(
            "(?i)\\b(?:the |a |our |its )?(?:working )?title(?: for (?:this|it|the (?:work|invention|device|project)))?\\s*(?:is|:|should be)\\s+[\"']?([^\\n\"']+?)[\"']?(?:[.!?]|\\n|$)"
          + "|\\b(?:call(?:ed)? it|name it|it(?:'?s| is) called)\\s+[\"']?([^\\n\"']{3,80}?)[\"']?(?:[.!?]|\\n|$)");

    private final ChecklistService checklist;
    private final DraftRepository repo;
    private final LlmClient llm;
    private final String systemPrompt;
    private final int maxTokens;
    private final ObjectMapper mapper = new ObjectMapper();
    private final ExecutorService pool = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "notes-organizer");
        t.setDaemon(true);
        return t;
    });
    private final Set<String> running = ConcurrentHashMap.newKeySet();
    private final Set<String> again = ConcurrentHashMap.newKeySet();
    private final Map<String, ScheduledFuture<?>> waiting = new ConcurrentHashMap<>();
    private final AtomicBoolean hold = new AtomicBoolean();
    private final ScheduledExecutorService delay = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "notes-organizer-delay");
        t.setDaemon(true);
        return t;
    });
    static final long DEBOUNCE_MS = 8_000;
    private static final Logger log = LoggerFactory.getLogger(NotesOrganizer.class);

    public NotesOrganizer(ChecklistService checklist, DraftRepository repo, LlmClient llm, String systemPrompt, int maxTokens) {
        this.checklist = checklist; this.repo = repo; this.llm = llm; this.systemPrompt = systemPrompt; this.maxTokens = maxTokens;
    }

    public boolean isOrganizing(String draftId) { return running.contains(draftId); }

    /** Pause background LLM work so the next interview turn can use the local model immediately. */
    public void holdForInterview() { hold.set(true); }

    /** Queues a reorganization after a short pause so rapid chat does not compete for the local GPU. */
    public synchronized void organizeLater(String draftId, String owner) {
        hold.set(false);
        ScheduledFuture<?> prev = waiting.remove(draftId);
        if (prev != null) prev.cancel(false);
        waiting.put(draftId, delay.schedule(() -> {
            waiting.remove(draftId);
            if (hold.get()) return;
            if (!running.add(draftId)) { again.add(draftId); return; }
            pool.execute(() -> loop(draftId, owner));
        }, DEBOUNCE_MS, TimeUnit.MILLISECONDS));
    }

    private void loop(String draftId, String owner) {
        while (true) {
            try { organizeNow(draftId, owner); } catch (RuntimeException ignored) { /* keep the existing notes */ }
            synchronized (this) {
                if (!again.remove(draftId)) { running.remove(draftId); return; }
            }
        }
    }

    /** @return true when the notes were rewritten */
    boolean organizeNow(String draftId, String owner) {
        Draft d = repo.find(draftId, owner).orElse(null);
        if (d == null) return false;
        Map<String, FieldAnswer> organized = organize(d, repo.turns(draftId));
        if (organized == null || organized.equals(d.answers())) return false;
        Draft latest = repo.find(draftId, owner).orElse(null);
        if (latest == null) return false;
        if (!latest.answers().equals(d.answers())) { again.add(draftId); return false; }
        repo.saveAnswers(draftId, organized);
        return true;
    }

    /** @return the reorganized answers, or null when there is nothing to do or the model's reply is unusable */
    Map<String, FieldAnswer> organize(Draft d, List<Turn> turns) {
        StringBuilder said = new StringBuilder();
        turns.stream().filter(t -> t.role().equals("USER")).forEach(t -> said.append(t.content()).append('\n'));
        if (said.toString().isBlank()) return null;

        List<ChecklistField> fields = checklist.applicable(d.workflow());
        List<JsonNode> notes = new ArrayList<>();
        boolean anyReply = false;
        for (List<ChecklistService.Section> group : groups(d.workflow())) {
            if (hold.get()) break;
            try {
                String reply = llm.complete(systemPrompt, prompt(d, group, fields, turns), maxTokens);
                log.debug("Organizer reply for draft {}: {}", d.id(), reply);
                int start = reply.indexOf('{'), end = reply.lastIndexOf('}');
                if (start < 0 || end <= start) continue;
                mapper.readTree(reply.substring(start, end + 1)).path("notes").forEach(notes::add);
                anyReply = true;
            } catch (Exception e) {
                log.debug("Organizer call failed for draft {}: {}", d.id(), e.toString());
            }
        }
        Map<String, ChecklistField> byId = new HashMap<>();
        fields.forEach(f -> byId.put(f.id(), f));
        Map<String, FieldAnswer> out = new LinkedHashMap<>(d.answers());
        if (!anyReply) {
            boolean titled = applyNamedTitle(out, byId, said.toString());
            rehomeMismatched(out, byId);
            return titled || !out.equals(d.answers()) ? out : null;
        }

        Set<String> seen = new HashSet<>();
        for (JsonNode n : notes) {
            String id = n.path("fieldId").asText("");
            if (!byId.containsKey(id) || !seen.add(id)) continue;
            String text = n.path("text").asText("").replaceAll("\\s+", " ").strip();
            if (NO_INFO.matcher(text).matches() || isPlaceholderNote(text)) { seen.remove(id); continue; }
            FieldAnswer old = d.answer(id);
            if (text.isEmpty()) {
                if (old.hasText()) {
                    if (old.mark() == ResearcherMark.NONE) out.remove(id);
                    else out.put(id, new FieldAnswer("", old.mark()));
                }
                continue;
            }
            if (text.length() > MAX_NOTE_CHARS || !NotesSummaryWriter.grounded(text, said.toString()) || !supported(text, said.toString())
                    || leakedFromCoach(text, said.toString(), turns)) {
                log.debug("Organizer dropped note for {}: {}", id, text);
                continue;
            }
            if (isProblemField(id) && !PROBLEM_STATED.matcher(said).find()) {
                if (old.hasText() && !PROBLEM_STATED.matcher(old.text()).find() && old.mark() == ResearcherMark.NONE) {
                    out.remove(id);
                }
                continue;
            }
            text = dedupeSentences(text);
            if (id.equals("public_disclosures_list") && byId.containsKey("previous_public_disclosures")
                    && PAST_SHARING.matcher(text).find() && !FUTURE_SHARING.matcher(text).find()) {
                if (!seen.contains("previous_public_disclosures") && !out.getOrDefault("previous_public_disclosures", FieldAnswer.EMPTY).hasText()) {
                    out.put("previous_public_disclosures", new FieldAnswer(text, ResearcherMark.NONE));
                    seen.add("previous_public_disclosures");
                }
                if (old.mark() == ResearcherMark.NONE) out.remove(id); else out.put(id, new FieldAnswer("", old.mark()));
                continue;
            }
            ResearcherMark mark = old.mark() == ResearcherMark.NOT_YET_KNOWN && old.hasText() ? ResearcherMark.NOT_YET_KNOWN : ResearcherMark.NONE;
            out.put(id, new FieldAnswer(text, mark));
        }
        applyNamedTitle(out, byId, said.toString());
        FieldSeeding.apply(out, said.toString());
        rehomeMismatched(out, byId);
        for (String id : List.copyOf(out.keySet())) {
            FieldAnswer a = out.get(id);
            if (a != null && a.hasText()) {
                String cleaned = dedupeSentences(a.text());
                if (!cleaned.equals(a.text())) out.put(id, new FieldAnswer(cleaned, a.mark()));
            }
        }
        return out.equals(d.answers()) ? null : out;
    }

    /**
     * Notes sometimes land under the heading the coach just asked about, even when the researcher answered a different
     * topic. Move those by what the text is actually about, including when they skip around mid-conversation.
     */
    static void rehomeMismatched(Map<String, FieldAnswer> out, Map<String, ChecklistField> byId) {
        for (String id : List.copyOf(out.keySet())) {
            if ("title".equals(id) || !byId.containsKey(id)) continue;
            FieldAnswer a = out.getOrDefault(id, FieldAnswer.EMPTY);
            if (!a.hasText()) continue;
            String dest = homeFor(id, a.text());
            if (dest.equals(id) || !byId.containsKey(dest)) continue;
            FieldAnswer there = out.getOrDefault(dest, FieldAnswer.EMPTY);
            if (!there.hasText()) out.put(dest, new FieldAnswer(a.text(), ResearcherMark.NONE));
            else if (!there.text().toLowerCase(Locale.ROOT).contains(a.text().toLowerCase(Locale.ROOT)))
                out.put(dest, new FieldAnswer(dedupeSentences(there.text() + " " + a.text()), ResearcherMark.NONE));
            if (a.mark() == ResearcherMark.NONE) out.remove(id);
            else out.put(id, new FieldAnswer("", a.mark()));
        }
    }

    /** Where a note should live: keep it if it fits this heading, otherwise the best matching topic. */
    static String homeFor(String currentId, String text) {
        if (currentId == null || text == null || text.isBlank()) return currentId;
        if (fits(currentId, text)) return currentId;
        String dest = bestHome(text);
        if (dest != null && inventorFamily(currentId) && inventorFamily(dest)) return currentId;
        if (dest == null && LOOKS_LIKE_PROTOTYPE.matcher(text).find() && PROTOTYPE_TO_SUMMARY.contains(currentId))
            dest = "summary_general";
        return dest != null ? dest : currentId;
    }

    private static boolean inventorFamily(String id) {
        return "potential_inventors".equals(id) || "inventor_contributions".equals(id)
                || "concept_participants".equals(id);
    }

    /** True when the note is actually about this heading. Headings with no topic hint are left alone. */
    static boolean fits(String fieldId, String text) {
        if ("summary_general".equals(fieldId)) {
            if (LOOKS_LIKE_SUMMARY.matcher(text).find() || LOOKS_LIKE_PROTOTYPE.matcher(text).find()) return true;
            return specificHome(text) == null;
        }
        if (DISCLOSURE_FIELDS.contains(fieldId)) return LOOKS_LIKE_DISCLOSURE.matcher(text).find();
        for (TopicHint t : TOPICS) if (t.fieldId().equals(fieldId)) return t.matches(text);
        return true;
    }

    static String bestHome(String text) {
        String dest = specificHome(text);
        if (dest != null) return dest;
        if (LOOKS_LIKE_SUMMARY.matcher(text).find()) return "summary_general";
        return null;
    }

    private static String specificHome(String text) {
        if (LOOKS_LIKE_DISCLOSURE.matcher(text).find() && PAST_SHARING.matcher(text).find() && !FUTURE_SHARING.matcher(text).find())
            return "previous_public_disclosures";
        if (LOOKS_LIKE_DISCLOSURE.matcher(text).find()) return "public_disclosures_list";
        for (TopicHint t : TOPICS) if (t.matches(text)) return t.fieldId();
        return null;
    }

    /** When the researcher names a title, put it under title even if the model left that heading empty. */
    static boolean applyNamedTitle(Map<String, FieldAnswer> out, Map<String, ChecklistField> byId, String said) {
        if (!byId.containsKey("title")) return false;
        String named = namedTitle(said);
        if (named == null) return false;
        FieldAnswer old = out.getOrDefault("title", FieldAnswer.EMPTY);
        if (named.equalsIgnoreCase(old.text())) return false;
        out.put("title", new FieldAnswer(named, ResearcherMark.NONE));
        return true;
    }

    static String namedTitle(String source) {
        if (source == null) return null;
        Matcher m = NAMED_TITLE.matcher(source);
        String found = null;
        while (m.find()) {
            String raw = m.group(1) != null ? m.group(1) : m.group(2);
            if (raw == null) continue;
            String t = raw.replaceAll("\\s+", " ").replaceAll("[.\"']+$", "").strip();
            if (t.length() >= 3 && t.length() <= 120) found = t;
        }
        if (found == null) return null;
        return Character.toUpperCase(found.charAt(0)) + found.substring(1);
    }

    /** True when at least half of the note's meaningful words (compared by their first four letters) come from the researcher. */
    static boolean supported(String note, String source) {
        Set<String> sourceStems = new HashSet<>();
        Matcher s = CONTENT_WORD.matcher(source.toLowerCase(Locale.ROOT));
        while (s.find()) sourceStems.add(s.group().substring(0, 4));
        int words = 0, found = 0;
        Matcher m = CONTENT_WORD.matcher(note.toLowerCase(Locale.ROOT));
        while (m.find()) {
            if (STOPWORDS.contains(m.group())) continue;
            words++;
            if (sourceStems.contains(m.group().substring(0, 4))) found++;
        }
        return words == 0 || found >= MIN_SUPPORTED_WORDS * words;
    }

    /** Sections in batches of {@link #SECTIONS_PER_CALL}; a small model fills a short list of headings far more completely. */
    private List<List<ChecklistService.Section>> groups(Workflow w) {
        List<ChecklistService.Section> all = checklist.sections(w);
        List<List<ChecklistService.Section>> out = new ArrayList<>();
        for (int i = 0; i < all.size(); i += SECTIONS_PER_CALL) out.add(all.subList(i, Math.min(all.size(), i + SECTIONS_PER_CALL)));
        return out;
    }

    private String prompt(Draft d, List<ChecklistService.Section> group, List<ChecklistField> fields, List<Turn> turns) {
        List<ChecklistService.Section> all = checklist.sections(d.workflow());
        boolean last = all.get(all.size() - 1) == group.get(group.size() - 1);
        List<ChecklistField> mine = new ArrayList<>();
        StringBuilder sb = new StringBuilder("<headings>\n");
        for (ChecklistService.Section s : group) {
            sb.append("## ").append(s.section().title()).append('\n');
            for (ChecklistField f : s.fields()) {
                sb.append(f.id()).append(" | ").append(f.label()).append(" | ").append(f.description()).append('\n');
                mine.add(f);
            }
        }
        if (last) {
            Set<String> placed = new HashSet<>();
            all.forEach(s -> s.fields().forEach(f -> placed.add(f.id())));
            List<ChecklistField> other = fields.stream().filter(f -> !placed.contains(f.id())).toList();
            if (!other.isEmpty()) {
                sb.append("## Other\n");
                other.forEach(f -> sb.append(f.id()).append(" | ").append(f.label()).append(" | ").append(f.description()).append('\n'));
                mine.addAll(other);
            }
        }
        sb.append("</headings>\n<other_sections>");
        sb.append(String.join(", ", all.stream().filter(s -> !group.contains(s)).map(s -> s.section().title()).toList()));
        sb.append("</other_sections>\n<headings_with_notes>");
        sb.append(String.join(", ", mine.stream().filter(f -> d.answer(f.id()).hasText()).map(ChecklistField::id).toList()));
        sb.append("</headings_with_notes>\n<conversation>\n");
        for (Turn t : turns.subList(Math.max(0, turns.size() - MAX_TURNS), turns.size())) {
            if (!t.role().equals("USER")) continue;
            sb.append("RESEARCHER: ").append(t.content().replaceAll("\\s+", " ").strip()).append('\n');
        }
        return sb.append("</conversation>").toString();
    }

    private static boolean isProblemField(String id) {
        return PROBLEM_FIELDS.contains(id);
    }

    /** True when the note uses content words that appear only in advisor questions, not in the researcher's words. */
    static boolean leakedFromCoach(String note, String researcher, List<Turn> turns) {
        if (note == null || researcher == null || turns == null) return false;
        Set<String> user = stems(researcher);
        Set<String> coach = new HashSet<>();
        for (Turn t : turns) {
            if (t.role().equals("ASSISTANT")) coach.addAll(stems(t.content()));
        }
        coach.removeAll(user);
        if (coach.isEmpty()) return false;
        Matcher m = CONTENT_WORD.matcher(note.toLowerCase(Locale.ROOT));
        while (m.find()) {
            if (STOPWORDS.contains(m.group())) continue;
            String stem = m.group().substring(0, Math.min(4, m.group().length()));
            if (coach.contains(stem) && !user.contains(stem)) return true;
        }
        return false;
    }

    static String dedupeSentences(String text) {
        if (text == null || text.isBlank()) return text == null ? "" : text;
        List<String> kept = new ArrayList<>();
        for (String part : text.split("(?<=[.!?])\\s+")) {
            String s = part.replaceAll("\\s+", " ").strip();
            if (s.isEmpty()) continue;
            boolean dup = false;
            for (String k : kept) {
                if (TextOverlap.coverage(s, k) >= 0.72 && TextOverlap.coverage(k, s) >= 0.45) {
                    dup = true;
                    break;
                }
            }
            if (!dup) kept.add(s);
        }
        return String.join(" ", kept);
    }

    private static Set<String> stems(String text) {
        Set<String> out = new HashSet<>();
        if (text == null) return out;
        Matcher m = CONTENT_WORD.matcher(text.toLowerCase(Locale.ROOT));
        while (m.find()) {
            if (STOPWORDS.contains(m.group())) continue;
            out.add(m.group().substring(0, Math.min(4, m.group().length())));
        }
        return out;
    }

    static boolean isPlaceholderNote(String text) {
        if (text == null || text.isBlank()) return true;
        String stripped = text.strip();
        if (NO_INFO.matcher(stripped).matches()) return true;
        if (!PLACEHOLDER_PHRASE.matcher(stripped).find()) return false;
        String leftover = PLACEHOLDER_PHRASE.matcher(stripped).replaceAll(" ").replaceAll("\\s+", " ").strip();
        return leftover.length() < 40;
    }
}
