package edu.umkc.advisor.ingestion;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class HtmlLoaderTest {
    // SYNTHETIC page shaped like the UMKC/UM System pages; not official content.
    private static final String HTML = """
        <html><head><title>Broken Title {</title></head><body>
        <nav><a href='#'>Skip to Content</a><ul><li>Navigation item</li></ul></nav>
        <main>
          <h1>Page heading</h1>
          <p>%s</p>
          <h2>Timing</h2>
          <p>Synthetic paragraph about timing of disclosure that is long enough for the test.</p>
          <ol><li><strong>Invention Disclosures.</strong> Every invention shall be reported. <ol><li>Nested item text.</li></ol></li></ol>
        </main>
        <footer>Footer boilerplate should vanish</footer></body></html>
        """.formatted("Intro paragraph. ".repeat(20));

    @Test
    void extractsSectionsDropsChromeAndOverridesTitle() {
        ExtractedDocument d = new HtmlLoader().parseHtml(HTML, "Good Title");
        assertEquals("Good Title", d.title());
        String all = d.segments().stream().map(Segment::text).reduce("", (a, b) -> a + b);
        assertFalse(all.contains("Navigation item"));
        assertFalse(all.contains("Footer boilerplate"));
        assertTrue(d.segments().stream().anyMatch(s -> "Timing".equals(s.section())));
        assertTrue(d.segments().stream().anyMatch(s -> "Invention Disclosures".equals(s.section())));
        assertEquals(1, all.split("Nested item text").length - 1);
    }

    @Test
    void fallsBackToDocumentTitleWhenNoOverride() {
        assertEquals("Broken Title {", new HtmlLoader().parseHtml(HTML, null).title());
    }
}
