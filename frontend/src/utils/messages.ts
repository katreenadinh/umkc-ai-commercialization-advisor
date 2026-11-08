import type { DraftView, Msg } from "../types";

export function paragraphsFrom(text: string): string[] {
  return text
    .replace(/\s*\[S\d+]/g, "")
    .split(/\n\n+/)
    .map((part) => part.trim())
    .filter(Boolean);
}

export function fromTranscript(draft: DraftView): Msg[] {
  return draft.transcript.map((turn, index) => {
    if (turn.role === "USER") {
      return { id: `${draft.id}-u-${index}`, role: "user" as const, text: turn.content };
    }
    return {
      id: `${draft.id}-a-${index}`,
      role: "advisor" as const,
      paragraphs: paragraphsFrom(turn.content),
    };
  });
}
