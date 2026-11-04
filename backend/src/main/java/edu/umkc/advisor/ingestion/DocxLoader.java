package edu.umkc.advisor.ingestion;

import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/** Uses Word heading styles as section labels. */
public final class DocxLoader {
    public ExtractedDocument load(InputStream in, String title) throws IOException {
        try (XWPFDocument doc = new XWPFDocument(in)) {
            List<Segment> segs = new ArrayList<>();
            String section = title;
            StringBuilder buf = new StringBuilder();
            for (XWPFParagraph p : doc.getParagraphs()) {
                String text = p.getText() == null ? "" : p.getText().trim();
                if (text.isEmpty()) continue;
                String style = p.getStyle();
                if (style != null && style.toLowerCase().startsWith("heading")) {
                    if (!buf.toString().isBlank()) segs.add(new Segment(section, null, buf.toString()));
                    buf.setLength(0);
                    section = text;
                } else buf.append(text).append("\n\n");
            }
            if (!buf.toString().isBlank()) segs.add(new Segment(section, null, buf.toString()));
            return new ExtractedDocument(title, segs);
        }
    }
}
