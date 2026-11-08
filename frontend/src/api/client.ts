const TOKEN_KEY = "advisor-owner-token";

export function ownerToken(): string {
  let token = localStorage.getItem(TOKEN_KEY);
  if (!token || !/^[0-9a-fA-F-]{36}$/.test(token)) {
    token = crypto.randomUUID();
    localStorage.setItem(TOKEN_KEY, token);
  }
  return token;
}

export function headers(json = true): HeadersInit {
  const h: Record<string, string> = { "X-Owner-Token": ownerToken() };
  if (json) h["Content-Type"] = "application/json";
  return h;
}

export async function read<T>(res: Response): Promise<T> {
  const data = (await res.json().catch(() => ({}))) as { error?: string } & T;
  if (!res.ok) throw new Error(data.error || `Request failed (${res.status})`);
  return data;
}
