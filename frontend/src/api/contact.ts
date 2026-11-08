import type { ContactInfo } from "../types";
import { read } from "./client";

export async function getContact(): Promise<ContactInfo> {
  return read<ContactInfo>(await fetch("/api/contact"));
}
