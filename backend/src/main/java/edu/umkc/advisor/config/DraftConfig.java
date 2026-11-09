package edu.umkc.advisor.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.umkc.advisor.ai.LlmClient;
import edu.umkc.advisor.ai.ResponseValidator;
import edu.umkc.advisor.repository.DraftRepository;
import edu.umkc.advisor.repository.JdbcDraftRepository;
import edu.umkc.advisor.safety.ReferralService;
import edu.umkc.advisor.service.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

@Configuration
public class DraftConfig {

    @Bean ChecklistService checklistService() { return ChecklistService.fromClasspath(); }

    @Bean DraftRepository draftRepository(JdbcTemplate jdbc) { return new JdbcDraftRepository(jdbc); }

    @Bean DisclosureInterviewService interviewService(ChecklistService c, ReferralService r, LlmClient llm, ResponseValidator v,
                                                      DraftRepository repo, AdvisorProperties p) {
        // Cap chat replies so a local model cannot spend minutes filling a 1200-token budget.
        return new DisclosureInterviewService(c, r, llm, v, repo, AdvisorConfig.loadPrompt("prompts/interview.txt"),
                Math.min(700, p.llm().maxTokens()));
    }

    @Bean DisclosureReviewService reviewService(ChecklistService c, ReferralService r, LlmClient llm, ResponseValidator v, AdvisorProperties p) {
        return new DisclosureReviewService(c, r, llm, v, AdvisorConfig.loadPrompt("prompts/review.txt"), p.llm().maxTokens());
    }

    @Bean DraftSummaryBuilder summaryBuilder(ChecklistService c, ReferralService r) { return new DraftSummaryBuilder(c, r); }

    @Bean DraftService draftService(DraftRepository repo, ChecklistService c, DisclosureReviewService rev, DraftSummaryBuilder sb,
                                    ReferralService r, AdvisorProperties p) {
        return new DraftService(repo, c, rev, sb, r, officialSources(), p.limits().maxDraftsPerOwner());
    }

    /** Titles and URLs of the verified official sources, read from sources.json (never from model output). */
    private static List<DraftSummaryBuilder.SourceRef> officialSources() {
        List<DraftSummaryBuilder.SourceRef> out = new ArrayList<>();
        try (InputStream in = DraftConfig.class.getClassLoader().getResourceAsStream("sources.json")) {
            for (JsonNode s : new ObjectMapper().readTree(in)) out.add(new DraftSummaryBuilder.SourceRef(s.get("title").asText(), s.path("url").asText(null)));
        } catch (IOException e) { throw new IllegalStateException("Cannot read sources.json", e); }
        return out;
    }
}
