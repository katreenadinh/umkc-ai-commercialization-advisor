package edu.umkc.advisor.controller;

import edu.umkc.advisor.dto.AskRequest;
import edu.umkc.advisor.dto.AskResponse;
import edu.umkc.advisor.service.AdvisorService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/advisor")
public class AdvisorController {
    private final AdvisorService advisor;

    public AdvisorController(AdvisorService advisor) { this.advisor = advisor; }

    /** Request text is never logged. */
    @PostMapping("/ask")
    public AskResponse ask(@Valid @RequestBody AskRequest request) {
        return advisor.ask(request.question().trim());
    }
}
