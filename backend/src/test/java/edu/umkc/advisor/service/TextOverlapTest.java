package edu.umkc.advisor.service;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class TextOverlapTest {

    @Test
    public void exactTokensMatch() {
        double cov = TextOverlap.coverage("sensor detects chemicals", "it uses a sensor that detects chemicals");
        assertEquals(1.0, cov);
    }

    @Test
    public void typoMatchesWithFuzzy() {
        // "resukts" in reference vs "results" in candidate
        double cov = TextOverlap.coverage("sends results to a mobile app", "and it sends the resukts to a mobile app");
        assertTrue(cov >= 0.75, "Expected coverage >= 0.75 but was: " + cov);
    }

    @Test
    public void unrelatedTextFails() {
        double cov = TextOverlap.coverage("novel blockchain cryptocurrency mining", "it detects chemicals in water");
        assertEquals(0.0, cov);
    }

    public static void main(String[] args) {
        TextOverlapTest t = new TextOverlapTest();
        t.exactTokensMatch();
        t.typoMatchesWithFuzzy();
        t.unrelatedTextFails();
        System.out.println("TEXT OVERLAP TESTS PASSED!");
    }
}
