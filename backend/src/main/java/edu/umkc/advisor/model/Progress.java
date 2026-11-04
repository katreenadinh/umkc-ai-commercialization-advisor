package edu.umkc.advisor.model;

/**
 * Disclosure PREPARATION progress only. Says nothing about patentability, novelty, quality or commercial potential.
 * Fields the researcher marked not applicable are removed from the denominator, so they never lower the percentage.
 */
public record Progress(int addressed, int applicable, int notApplicable, int partial, int percent) {
    public static Progress of(int addressed, int totalApplicable, int notApplicable, int partial) {
        int denom = totalApplicable - notApplicable;
        int pct = denom <= 0 ? 0 : (int) Math.round(100.0 * addressed / denom);
        return new Progress(addressed, denom, notApplicable, partial, Math.min(100, pct));
    }
}
