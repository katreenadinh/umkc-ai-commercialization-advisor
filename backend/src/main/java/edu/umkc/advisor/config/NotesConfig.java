package edu.umkc.advisor.config;

import edu.umkc.advisor.ai.LlmClient;
import edu.umkc.advisor.repository.DraftRepository;
import edu.umkc.advisor.service.ChecklistService;
import edu.umkc.advisor.service.NotesOrganizer;
import edu.umkc.advisor.service.NotesSummaryWriter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class NotesConfig {
    private static final int SUMMARY_MAX_TOKENS = 900;
    private static final int ORGANIZE_MAX_TOKENS = 400;

    @Bean NotesSummaryWriter notesSummaryWriter(LlmClient llm) {
        return new NotesSummaryWriter(llm, AdvisorConfig.loadPrompt("prompts/notes-summary.txt"), SUMMARY_MAX_TOKENS);
    }

    @Bean NotesOrganizer notesOrganizer(ChecklistService checklist, DraftRepository repo, LlmClient llm) {
        return new NotesOrganizer(checklist, repo, llm, AdvisorConfig.loadPrompt("prompts/notes-organize.txt"), ORGANIZE_MAX_TOKENS);
    }
}
