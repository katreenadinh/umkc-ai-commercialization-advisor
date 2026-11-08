import { useEffect, useState } from "react";
import { getContact } from "../api";
import { FALLBACK_CONTACT } from "../data/contact";
import type { ContactInfo } from "../types";

/** Loads the office contact once; falls back to FALLBACK_CONTACT if the API call fails. */
export function useContact(): ContactInfo | null {
  const [contact, setContact] = useState<ContactInfo | null>(null);

  useEffect(() => {
    getContact()
      .then(setContact)
      .catch(() => setContact(FALLBACK_CONTACT));
  }, []);

  return contact;
}
