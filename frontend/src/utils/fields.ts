import type { FieldDepth, FieldView } from "../types";

export function fieldDepth(field: FieldView): FieldDepth {
  if (field.mark === "NOT_APPLICABLE") return "usable";
  if (field.mark === "NOT_YET_KNOWN" || field.mark === "SKIPPED") return "mentioned";
  const text = field.text?.trim() || "";
  if (!text) return "empty";
  return text.length > 40 ? "usable" : "mentioned";
}

export function fieldStatus(field: FieldView): string {
  if (field.mark === "NOT_APPLICABLE") return "Not applicable";
  if (field.mark === "NOT_YET_KNOWN") return "Not yet known";
  if (field.mark === "SKIPPED") return "Skipped";
  const depth = fieldDepth(field);
  if (depth === "usable") return "Enough to discuss";
  if (depth === "mentioned") return "Mentioned";
  return "Not yet";
}
