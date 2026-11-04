package edu.umkc.advisor.service;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.umkc.advisor.model.ChecklistField;
import edu.umkc.advisor.model.Workflow;

import java.io.IOException;
import java.io.InputStream;
import java.util.*;

/** Loads the configurable checklist. Fields marked PROJECT_GUIDANCE are never presented as official requirements. */
public final class ChecklistService {
    private final List<ChecklistField> fields;
    private final Map<String, ChecklistField> byId = new LinkedHashMap<>();
    private final String formVersion, inspectionStatus;

    public ChecklistService(InputStream json) throws IOException {
        ObjectMapper m = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        JsonNode root = m.readTree(json);
        List<ChecklistField> list = new ArrayList<>();
        for (JsonNode n : root.path("fields")) list.add(m.treeToValue(n, ChecklistField.class));
        list.sort(Comparator.comparingInt((ChecklistField f) -> f.priority() * 100 + f.order()));
        for (ChecklistField f : list) if (byId.put(f.id(), f) != null) throw new IllegalStateException("Duplicate field id " + f.id());
        this.fields = List.copyOf(list);
        this.formVersion = root.path("formVersion").asText("");
        this.inspectionStatus = root.path("inspectionStatus").asText("");
    }

    public static ChecklistService fromClasspath() {
        try (InputStream in = ChecklistService.class.getClassLoader().getResourceAsStream("disclosure-checklist.json")) {
            if (in == null) throw new IllegalStateException("disclosure-checklist.json missing");
            return new ChecklistService(in);
        } catch (IOException e) { throw new IllegalStateException("Cannot read checklist", e); }
    }

    /** Applicable fields in interview priority order (Priority 1 first). */
    public List<ChecklistField> applicable(Workflow w) { return fields.stream().filter(f -> f.appliesTo(w)).toList(); }

    /** The short first-conversation set shown in the coach. The full form remains in applicable(). */
    public List<ChecklistField> coach(Workflow w) {
        return applicable(w).stream().filter(ChecklistField::inCoach).toList();
    }
    public Optional<ChecklistField> find(String id) { return Optional.ofNullable(byId.get(id)); }
    public List<ChecklistField> all() { return fields; }
    public String formVersion() { return formVersion; }
    public String inspectionStatus() { return inspectionStatus; }
}
