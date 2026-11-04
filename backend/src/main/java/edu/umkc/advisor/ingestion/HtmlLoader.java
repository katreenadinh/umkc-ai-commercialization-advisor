package edu.umkc.advisor.ingestion;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Extracts main content from HTML, tracking headings (and bold lead-ins in policy lists) as section labels. */
public final class HtmlLoader {
    private static final String[] ROOTS = {"main", "#content", "article", ".main-content", "body"};

    public ExtractedDocument fetch(String url, String titleOverride) throws IOException {
        Document doc = Jsoup.connect(url).userAgent("UMKC-Advisor-Ingest/0.1").timeout(20_000).get();
        return parse(doc, titleOverride);
    }

    public ExtractedDocument parseHtml(String html, String titleOverride) {
        return parse(Jsoup.parse(html), titleOverride);
    }

    private ExtractedDocument parse(Document doc, String titleOverride) {
        String title = titleOverride != null && !titleOverride.isBlank() ? titleOverride : doc.title();
        doc.select("nav, header, footer, script, style, aside, form, noscript, .skip-link, #breadcrumb").remove();
        Element root = null;
        for (String sel : ROOTS) {
            Element e = doc.selectFirst(sel);
            if (e != null && e.text().length() > 200) { root = e; break; }
        }
        if (root == null) root = doc.body();

        List<Segment> segs = new ArrayList<>();
        String[] section = {title};
        StringBuilder buf = new StringBuilder();
        Runnable flush = () -> {
            if (buf.toString().isBlank()) return;
            segs.add(new Segment(section[0], null, buf.toString()));
            buf.setLength(0);
        };
        for (Element el : root.select("h1, h2, h3, h4, p, li, tr")) {
            String tag = el.tagName();
            if (tag.startsWith("h")) { flush.run(); section[0] = el.text().trim(); continue; }
            if (tag.equals("p") && el.parent() != null && el.parent().tagName().equals("li")) continue;
            String text;
            if (tag.equals("li")) {
                Element c = el.clone();
                c.select("ol, ul").remove();
                text = c.text();
            } else text = el.text();
            text = text.trim();
            if (text.isEmpty()) continue;
            Element lead = el.selectFirst("> strong, > b");
            if (lead != null && lead.text().length() < 80 && lead.text().length() > 2) {
                flush.run();
                section[0] = lead.text().replaceAll("[\\s.:\\u2013\\-]+$", "");
            }
            buf.append(text).append("\n\n");
        }
        flush.run();
        return new ExtractedDocument(title, segs);
    }
}
