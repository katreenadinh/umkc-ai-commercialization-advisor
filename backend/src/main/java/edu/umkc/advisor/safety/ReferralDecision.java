package edu.umkc.advisor.safety;

import java.util.EnumSet;
import java.util.Set;

/**
 * Result of the mandatory, deterministic safety rules. Computed in Java; the LLM cannot override it.
 *
 * @param triggers      every rule that fired
 * @param primary       highest-priority trigger (NONE if no rule fired)
 * @param blockLlm      true when the request must be answered with {@code blockingMessage} and no LLM answer
 * @param blockingMessage non-null only when blockLlm
 * @param referralNote  text Java appends to any answer when a referral is required
 * @param topics        individual-determination topics detected
 */
public record ReferralDecision(
        Set<Trigger> triggers,
        Trigger primary,
        boolean blockLlm,
        String blockingMessage,
        String referralNote,
        Set<Topic> topics) {

    public enum Trigger { NONE, IMMINENT_DISCLOSURE, INJECTION_ATTEMPT, INDIVIDUAL_DETERMINATION, PAST_DISCLOSURE }

    public enum Topic {
        PATENTABILITY("whether your invention is patentable"),
        OWNERSHIP("who owns an invention"),
        INVENTORSHIP("who qualifies as an inventor"),
        PROTECTION_GUARANTEE("whether a disclosure guarantees any protection"),
        UNIVERSITY_DECISION("whether the university will pursue an invention"),
        NOVELTY_OR_VALUE("whether an invention is novel or commercially valuable");

        public final String label;
        Topic(String label) { this.label = label; }
    }

    public boolean referralRequired() { return primary != Trigger.NONE; }

    public static ReferralDecision none() {
        return new ReferralDecision(EnumSet.noneOf(Trigger.class), Trigger.NONE, false, null, null,
                EnumSet.noneOf(Topic.class));
    }
}
