package edu.umkc.advisor.safety;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class OutputGuardTest {
    private final OutputGuard guard = new OutputGuard();
    private final Set<String> allowed = Set.of("https://example.edu/x", "office@example.edu");

    @ParameterizedTest
    @ValueSource(strings = {
        "Your invention is patentable and you should file now.",
        "This software is definitely novel.",
        "You are the sole inventor of this work.",
        "You own the rights to this software.",
        "A disclosure guarantees protection for your idea.",
        "It is safe to publish your paper tomorrow.",
        "There is no need to contact the office yet.",
        "You should wait until your work is more mature.",
        "The university will definitely pursue your invention."
    })
    void blocksDefinitiveStatements(String text) {
        OutputGuard.Result r = guard.scan(text, allowed);
        assertTrue(r.violated(), text);
        assertEquals(OutputGuard.SAFE_FALLBACK, r.text());
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "An invention disclosure does not guarantee protection or that the university will file a patent.",
        "Under the regulations, the university generally owns inventions made within the scope of an employee's duties.",
        "Patentability is a legal determination that qualified staff assess.",
        "Contact office@example.edu or see https://example.edu/x for details."
    })
    void allowsGeneralAndNegatedStatements(String text) {
        OutputGuard.Result r = guard.scan(text, allowed);
        assertFalse(r.violated(), text + " -> " + r.reasons());
        assertEquals(text, r.text());
    }

    @Test
    void blocksInventedUrlsAndEmails() {
        assertTrue(guard.scan("Email jane.doe@umkc.edu for help.", allowed).reasons().contains("UNVERIFIED_EMAIL"));
        assertTrue(guard.scan("See https://made-up.example.org/page.", allowed).reasons().contains("UNVERIFIED_URL"));
    }

    @Test
    void trailingPunctuationDoesNotBreakAllowlist() {
        assertFalse(guard.scan("Visit https://example.edu/x.", allowed).violated());
    }
}
