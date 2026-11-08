import type { ContactInfo } from "../types";

/** Shown when GET /api/contact fails, so the office is always reachable from the UI. */
export const FALLBACK_CONTACT: ContactInfo = {
  officeName: "UMKC Office of Technology Commercialization",
  email: "ericwa@umkc.edu",
  url: "https://ori.umkc.edu/facilities-compliance-and-commercialization/commercialization/process/submit-an-invention-disclosure.php",
};
