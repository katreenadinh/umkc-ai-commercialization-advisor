package edu.umkc.advisor.model;

public record FieldAnswer(String text, ResearcherMark mark) {
    public FieldAnswer {
        text = text == null ? "" : text.strip();
        mark = mark == null ? ResearcherMark.NONE : mark;
    }
    public static final FieldAnswer EMPTY = new FieldAnswer("", ResearcherMark.NONE);
    public boolean hasText() { return !text.isBlank(); }
    /** The researcher has said something about this field, even if only "I don't know" or "skip". */
    public boolean isAddressed() { return hasText() || mark != ResearcherMark.NONE; }
}
