package edu.umkc.advisor.config;

import edu.umkc.advisor.ai.LlmClient;
import edu.umkc.advisor.ai.ResponseValidator;
import edu.umkc.advisor.ingestion.Chunker;
import edu.umkc.advisor.ingestion.IngestionPipeline;
import edu.umkc.advisor.ingestion.JsonlImporter;
import edu.umkc.advisor.retrieval.*;
import edu.umkc.advisor.service.AnswerGenerator;
import edu.umkc.advisor.service.RagAdvisorService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration
public class RagConfig {

    @Bean EmbeddingService embeddingService() { return new MiniLmEmbeddingService(); }

    @Bean VectorStore vectorStore(JdbcTemplate jdbc) { return new PgVectorStore(jdbc); }

    @Bean DocumentRetrievalService documentRetrievalService(EmbeddingService e, VectorStore v, AdvisorProperties p) {
        return new DocumentRetrievalService(e, v, p.rag().topK(), p.rag().minScore());
    }

    @Bean AnswerGenerator answerGenerator(DocumentRetrievalService r, LlmClient llm, ResponseValidator v, AdvisorProperties p) {
        return new RagAdvisorService(r, llm, v, AdvisorConfig.loadPrompt("prompts/answer.txt"), p.anthropic().maxTokens());
    }

    @Bean IngestionPipeline ingestionPipeline(EmbeddingService e, VectorStore v) {
        return new IngestionPipeline(Chunker.defaults(), e, v);
    }

    @Bean JsonlImporter jsonlImporter(IngestionPipeline p) { return new JsonlImporter(p); }
}
