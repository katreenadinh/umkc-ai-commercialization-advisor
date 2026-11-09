const TOKEN_KEY = "advisor-owner-token";
const DRAFT_KEY = "advisor-draft-id";

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

export type InformationStatus = "NOT_DISCUSSED" | "PARTIAL" | "COMPLETE" | "UNKNOWN";

export interface SectionView {
  id: string;
  title: string;
  status: InformationStatus;
  statusLabel: string;
  fieldIds: string[];
}

export interface DraftView {
  id: string;
  title: string;
  workflow: string;
  fields: FieldView[];
  sections: SectionView[];
  organizing: boolean;
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

function ownerToken(): string {
  let token = localStorage.getItem(TOKEN_KEY);
  if (!token || !/^[0-9a-fA-F-]{36}$/.test(token)) {
    token = crypto.randomUUID();
    localStorage.setItem(TOKEN_KEY, token);
  }
  return token;
}

function headers(json = true): HeadersInit {
  const h: Record<string, string> = { "X-Owner-Token": ownerToken() };
  if (json) h["Content-Type"] = "application/json";
  return h;
}

async function read<T>(res: Response): Promise<T> {
  const data = (await res.json().catch(() => ({}))) as { error?: string } & T;
  if (!res.ok) throw new Error(data.error || `Request failed (${res.status})`);
  return data;
}

export function savedDraftId(): string | null {
  return sessionStorage.getItem(DRAFT_KEY);
}

export function rememberDraftId(id: string) {
  sessionStorage.setItem(DRAFT_KEY, id);
}

export function forgetDraftId() {
  sessionStorage.removeItem(DRAFT_KEY);
}

export async function createDraft(workflow = "NEW_INVENTION"): Promise<{ draft: DraftView; opening: Reply }> {
  const data = await read<{ draft: DraftView; opening: Reply }>(
    await fetch("/api/drafts", {
      method: "POST",
      headers: headers(),
      body: JSON.stringify({ workflow }),
    }),
  );
  rememberDraftId(data.draft.id);
  return data;
}

export async function getDraft(id: string): Promise<DraftView> {
  return read<DraftView>(await fetch(`/api/drafts/${id}`, { headers: headers(false) }));
}

export async function sendInterview(id: string, message: string): Promise<Reply> {
  return read<Reply>(
    await fetch(`/api/drafts/${id}/interview/messages`, {
      method: "POST",
      headers: headers(),
      body: JSON.stringify({ message }),
    }),
  );
}

export async function skipField(id: string, fieldId: string): Promise<Reply> {
  return read<Reply>(
    await fetch(`/api/drafts/${id}/interview/skip/${encodeURIComponent(fieldId)}`, {
      method: "POST",
      headers: headers(false),
    }),
  );
}

export async function generateSummary(id: string): Promise<string> {
  const data = await read<{ markdown: string }>(
    await fetch(`/api/drafts/${id}/summary`, { method: "POST", headers: headers(false) }),
  );
  return data.markdown;
}

/** Builds fresh notes and returns them as a Word document. */
export async function exportWord(id: string): Promise<Blob> {
  await generateSummary(id);
  const res = await fetch(`/api/drafts/${id}/export?format=docx`, { headers: headers(false) });
  if (!res.ok) await read(res);
  return res.blob();
}

export interface ContactInfo {
  officeName: string;
  email: string;
  url: string | null;
}

export async function getContact(): Promise<ContactInfo> {
  return read<ContactInfo>(await fetch("/api/contact"));
}

export async function listSources(): Promise<SourceView[]> {
  return read<SourceView[]>(await fetch("/api/sources"));
}

export async function searchSources(q: string): Promise<PassageView[]> {
  return read<PassageView[]>(await fetch(`/api/sources/search?q=${encodeURIComponent(q)}`));
}

export async function getSourceText(id: string): Promise<string> {
  const data = await read<{ text?: string }>(await fetch(`/api/sources/${id}/text`));
  return data.text ?? "";
}

export function sourceFileUrl(id: string): string {
  return `/api/sources/${id}/file`;
}

export function officialPage(url: string | null | undefined): string | null {
  if (!url) return null;
  try {
    const parsed = new URL(url);
    if (parsed.protocol === "http:" || parsed.protocol === "https:") return url;
  } catch {
    return null;
  }
  return null;
}

export function sourceFileKind(source: SourceView): "pdf" | "docx" | null {
  if (!source.fileName) return null;
  const name = source.fileName.toLowerCase();
  if (source.type === "PDF" || name.endsWith(".pdf")) return "pdf";
  if (source.type === "DOCX" || name.endsWith(".docx")) return "docx";
  return null;
}
