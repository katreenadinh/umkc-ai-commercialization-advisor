package edu.umkc.advisor.config;

import edu.umkc.advisor.ai.ClaudeClient;
import edu.umkc.advisor.ai.LlmClient;
import edu.umkc.advisor.ai.ResponseValidator;
import edu.umkc.advisor.safety.ReferralService;
import edu.umkc.advisor.service.AdvisorService;
import edu.umkc.advisor.service.AnswerGenerator;
import edu.umkc.advisor.service.RelevanceService;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

@Configuration
@EnableConfigurationProperties(AdvisorProperties.class)
public class AdvisorConfig implements WebMvcConfigurer {

    private final AdvisorProperties props;

    public AdvisorConfig(AdvisorProperties props) { this.props = props; }

    @Bean LlmClient llmClient() {
        var l = props.llm();
        if ("hosted".equalsIgnoreCase(l.provider()) || "anthropic".equalsIgnoreCase(l.provider())) {
            var a = l.anthropic();
            return new ClaudeClient(a.apiKey(), a.model(), a.baseUrl(), l.timeoutSeconds());
        }
        return new edu.umkc.advisor.ai.LocalLlmClient(l.local().baseUrl(), l.local().model(), l.timeoutSeconds());
    }

    @Bean ResponseValidator responseValidator() { return new ResponseValidator(); }

    @Bean ReferralService referralService() {
        var r = props.referral();
        return new ReferralService(new ReferralService.Contact(r.officeName(), r.email(), r.url()));
    }

    @Bean RelevanceService relevanceService(LlmClient llm, ResponseValidator v) {
        return new RelevanceService(llm, v, loadPrompt("prompts/relevance.txt"));
    }

    @Bean AdvisorService advisorService(ReferralService r, RelevanceService rel, AnswerGenerator gen) {
        return new AdvisorService(r, rel, gen);
    }

    public static String loadPrompt(String path) {
        try (InputStream in = AdvisorConfig.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) throw new IllegalStateException("Missing prompt resource: " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Could not read prompt resource: " + path, e);
        }
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**").allowedOrigins(props.corsAllowedOrigin())
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*");
    }
}
