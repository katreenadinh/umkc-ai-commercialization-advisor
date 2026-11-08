export interface Scenario {
  id: string;
  label: string;
  audience: string;
  shows: string;
  prompt: string;
}

export const SCENARIOS: Scenario[] = [
  {
    id: "friday",
    label: "Paper on Friday",
    audience: "Faculty, basic science",
    shows: "Finding versus application, and a near-term public disclosure",
    prompt:
      "We found that protein X binds protein Y in breast cancer cell lines, and the interaction tracks with poor outcomes. I think this is an interesting finding. Is it an invention? We are submitting the manuscript on Friday.",
  },
  {
    id: "thin",
    label: "Thin disclosure",
    audience: "Faculty preparing the form",
    shows: "Which disclosure prompts are still missing",
    prompt:
      "Title: Novel protein interaction. We discovered that protein X binds protein Y in breast cancer cell lines and the interaction correlates with poor outcomes. We think it could be important. My graduate student ran the western blots. NIH R01 supported the work. We are submitting to a journal next week. A company collaborator sent us the antibody.",
  },
  {
    id: "student",
    label: "Student and a company",
    audience: "Faculty with collaborators",
    shows: "Ownership patterns without deciding the case",
    prompt:
      "Who owns this? My graduate student is paid on my NSF grant and did the work in my UMKC lab. A scientist at a partner company suggested we try their antibody and sent it to us. There might be an MTA. Does the university own it, or does the student?",
  },
  {
    id: "preprint",
    label: "Preprint eight months ago",
    audience: "Faculty after a public step",
    shows: "Grace period explained, no filing decision",
    prompt:
      "I posted a preprint eight months ago that describes the method in enough detail to reproduce it. Can I still get a patent in the US and in Europe?",
  },
  {
    id: "patent",
    label: "Is it patentable?",
    audience: "Anyone hoping for a yes or no",
    shows: "The boundary: concepts yes, determination no",
    prompt:
      "Be honest. Is this patentable? It is a sensor that detects a metabolite the current strips miss, and our bench data looks strong. I want to know if I should file.",
  },
  {
    id: "path",
    label: "Startup or license",
    audience: "Faculty choosing a path",
    shows: "Stage, market, and role — without picking for them",
    prompt:
      "The device works on the bench and in a simulated clinic workflow, but it is not in real patients. I do not want to leave my faculty job to be a CEO. Should I start a company or try to license this?",
  },
  {
    id: "trl",
    label: "Where is my TRL?",
    audience: "Faculty asked for a readiness number",
    shows: "Evidence over optimism",
    prompt:
      "A program officer asked for our technology readiness level. We have a working lab prototype of a wearable sensor. It has never left the bench. What TRL is that, and what would move it forward?",
  },
  {
    id: "royalty",
    label: "What royalty is fair?",
    audience: "Founder-curious faculty",
    shows: "License levers without inventing a rate",
    prompt:
      "If I form a startup around this, what royalty rate and equity share will UMKC want? Can you give me a fair number to put in front of an investor?",
  },
];
