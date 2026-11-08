import type { PassageView, SourceView } from "../types";
import { read } from "./client";

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
