export const PROCESS_CHIPS = [
  "What is a public disclosure?",
  "How does licensing usually work?",
  "What is prior art?",
  "When should I contact the office?",
  "Who usually owns university inventions?",
  "What belongs in an invention disclosure?",
];

export const FIELD_CHIPS: Record<string, string[]> = {
  summary_general: ["It's still an early-stage idea.", "I can give a one-sentence overview."],
  problem_solved: ["It addresses a bottleneck we see in the lab.", "I'm still figuring out the exact problem."],
  technical_description: ["I can walk through how it works at a high level.", "The mechanism is still a bit fuzzy."],
  difference_from_others: ["I'm not sure yet how it compares.", "It does something existing tools don't."],
  public_disclosures_list: ["Nothing has been shared publicly yet.", "I want to present this finding."],
  evidence_testing: ["We haven't tested it yet.", "We have some early lab results."],
  potential_inventors: ["It was just me so far.", "A student helped with the experiments."],
  funding: ["There was no outside funding.", "It was supported by a grant."],
  existing_disclosure_ref: ["I don't have the disclosure number handy.", "I can describe which one it is."],
  what_changed: ["The main change is a new method.", "I'm not sure what counts as a change."],
};
