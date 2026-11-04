package edu.umkc.advisor.model;

import java.util.List;

public record Turn(String role, String content, List<String> fieldIds) {
    public Turn { fieldIds = fieldIds == null ? List.of() : List.copyOf(fieldIds); }
    public static Turn user(String c) { return new Turn("USER", c, List.of()); }
    public static Turn assistant(String c, List<String> fieldIds) { return new Turn("ASSISTANT", c, fieldIds); }
}
