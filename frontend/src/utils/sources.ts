import type { SourceView } from "../types";

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

function sourceTypeRank(type: string): number {
  const kind = (type || "").toUpperCase();
  if (kind === "HTML") return 0;
  if (kind === "DOCX") return 1;
  if (kind === "PDF") return 2;
  return 3;
}

export function sortSources(sources: SourceView[]): SourceView[] {
  return [...sources].sort((a, b) => {
    const byType = sourceTypeRank(a.type) - sourceTypeRank(b.type);
    if (byType !== 0) return byType;
    return a.title.localeCompare(b.title, undefined, { numeric: true, sensitivity: "base" });
  });
}
