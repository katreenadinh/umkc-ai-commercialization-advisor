export type Page = "coach" | "scenarios" | "sources" | "deploy";

export interface Msg {
  id: string;
  role: "user" | "advisor";
  text?: string;
  paragraphs?: string[];
}

export type FieldDepth = "empty" | "mentioned" | "usable";
