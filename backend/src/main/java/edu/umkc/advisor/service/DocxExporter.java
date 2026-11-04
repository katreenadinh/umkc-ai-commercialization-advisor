package edu.umkc.advisor.service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Writes the summary's small Markdown subset (headings, bullets, numbered items, **bold**, _italic_) as a real .docx.
 * A .docx is a zip of XML parts, so this needs no external library. Headings use the built-in Heading1/Heading2 styles.
 */
public final class DocxExporter {
    private static final Pattern INLINE = Pattern.compile("\\*\\*(.+?)\\*\\*|(?<![\\w])_(.+?)_(?![\\w])");
    private static final String W = "xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"";

    public byte[] export(String markdown) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(bos, StandardCharsets.UTF_8)) {
            put(z, "[Content_Types].xml", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
                + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
                + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
                + "<Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/>"
                + "<Override PartName=\"/word/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml\"/>"
                + "</Types>");
            put(z, "_rels/.rels", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"word/document.xml\"/>"
                + "</Relationships>");
            put(z, "word/_rels/document.xml.rels", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/>"
                + "</Relationships>");
            put(z, "word/styles.xml", styles());
            put(z, "word/document.xml", document(markdown));
        }
        return bos.toByteArray();
    }

    private static void put(ZipOutputStream z, String name, String content) throws IOException {
        z.putNextEntry(new ZipEntry(name));
        z.write(content.getBytes(StandardCharsets.UTF_8));
        z.closeEntry();
    }

    private static String styles() {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><w:styles " + W + ">"
            + "<w:docDefaults><w:rPrDefault><w:rPr><w:rFonts w:ascii=\"Calibri\" w:hAnsi=\"Calibri\" w:cs=\"Calibri\"/><w:sz w:val=\"22\"/></w:rPr></w:rPrDefault>"
            + "<w:pPrDefault><w:pPr><w:spacing w:after=\"120\" w:line=\"264\" w:lineRule=\"auto\"/></w:pPr></w:pPrDefault></w:docDefaults>"
            + "<w:style w:type=\"paragraph\" w:default=\"1\" w:styleId=\"Normal\"><w:name w:val=\"Normal\"/></w:style>"
            + "<w:style w:type=\"paragraph\" w:styleId=\"Heading1\"><w:name w:val=\"heading 1\"/><w:basedOn w:val=\"Normal\"/><w:next w:val=\"Normal\"/>"
            + "<w:pPr><w:keepNext/><w:spacing w:before=\"240\" w:after=\"200\"/><w:outlineLvl w:val=\"0\"/></w:pPr><w:rPr><w:b/><w:sz w:val=\"40\"/></w:rPr></w:style>"
            + "<w:style w:type=\"paragraph\" w:styleId=\"Heading2\"><w:name w:val=\"heading 2\"/><w:basedOn w:val=\"Normal\"/><w:next w:val=\"Normal\"/>"
            + "<w:pPr><w:keepNext/><w:spacing w:before=\"280\" w:after=\"120\"/><w:outlineLvl w:val=\"1\"/></w:pPr><w:rPr><w:b/><w:sz w:val=\"28\"/></w:rPr></w:style>"
            + "</w:styles>";
    }

    private String document(String markdown) {
        StringBuilder body = new StringBuilder();
        for (String raw : markdown.split("\n")) {
            String line = raw.stripTrailing();
            if (line.isBlank()) continue;
            if (line.startsWith("# ")) body.append(para("Heading1", 0, run(line.substring(2), false, false)));
            else if (line.startsWith("## ")) body.append(para("Heading2", 0, run(line.substring(3), false, false)));
            else if (line.startsWith("- ")) body.append(para(null, 360, inline("\u2022 " + line.substring(2))));
            else if (line.matches("^\\d+\\.\\s.*")) body.append(para(null, 360, inline(line)));
            else body.append(para(null, 0, inline(line)));
        }
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><w:document " + W + "><w:body>" + body
            + "<w:sectPr><w:pgSz w:w=\"12240\" w:h=\"15840\"/><w:pgMar w:top=\"1440\" w:right=\"1440\" w:bottom=\"1440\" w:left=\"1440\" w:header=\"720\" w:footer=\"720\" w:gutter=\"0\"/></w:sectPr>"
            + "</w:body></w:document>";
    }

    private static String para(String style, int indentLeft, String runs) {
        StringBuilder p = new StringBuilder("<w:p><w:pPr>");
        if (style != null) p.append("<w:pStyle w:val=\"").append(style).append("\"/>");
        if (indentLeft > 0) p.append("<w:ind w:left=\"").append(indentLeft).append("\"/>");
        return p.append("</w:pPr>").append(runs).append("</w:p>").toString();
    }

    private String inline(String text) {
        StringBuilder sb = new StringBuilder();
        Matcher m = INLINE.matcher(text);
        int pos = 0;
        while (m.find()) {
            if (m.start() > pos) sb.append(run(text.substring(pos, m.start()), false, false));
            if (m.group(1) != null) sb.append(run(m.group(1), true, false)); else sb.append(run(m.group(2), false, true));
            pos = m.end();
        }
        if (pos < text.length()) sb.append(run(text.substring(pos), false, false));
        return sb.toString();
    }

    private static String run(String text, boolean bold, boolean italic) {
        String props = (bold ? "<w:b/>" : "") + (italic ? "<w:i/>" : "");
        return "<w:r>" + (props.isEmpty() ? "" : "<w:rPr>" + props + "</w:rPr>") + "<w:t xml:space=\"preserve\">" + escape(text) + "</w:t></w:r>";
    }

    /** XML-escapes and drops characters that are illegal in XML 1.0. */
    static String escape(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            boolean legal = cp == 0x9 || cp == 0xA || cp == 0xD || (cp >= 0x20 && cp <= 0xD7FF) || (cp >= 0xE000 && cp <= 0xFFFD) || (cp >= 0x10000 && cp <= 0x10FFFF);
            if (!legal) continue;
            switch (cp) {
                case '&' -> sb.append("&amp;");
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '"' -> sb.append("&quot;");
                default -> sb.appendCodePoint(cp);
            }
        }
        return sb.toString();
    }
}
