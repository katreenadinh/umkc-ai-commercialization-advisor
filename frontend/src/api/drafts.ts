import type { DraftView, Reply } from "../types";
import { headers, read } from "./client";
import { rememberDraftId } from "./draftStorage";

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

export async function exportSummary(id: string, format: "docx" | "md"): Promise<void> {
  const res = await fetch(`/api/drafts/${id}/export?format=${format}`, { headers: headers(false) });
  if (!res.ok) {
    const data = (await res.json().catch(() => ({}))) as { error?: string };
    throw new Error(data.error || "Could not export the summary.");
  }
  const blob = await res.blob();
  const url = URL.createObjectURL(blob);
  const link = document.createElement("a");
  link.href = url;
  link.download = format === "md" ? "preparation-summary.md" : "preparation-summary.docx";
  link.click();
  URL.revokeObjectURL(url);
}
