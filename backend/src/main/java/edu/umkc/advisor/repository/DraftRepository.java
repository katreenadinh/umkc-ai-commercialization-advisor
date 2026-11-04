package edu.umkc.advisor.repository;

import edu.umkc.advisor.model.*;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Every lookup requires the owner token. There is no authentication in the prototype, so the token is the only gate. */
public interface DraftRepository {
    Draft create(Draft draft);
    Optional<Draft> find(String id, String ownerToken);
    List<Draft> listByOwner(String ownerToken);
    int countByOwner(String ownerToken);
    void saveAnswers(String id, Map<String, FieldAnswer> answers);
    void saveTitle(String id, String title);
    void saveSummary(String id, String markdown);
    void saveReview(String id, String reviewJson);
    void appendTurn(String id, Turn turn);
    List<Turn> turns(String id);
    void delete(String id);
}
