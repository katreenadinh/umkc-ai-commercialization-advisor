import { FIELD_CHIPS, PROCESS_CHIPS } from "../data/chips";
import type { FieldView } from "../types";

export function hashSeed(value: string): number {
  let hash = 0;
  for (let i = 0; i < value.length; i += 1) hash = (hash * 31 + value.charCodeAt(i)) | 0;
  return Math.abs(hash);
}

export function pickRotated(pool: string[], seed: string, count: number, skip: string[] = []): string[] {
  const filtered = pool.filter((item) => !skip.some((s) => s.toLowerCase() === item.toLowerCase()));
  if (filtered.length === 0) return [];
  const start = hashSeed(seed) % filtered.length;
  const out: string[] = [];
  for (let i = 0; i < Math.min(count, filtered.length); i += 1) {
    out.push(filtered[(start + i * 2) % filtered.length]);
  }
  return [...new Set(out)];
}

export function suggestionChips(
  currentField: FieldView | undefined,
  lastUser: string,
  lastAdvisor: string,
  turn: number,
): string[] {
  const processReply = /^(what|how|who|when|why|where|which|can|could|should|is|are|do|does)\b/i.test(lastUser.trim());
  if (processReply) {
    const chips = pickRotated(PROCESS_CHIPS, `${lastUser}|${lastAdvisor}|${turn}`, 3, [lastUser]);
    chips.push("Let's continue with the notes.");
    if (currentField) chips.unshift("Skip this for now.");
    return chips.slice(0, 4);
  }
  const chips: string[] = [];
  if (currentField) chips.push("Skip this for now.");
  const fieldPool = FIELD_CHIPS[currentField?.id || ""] ?? ["I don't know yet", "That's not applicable"];
  chips.push(...pickRotated(fieldPool, `${lastUser}|${currentField?.id || ""}|${turn}`, 2));
  chips.push(...pickRotated(PROCESS_CHIPS, lastAdvisor || lastUser || String(turn), 1, [lastUser, ...chips]));
  if (!chips.includes("I don't know yet")) chips.push("I don't know yet");
  return [...new Set(chips)].slice(0, 5);
}
