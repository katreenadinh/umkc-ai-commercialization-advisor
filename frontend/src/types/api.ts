export type ResearcherMark = "NONE" | "NOT_YET_KNOWN" | "NOT_APPLICABLE" | "SKIPPED";

export interface Progress {
  addressed: number;
  applicable: number;
  notApplicable: number;
  partial: number;
  percent: number;
}

export interface Referral {
  required: boolean;
  trigger: string;
  note: string | null;
}

export interface FieldView {
  id: string;
  section: string;
  label: string;
  description: string;
  whyItHelps: string;
  officialFormField: boolean;
  origin: string;
  sourceRef: string;
  humanReviewNote: string | null;
  notApplicableAllowed: boolean;
  priority: number;
  text: string;
  mark: ResearcherMark;
}

export interface Turn {
  role: "USER" | "ASSISTANT";
  content: string;
  fieldIds: string[];
}

export interface DraftView {
  id: string;
  title: string;
  workflow: string;
  fields: FieldView[];
  transcript: Turn[];
  progress: Progress;
  summaryMd: string | null;
  updatedAt: string;
}

export interface Citation {
  label: string;
  chunkId: string;
  title: string;
  url: string | null;
  section: string | null;
  page: number | null;
  excerpt: string | null;
}

export interface Reply {
  assistantMessage: string;
  updatedFieldIds: string[];
  askedFieldIds: string[];
  referral: Referral;
  urgent: boolean;
  progress: Progress;
  complete: boolean;
  citations?: Citation[];
}

export interface SourceView {
  id: string;
  title: string;
  url: string | null;
  type: string;
  enabled: boolean;
  fileName?: string | null;
}

export interface PassageView {
  chunkId: string;
  title: string;
  url: string;
  section: string;
  page: number;
  excerpt: string;
  score: number;
}

export interface ContactInfo {
  officeName: string;
  email: string;
  url: string | null;
}
