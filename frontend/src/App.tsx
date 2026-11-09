import { useEffect, useMemo, useRef, useState, type ReactNode } from "react";
import { renderAsync } from "docx-preview";
import {
  createDraft,
  forgetDraftId,
  exportWord,
  generateSummary,
  getDraft,
  getContact,
  getSourceText,
  listSources,
  officialPage,
  savedDraftId,
  sendInterview,
  skipField,
  sourceFileKind,
  sourceFileUrl,
  type DraftView,
  type FieldView,
  type ContactInfo,
  type SourceView,
} from "./api";
import { notesToHtml, notesToPlain } from "./notesFormat";

type Page = "coach" | "sources";

interface Msg {
  id: string;
  role: "user" | "advisor";
  text?: string;
  paragraphs?: string[];
}

const INLINE_MARKDOWN = /\*\*(.+?)\*\*|\[([^\]]+)\]\(((?:https?:\/\/|mailto:)[^)\s]+)\)/g;

/** Renders the advisor's **bold** and [text](link) markup; only web and email links become clickable. */
function richText(text: string): ReactNode[] {
  const out: ReactNode[] = [];
  let last = 0;
  for (const m of text.matchAll(INLINE_MARKDOWN)) {
    if (m.index > last) out.push(text.slice(last, m.index));
    if (m[1]) out.push(<strong key={m.index}>{m[1]}</strong>);
    else
      out.push(
        <a key={m.index} href={m[3]} {...(m[3].startsWith("mailto:") ? {} : { target: "_blank", rel: "noreferrer" })}>
          {m[2]}
        </a>,
      );
    last = m.index + m[0].length;
  }
  if (last < text.length) out.push(text.slice(last));
  return out;
}

function noteText(field: FieldView): string {
  const text = field.text?.trim() || "";
  if (text) return text;
  if (field.mark === "NOT_APPLICABLE") return "Not applicable";
  if (field.mark === "NOT_YET_KNOWN") return "Not yet known";
  if (field.mark === "SKIPPED") return "Skipped for now";
  return "Not discussed yet";
}

function paragraphsFrom(text: string): string[] {
  return text
    .replace(/\s*\[S\d+]/g, "")
    .split(/\n\n+/)
    .map((part) => part.trim())
    .filter(Boolean);
}

function fromTranscript(draft: DraftView): Msg[] {
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

export function App() {
  const [page, setPage] = useState<Page>("coach");
  const [messages, setMessages] = useState<Msg[]>([]);
  const [draft, setDraft] = useState<DraftView | null>(null);
  const [compose, setCompose] = useState("");
  const [copyState, setCopyState] = useState<"idle" | "preparing" | "copied">("idle");
  const [wordState, setWordState] = useState<"idle" | "preparing">("idle");
  const [pending, setPending] = useState(false);
  const [bootError, setBootError] = useState("");
  const [contact, setContact] = useState<ContactInfo | null>(null);
  const [openSections, setOpenSections] = useState<Record<string, boolean>>({});
  const threadRef = useRef<HTMLDivElement>(null);
  const boxRef = useRef<HTMLTextAreaElement>(null);
  const draftIdRef = useRef<string | null>(null);
  const pendingRef = useRef(false);
  const askedFieldRef = useRef<string[]>([]);
  const notesWatchRef = useRef(0);

  useEffect(() => {
    pendingRef.current = pending;
  }, [pending]);

  useEffect(() => {
    draftIdRef.current = draft?.id ?? null;
  }, [draft]);

  useEffect(() => {
    if (copyState !== "copied") return;
    const clear = () => setCopyState("idle");
    window.addEventListener("pointerdown", clear, true);
    window.addEventListener("keydown", clear, true);
    return () => {
      window.removeEventListener("pointerdown", clear, true);
      window.removeEventListener("keydown", clear, true);
    };
  }, [copyState]);

  useEffect(() => {
    void boot();
    getContact()
      .then(setContact)
      .catch(() => {
        setContact({
          officeName: "UMKC Office of Technology Commercialization",
          email: "ericwa@umkc.edu",
          url: "https://ori.umkc.edu/facilities-compliance-and-commercialization/commercialization/process/submit-an-invention-disclosure.php",
        });
      });
  }, []);

  useEffect(() => {
    threadRef.current?.scrollTo({ top: threadRef.current.scrollHeight, behavior: "smooth" });
  }, [messages, page, pending]);

  const items = draft?.fields ?? [];
  const sections = draft?.sections ?? [];
  const completed = sections.filter((section) => section.status === "COMPLETE").length;
  const lastAdvisorId = [...messages].reverse().find((msg) => msg.role === "advisor")?.id;
  const currentAsk = askedFieldRef.current[0];
  const currentField = items.find((field) => field.id === currentAsk);
  const currentSectionId = sections.find((section) => section.fieldIds.includes(currentAsk))?.id;
  const isOpen = (id: string) => openSections[id] ?? id === currentSectionId;
  const allOpen = sections.length > 0 && sections.every((section) => isOpen(section.id));
  async function boot() {
    setPending(true);
    pendingRef.current = true;
    try {
      const saved = savedDraftId();
      if (saved) {
        try {
          const existing = await getDraft(saved);
          applyDraft(existing, fromTranscript(existing));
          askedFieldRef.current = existing.transcript
            .filter((turn) => turn.role === "ASSISTANT")
            .at(-1)?.fieldIds ?? [];
          return;
        } catch {
          forgetDraftId();
        }
      }
      await startCoach();
    } catch (err) {
      setBootError(err instanceof Error ? err.message : "Could not reach the advisor API.");
    } finally {
      pendingRef.current = false;
      setPending(false);
    }
  }

  function applyDraft(next: DraftView, nextMessages?: Msg[]) {
    setDraft(next);
    draftIdRef.current = next.id;
    if (nextMessages) setMessages(nextMessages);
  }

  async function refreshDraft(id: string) {
    const next = await getDraft(id);
    setDraft(next);
    if (next.organizing) void watchNotes(id);
    return next;
  }

  async function watchNotes(id: string) {
    const watch = ++notesWatchRef.current;
    for (let i = 0; i < 40; i += 1) {
      await new Promise((resolve) => window.setTimeout(resolve, 1500));
      if (watch !== notesWatchRef.current || draftIdRef.current !== id) return;
      try {
        const next = await getDraft(id);
        if (watch !== notesWatchRef.current || draftIdRef.current !== id) return;
        setDraft(next);
        if (!next.organizing) return;
      } catch {
        return;
      }
    }
  }

  async function startCoach() {
    const created = await createDraft();
    askedFieldRef.current = created.opening.askedFieldIds;
    setOpenSections({});
    applyDraft(created.draft, [
      {
        id: `${created.draft.id}-open`,
        role: "advisor",
        paragraphs: paragraphsFrom(created.opening.assistantMessage),
      },
    ]);
    setCompose("");
    setBootError("");
    setPage("coach");
    boxRef.current?.focus();
    return created.draft.id;
  }

  function openCoach() {
    setPage("coach");
    boxRef.current?.focus();
  }

  async function reset() {
    forgetDraftId();
    setPending(true);
    pendingRef.current = true;
    try {
      await startCoach();
    } catch (err) {
      setBootError(err instanceof Error ? err.message : "Could not start a new draft.");
    } finally {
      pendingRef.current = false;
      setPending(false);
    }
  }

  async function ask(text: string) {
    const clean = text.trim();
    const id = draftIdRef.current;
    if (!clean || !id || pendingRef.current) return;
    setCompose("");
    setPage("coach");
    pendingRef.current = true;
    setPending(true);
    const userMsg: Msg = { id: `${Date.now()}-u`, role: "user", text: clean };
    setMessages((curr) => [...curr, userMsg]);
    try {
      const reply = await sendInterview(id, clean);
      askedFieldRef.current = reply.askedFieldIds;
      await refreshDraft(id);
      setMessages((curr) => [
        ...curr,
        {
          id: `${Date.now()}-a`,
          role: "advisor",
          paragraphs: paragraphsFrom(reply.assistantMessage),
        },
      ]);
    } catch (err) {
      setMessages((curr) => [
        ...curr,
        {
          id: `${Date.now()}-err`,
          role: "advisor",
          paragraphs: [err instanceof Error ? err.message : "The assistant is temporarily unavailable."],
        },
      ]);
    } finally {
      pendingRef.current = false;
      setPending(false);
    }
  }

  async function skipCurrent() {
    const id = draftIdRef.current;
    const fieldId = askedFieldRef.current[0];
    if (!id || !fieldId || pendingRef.current) return;
    pendingRef.current = true;
    setPending(true);
    setMessages((curr) => [...curr, { id: `${Date.now()}-u`, role: "user", text: "Skip this for now." }]);
    try {
      const reply = await skipField(id, fieldId);
      askedFieldRef.current = reply.askedFieldIds;
      await refreshDraft(id);
      setMessages((curr) => [
        ...curr,
        { id: `${Date.now()}-a`, role: "advisor", paragraphs: paragraphsFrom(reply.assistantMessage) },
      ]);
    } catch (err) {
      setMessages((curr) => [
        ...curr,
        {
          id: `${Date.now()}-err`,
          role: "advisor",
          paragraphs: [err instanceof Error ? err.message : "Could not skip that note."],
        },
      ]);
    } finally {
      pendingRef.current = false;
      setPending(false);
    }
  }

  async function copyBrief() {
    const id = draftIdRef.current;
    if (!id) return;
    setCopyState("preparing");
    const markdown = generateSummary(id);
    try {
      try {
        // Passing promises keeps the click's permission to write while the notes are still being written.
        await navigator.clipboard.write([
          new ClipboardItem({
            "text/html": markdown.then((md) => new Blob([notesToHtml(md)], { type: "text/html" })),
            "text/plain": markdown.then((md) => new Blob([notesToPlain(md)], { type: "text/plain" })),
          }),
        ]);
      } catch {
        await navigator.clipboard.writeText(notesToPlain(await markdown));
      }
      setCopyState("copied");
    } catch (err) {
      setCopyState("idle");
      throw err;
    }
  }

  async function downloadWord() {
    const id = draftIdRef.current;
    if (!id) return;
    setWordState("preparing");
    try {
      const url = URL.createObjectURL(await exportWord(id));
      const link = document.createElement("a");
      link.href = url;
      link.download = "invention-disclosure-preparation-notes.docx";
      link.click();
      window.setTimeout(() => URL.revokeObjectURL(url), 1000);
    } finally {
      setWordState("idle");
    }
  }

  return (
    <div className="app">
      <header className="top">
        <div className="brand">
          <div>
            <h1>Commercialization Advisor</h1>
          </div>
        </div>
        <nav>
          {(
            [
              ["coach", "Disclosure Advisor"],
              ["sources", "Source Library"],
            ] as [Page, string][]
          ).map(([id, label]) => (
            <button
              key={id}
              className={page === id ? "on" : ""}
              onClick={() => {
                if (id === "coach") {
                  openCoach();
                  return;
                }
                setPage(id);
              }}
            >
              {label}
            </button>
          ))}
        </nav>
      </header>

      {page === "sources" ? (
        <SourcePage />
      ) : (
        <main className="workspace">
          <section className="thread" ref={threadRef} aria-live="polite">
            {bootError && (
              <article className="bubble advisor">
                <p className="who">Advisor</p>
                <p>
                  {bootError}
                  {/could not reach|failed to fetch|network|unavailable/i.test(bootError)
                    ? " Start the Java API on port 8081, then refresh."
                    : ""}
                </p>
              </article>
            )}
            {messages.map((msg) =>
              msg.role === "user" ? (
                <article key={msg.id} className="bubble user">
                  <p>{msg.text}</p>
                </article>
              ) : (
                <article key={msg.id} className="bubble advisor">
                  <p className="who">Advisor</p>
                  {(msg.paragraphs || []).map((paragraph, index) => {
                    const lines = paragraph.split("\n");
                    const items = lines.filter((line) => line.startsWith("- "));
                    if (!items.length) return <p key={`${msg.id}-${index}`}>{richText(paragraph)}</p>;
                    const lead = lines.filter((line) => !line.startsWith("- ")).join(" ");
                    return (
                      <div key={`${msg.id}-${index}`} className="bubble-list">
                        {lead && <p>{richText(lead)}</p>}
                        <ul>
                          {items.map((line, i) => (
                            <li key={i}>{richText(line.slice(2))}</li>
                          ))}
                        </ul>
                      </div>
                    );
                  })}
                  {msg.id === lastAdvisorId && !pending && currentField && (
                    <div className="follow">
                      <button type="button" onClick={() => void skipCurrent()}>
                        Skip this question
                      </button>
                    </div>
                  )}
                </article>
              ),
            )}
            {pending && (
              <article className="bubble advisor">
                <p className="who">Advisor</p>
                <p className="thinking">
                  Thinking
                  <span className="thinking-dots" aria-hidden="true">
                    <span>.</span>
                    <span>.</span>
                    <span>.</span>
                  </span>
                </p>
              </article>
            )}
          </section>

          <aside className="rail">
            <div className="panel">
              <div className="panel-head">
                <h2>Disclosure notes</h2>
                <span>
                  {draft?.organizing ? "Updating notes…" : `${completed} of ${sections.length} completed`}
                </span>
              </div>
              <button
                type="button"
                className="expand-all"
                disabled={sections.length === 0}
                onClick={() =>
                  setOpenSections(Object.fromEntries(sections.map((section) => [section.id, !allOpen])))
                }
              >
                {allOpen ? "Collapse all" : "Expand all"}
              </button>
              <ol className="ready">
                {sections.map((section) => {
                  const open = isOpen(section.id);
                  const notes = section.fieldIds
                    .map((id) => items.find((field) => field.id === id))
                    .filter((field): field is FieldView => Boolean(field));
                  return (
                    <li
                      key={section.id}
                      className={`note-section status-${section.status.toLowerCase()}`}
                    >
                      <button
                        type="button"
                        className="section-toggle"
                        aria-expanded={open}
                        onClick={() => setOpenSections((curr) => ({ ...curr, [section.id]: !open }))}
                      >
                        <div>
                          <strong>{section.title}</strong>
                          <em>{section.statusLabel}</em>
                        </div>
                      </button>
                      {open && (
                        <ul className="section-notes">
                          {notes.map((note) => (
                            <li key={note.id} className={note.text?.trim() ? "filled" : "empty"}>
                              <h3>{note.label}</h3>
                              <p>{noteText(note)}</p>
                            </li>
                          ))}
                        </ul>
                      )}
                    </li>
                  );
                })}
              </ol>
              <div className="panel-actions">
                <button
                  onClick={() => {
                    void copyBrief().catch((err: unknown) =>
                      setBootError(err instanceof Error ? err.message : "Could not copy notes."),
                    );
                  }}
                  disabled={pending || !draft || copyState === "preparing"}
                >
                  {copyState === "preparing" ? "Preparing notes…" : copyState === "copied" ? "Copied" : "Copy preparation notes"}
                </button>
                <button
                  className="ghost"
                  onClick={() => {
                    void downloadWord().catch((err: unknown) =>
                      setBootError(err instanceof Error ? err.message : "Could not download the Word file."),
                    );
                  }}
                  disabled={pending || !draft || wordState === "preparing"}
                >
                  {wordState === "preparing" ? "Preparing Word file…" : "Download Word"}
                </button>
                <button className="ghost" onClick={() => void reset()} disabled={pending}>
                  Start over
                </button>
              </div>
            </div>
            {contact && (
              <div className="panel contact-panel">
                <h2 className="contact-office">{contact.officeName}</h2>
                <div className="contact-person">
                  <p>Jeff Blackwood - Senior Director</p>
                  <p>
                    <a href="mailto:jblackwood@umkc.edu">jblackwood@umkc.edu</a>
                  </p>
                </div>
                <div className="contact-person">
                  <p>Eric Anderson - Director</p>
                  {contact.email && (
                    <p>
                      <a href={`mailto:${contact.email}`}>{contact.email}</a>
                    </p>
                  )}
                </div>
                {contact.url && (
                  <p>
                    <a href={contact.url} target="_blank" rel="noreferrer">
                      Invention Disclosure Process
                    </a>
                  </p>
                )}
              </div>
            )}
          </aside>

          <form
            className="composer"
            onSubmit={(event) => {
              event.preventDefault();
              void ask(compose);
            }}
          >
            <div className="composer-box">
              <textarea
                id="ask"
                ref={boxRef}
                rows={3}
                value={compose}
                disabled={pending || !draft}
                aria-label="Your answer"
                placeholder="Ask a question or tell me about your work."
                onChange={(event) => setCompose(event.target.value)}
                onKeyDown={(event) => {
                  if (event.key === "Enter" && !event.shiftKey) {
                    event.preventDefault();
                    void ask(compose);
                  }
                }}
              />
              <button type="submit" className="composer-send" disabled={pending || !draft}>
                {pending ? "Drafting" : "Send"}
              </button>
            </div>
            <p className="fine">
              Use this conversation to prepare for a meeting with the office. Early, informal questions are welcome. This does not count as a disclosure submission.
            </p>
          </form>
        </main>
      )}
    </div>
  );
}

function sourceTypeRank(type: string): number {
  const kind = (type || "").toUpperCase();
  if (kind === "HTML") return 0;
  if (kind === "DOCX") return 1;
  if (kind === "PDF") return 2;
  return 3;
}

function displaySourceTitle(title: string): string {
  return title.replace(/\s+v0*426$/i, "").trim();
}

function sortSources(sources: SourceView[]): SourceView[] {
  return [...sources].sort((a, b) => {
    const byType = sourceTypeRank(a.type) - sourceTypeRank(b.type);
    if (byType !== 0) return byType;
    return displaySourceTitle(a.title).localeCompare(displaySourceTitle(b.title), undefined, { numeric: true, sensitivity: "base" });
  });
}

function SourcePage() {
  const [sources, setSources] = useState<SourceView[]>([]);
  const [listError, setListError] = useState("");
  const [open, setOpen] = useState<SourceView | null>(null);
  const [view, setView] = useState<"doc" | "text">("doc");
  const [text, setText] = useState("");
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState("");
  const docxRef = useRef<HTMLDivElement>(null);

  const kind = open ? sourceFileKind(open) : null;
  const pageUrl = open ? officialPage(open.url) : null;
  const fileUrl = open ? sourceFileUrl(open.id) : "";
  const hasDoc = Boolean(kind);
  const listed = useMemo(() => sortSources(sources), [sources]);

  useEffect(() => {
    listSources()
      .then(setSources)
      .catch((err: unknown) => setListError(err instanceof Error ? err.message : "Could not load sources."));
  }, []);

  useEffect(() => {
    if (!open) return;
    const nextKind = sourceFileKind(open);
    setView(nextKind ? "doc" : "text");
    setText("");
    setError("");
    setLoading(!nextKind || nextKind === "docx");
  }, [open]);

  useEffect(() => {
    if (!open || view !== "text") return;
    let cancelled = false;
    setLoading(true);
    setError("");
    getSourceText(open.id)
      .then((next) => {
        if (!cancelled) setText(next);
      })
      .catch((err: unknown) => {
        if (!cancelled) setError(err instanceof Error ? err.message : "Could not load this source.");
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [open, view]);

  useEffect(() => {
    if (!open || kind !== "docx" || view !== "doc" || !docxRef.current) return;
    const host = docxRef.current;
    let cancelled = false;
    host.innerHTML = "";
    setLoading(true);
    setError("");
    fetch(fileUrl)
      .then((response) => {
        if (!response.ok) throw new Error("Word file not found.");
        return response.arrayBuffer();
      })
      .then((buffer) => {
        if (cancelled || !docxRef.current) return;
        return renderAsync(buffer, docxRef.current, undefined, {
          className: "docx-preview",
          inWrapper: true,
          breakPages: true,
          ignoreWidth: false,
          ignoreHeight: false,
        });
      })
      .catch((err: unknown) => {
        if (!cancelled) setError(err instanceof Error ? err.message : "Could not open this Word file.");
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
      host.innerHTML = "";
    };
  }, [open, kind, view, fileUrl]);

  useEffect(() => {
    if (!open) return;
    const onKey = (event: KeyboardEvent) => {
      if (event.key === "Escape") setOpen(null);
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [open]);

  return (
    <section className="page">
      <header className="page-intro">
        <h2>Source library</h2>
        <p>
          These are the UMKC and UM System documents the advisor uses for process questions. Open a source to read it.
          PDFs and Word files keep their original layout; official web pages are linked when available.
        </p>
      </header>
      {listError && <p className="fine">{listError}</p>}
      <div className="source-list">
        {listed.map((source) => (
          <button key={source.id} type="button" className="source-card" onClick={() => setOpen(source)}>
            <div>
              <h3>{displaySourceTitle(source.title)}</h3>
              <p className="eyebrow">{source.type}</p>
            </div>
            <span className="source-open" aria-hidden>
              ›
            </span>
          </button>
        ))}
      </div>

      {open && (
        <div className="source-modal" role="dialog" aria-modal="true" aria-labelledby="source-modal-title">
          <button type="button" className="source-backdrop" aria-label="Close source" onClick={() => setOpen(null)} />
          <div className={`source-sheet${hasDoc && view === "doc" ? " has-pdf" : ""}`}>
            <header className="source-sheet-head">
              <div>
                <p className="eyebrow">{open.type}</p>
                <h2 id="source-modal-title">{displaySourceTitle(open.title)}</h2>
              </div>
              <div className="source-sheet-actions">
                {hasDoc && (
                  <>
                    <button type="button" className={view === "doc" ? "on" : ""} onClick={() => setView("doc")}>
                      {kind === "pdf" ? "PDF" : "Word"}
                    </button>
                    <button type="button" className={view === "text" ? "on" : ""} onClick={() => setView("text")}>
                      Text extract
                    </button>
                  </>
                )}
                {kind === "pdf" && (
                  <a className="source-link" href={fileUrl} download={open.fileName ?? undefined}>
                    Download PDF
                  </a>
                )}
                {kind === "docx" && (
                  <a className="source-link" href={fileUrl} download={open.fileName ?? undefined}>
                    Download Word
                  </a>
                )}
                {pageUrl && (
                  <a className="source-link" href={pageUrl} target="_blank" rel="noreferrer">
                    Open official page
                  </a>
                )}
                <button type="button" className="ghost" onClick={() => setOpen(null)}>
                  Close
                </button>
              </div>
            </header>
            <div className="source-body">
              {view === "doc" && kind === "pdf" ? (
                <iframe title={displaySourceTitle(open.title)} src={fileUrl} className="source-pdf" />
              ) : view === "doc" && kind === "docx" ? (
                <>
                  {loading && <p className="fine source-loading">Opening Word file…</p>}
                  {error && <p className="fine">{error}</p>}
                  <div ref={docxRef} className="source-docx" />
                </>
              ) : (
                <>
                  {loading && <p className="fine">Loading source…</p>}
                  {error && <p className="fine">{error}</p>}
                  {!loading && !error && !text && <p className="fine">No indexed text for this source yet.</p>}
                  {!loading && !error && text && <pre>{text}</pre>}
                </>
              )}
            </div>
          </div>
        </div>
      )}
    </section>
  );
}
