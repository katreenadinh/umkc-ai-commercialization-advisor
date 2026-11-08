const DRAFT_KEY = "advisor-draft-id";

export function savedDraftId(): string | null {
  return sessionStorage.getItem(DRAFT_KEY);
}

export function rememberDraftId(id: string) {
  sessionStorage.setItem(DRAFT_KEY, id);
}

export function forgetDraftId() {
  sessionStorage.removeItem(DRAFT_KEY);
}
