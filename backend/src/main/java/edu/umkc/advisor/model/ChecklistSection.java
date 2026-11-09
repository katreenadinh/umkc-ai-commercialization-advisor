package edu.umkc.advisor.model;

import java.util.List;

/** One of the disclosure-note sections shown to researchers. {@code fields} is the display order of its headings. */
public record ChecklistSection(String id, String title, String summaryNote, List<String> fields) {
    public ChecklistSection {
        fields = fields == null ? List.of() : List.copyOf(fields);
    }
}
