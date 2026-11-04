package edu.umkc.advisor.ingestion;

import org.apache.poi.xwpf.usermodel.*;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Reads paragraphs AND tables. Word heading styles and lines such as
 * "A.1. Invention Description" become section labels.
 */
public final class DocxLoader {
    private static final Pattern FORM_HEADING = Pattern.compile("^[A-E]\\.(?:\\d+\\.?)*\\s+\\S.{1,80}$");

    public ExtractedDocument load(InputStream in, String title) throws IOException {
        try (XWPFDocument doc = new XWPFDocument(in)) {
            State st = new State(title);
            walk(doc.getBodyElements(), st);
            st.flush();
            return new ExtractedDocument(title, st.segments);
        }
    }

    private static final class State {
        final List<Segment> segments = new ArrayList<>();
        String section;
        final StringBuilder buf = new StringBuilder();
        State(String title) { section = title; }
        void flush() {
            if (!buf.toString().isBlank()) segments.add(new Segment(section, null, buf.toString()));
            buf.setLength(0);
        }
    }

    private void walk(List<IBodyElement> elements, State st) {
        for (IBodyElement e : elements) {
            if (e instanceof XWPFParagraph p) {
                String text = p.getText() == null ? "" : p.getText().strip();
                if (text.isEmpty()) continue;
                String style = p.getStyle();
                boolean heading = (style != null && style.toLowerCase().startsWith("heading"))
                        || (text.length() < 90 && FORM_HEADING.matcher(text).matches());
                if (heading) { st.flush(); st.section = text; }
                else st.buf.append(text).append("\n\n");
            } else if (e instanceof XWPFTable t) {
                for (XWPFTableRow row : t.getRows()) {
                    List<String> cells = new ArrayList<>();
                    for (XWPFTableCell c : row.getTableCells()) {
                        String ct = c.getText() == null ? "" : c.getText().replaceAll("\\s+", " ").strip();
                        if (!ct.isEmpty()) cells.add(ct);
                    }
                    if (!cells.isEmpty()) st.buf.append(String.join(" | ", cells)).append("\n\n");
                }
            }
        }
    }
}
