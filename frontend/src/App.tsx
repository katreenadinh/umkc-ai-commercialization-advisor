import { useEffect, useMemo, useRef, useState } from "react";
import { renderAsync } from "docx-preview";
import {
  createDraft,
  forgetDraftId,
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
import { SCENARIOS } from "./scenarios";

type Page = "coach" | "scenarios" | "sources" | "deploy";

interface Msg {
  id: string;
  role: "user" | "advisor";
  text?: string;
  paragraphs?: string[];
}

function fieldDepth(field: FieldView): "empty" | "mentioned" | "usable" {
  if (field.mark === "NOT_APPLICABLE") return "usable";
  if (field.mark === "NOT_YET_KNOWN" || field.mark === "SKIPPED") return "mentioned";
  const text = field.text?.trim() || "";
  if (!text) return "empty";
  return text.length > 40 ? "usable" : "mentioned";
}

function fieldStatus(field: FieldView): string {
  if (field.mark === "NOT_APPLICABLE") return "Not applicable";
  if (field.mark === "NOT_YET_KNOWN") return "Not yet known";
  if (field.mark === "SKIPPED") return "Skipped";
  const depth = fieldDepth(field);
  if (depth === "usable") return "Enough to discuss";
  if (depth === "mentioned") return "Mentioned";
  return "Not yet";
}

function paragraphsFrom(text: string): string[] {
  return text
    .replace(/\s*\[S\d+]/g, "")
    .split(/\n\n+/)
    .map((part) => part.trim())
    .filter(Boolean);
}

const PROCESS_CHIPS = [
  "What is a public disclosure?",
  "How does licensing usually work?",
  "What is prior art?",
  "When should I contact the office?",
  "Who usually owns university inventions?",
  "What belongs in an invention disclosure?",
];

const FIELD_CHIPS: Record<string, string[]> = {
  summary_general: ["It's still an early-stage idea.", "I can give a one-sentence overview."],
  problem_solved: ["It addresses a bottleneck we see in the lab.", "I'm still figuring out the exact problem."],
  technical_description: ["I can walk through how it works at a high level.", "The mechanism is still a bit fuzzy."],
  difference_from_others: ["I'm not sure yet how it compares.", "It does something existing tools don't."],
  public_disclosures_list: ["Nothing has been shared publicly yet.", "I want to present this finding."],
  evidence_testing: ["We haven't tested it yet.", "We have some early lab results."],
  potential_inventors: ["It was just me so far.", "A student helped with the experiments."],
  funding: ["There was no outside funding.", "It was supported by a grant."],
  existing_disclosure_ref: ["I don't have the disclosure number handy.", "I can describe which one it is."],
  what_changed: ["The main change is a new method.", "I'm not sure what counts as a change."],
};

function hashSeed(value: string): number {
  let hash = 0;
  for (let i = 0; i < value.length; i += 1) hash = (hash * 31 + value.charCodeAt(i)) | 0;
  return Math.abs(hash);
}

function pickRotated(pool: string[], seed: string, count: number, skip: string[] = []): string[] {
  const filtered = pool.filter((item) => !skip.some((s) => s.toLowerCase() === item.toLowerCase()));
  if (filtered.length === 0) return [];
  const start = hashSeed(seed) % filtered.length;
  const out: string[] = [];
  for (let i = 0; i < Math.min(count, filtered.length); i += 1) {
    out.push(filtered[(start + i * 2) % filtered.length]);
  }
  return [...new Set(out)];
}

function suggestionChips(
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
  const [copied, setCopied] = useState(false);
  const [pending, setPending] = useState(false);
  const [bootError, setBootError] = useState("");
  const [contact, setContact] = useState<ContactInfo | null>(null);
  const threadRef = useRef<HTMLDivElement>(null);
  const boxRef = useRef<HTMLTextAreaElement>(null);
  const draftIdRef = useRef<string | null>(null);
  const pendingRef = useRef(false);
  const askedFieldRef = useRef<string[]>([]);

  useEffect(() => {
    pendingRef.current = pending;
  }, [pending]);

  useEffect(() => {
    draftIdRef.current = draft?.id ?? null;
  }, [draft]);

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
  const started = items.filter((item) => fieldDepth(item) !== "empty").length;
  const usable = items.filter((item) => fieldDepth(item) === "usable").length;
  const lastAdvisorId = [...messages].reverse().find((msg) => msg.role === "advisor")?.id;
  const currentAsk = askedFieldRef.current[0];
  const currentField = items.find((field) => field.id === currentAsk);
  const placeholder = currentField?.label
    ? `Tell me about ${currentField.label.toLowerCase()}, or skip this note.`
    : "Ask a question, or go back to any note on the right.";

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
    return next;
  }

  async function startCoach() {
    const created = await createDraft();
    askedFieldRef.current = created.opening.askedFieldIds;
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
    const markdown = await generateSummary(id);
    await navigator.clipboard.writeText(markdown);
    setCopied(true);
    window.setTimeout(() => setCopied(false), 1800);
  }

  const lastUserText = [...messages].reverse().find((msg) => msg.role === "user")?.text || "";
  const lastAdvisorText = [...messages].reverse().find((msg) => msg.role === "advisor")?.paragraphs?.join(" ") || "";
  const suggestions = useMemo(
    () => suggestionChips(currentField, lastUserText, lastAdvisorText, messages.length),
    [currentField, lastUserText, lastAdvisorText, messages.length],
  );

  return (
    <div className="app">
      <header className="top">
        <div className="brand">
          <div>
            <p className="eyebrow">Prototype for Hackaroo</p>
            <h1>Commercialization Advisor</h1>
          </div>
        </div>
        <nav>
          {(
            [
              ["coach", "Disclosure coach"],
              ["scenarios", "Faculty scenarios"],
              ["sources", "Source library"],
              ["deploy", "For the future"],
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

      {page === "scenarios" ? (
        <ScenarioPage
          onPick={(text) => {
            void (async () => {
              try {
                forgetDraftId();
                await startCoach();
                pendingRef.current = false;
                await ask(text);
              } catch (err) {
                setBootError(err instanceof Error ? err.message : "Could not start that scenario.");
                pendingRef.current = false;
                setPending(false);
              }
            })();
          }}
        />
      ) : page === "sources" ? (
        <SourcePage />
      ) : page === "deploy" ? (
        <DeployPage />
      ) : (
        <main className="workspace">
          <section className="thread" ref={threadRef} aria-live="polite">
            <p className="coach-flag">
              First conversation · {usable} of {items.length || 0} enough to discuss
              {draft?.progress ? ` · ${draft.progress.percent}% prepared` : ""}
            </p>
            {bootError && (
              <article className="bubble advisor">
                <p className="who">Coach</p>
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
                  <p className="who">Coach</p>
                  {(msg.paragraphs || []).map((paragraph, index) => (
                    <p key={`${msg.id}-${index}`}>{paragraph}</p>
                  ))}
                  {msg.id === lastAdvisorId && !pending && (
                    <div className="follow">
                      <p className="who">You could say</p>
                      {suggestions.map((question) => (
                        <button
                          key={question}
                          onClick={() => (question.startsWith("Skip") ? skipCurrent() : ask(question))}
                        >
                          {question}
                        </button>
                      ))}
                    </div>
                  )}
                </article>
              ),
            )}
            {pending && (
              <article className="bubble advisor">
                <p className="who">Coach</p>
                <p>Listening…</p>
              </article>
            )}
          </section>

          <aside className="rail">
            <div className="panel">
              <div className="panel-head">
                <h2>Disclosure notes</h2>
                <span>
                  {started} of {items.length} touched
                </span>
              </div>
              <ol className="ready">
                {items.map((item) => (
                  <li key={item.id} className={fieldDepth(item)}>
                    <button
                      type="button"
                      className="ready-jump"
                      disabled={pending}
                      onClick={() => {
                        setPage("coach");
                        void ask(`Let's go back to ${item.label.toLowerCase()}.`);
                      }}
                    >
                      <div>
                        <strong>{item.label}</strong>
                        <em>{fieldStatus(item)}</em>
                      </div>
                      <p>{item.text?.trim() || item.whyItHelps || item.description}</p>
                    </button>
                  </li>
                ))}
              </ol>
              <div className="panel-actions">
                <button
                  onClick={() => {
                    void copyBrief().catch((err: unknown) =>
                      setBootError(err instanceof Error ? err.message : "Could not copy notes."),
                    );
                  }}
                  disabled={pending || !draft}
                >
                  {copied ? "Copied" : "Copy preparation notes"}
                </button>
                <button className="ghost" onClick={() => void reset()} disabled={pending}>
                  Start over
                </button>
              </div>
              <p className="fine">
                Eight notes for a first conversation, not the whole UM form. Skip freely. Early disclosure is encouraged, and these notes are not a confidential submission.
              </p>
            </div>
            {contact && (
              <div className="panel contact-panel">
                <h2 className="contact-office">{contact.officeName}</h2>
                <p className="contact-person">Eric Anderson - Director</p>
                {contact.email && (
                  <p>
                    <a href={`mailto:${contact.email}`}>{contact.email}</a>
                  </p>
                )}
                {contact.url && (
                  <p>
                    <a href={contact.url} target="_blank" rel="noreferrer">
                      Invention Disclosure Process
                    </a>
                  </p>
                )}
                <p className="fine">Early, informal questions are welcome. This chat is not a submission.</p>
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
            <textarea
              id="ask"
              ref={boxRef}
              rows={3}
              value={compose}
              disabled={pending || !draft}
              aria-label="Your answer"
              placeholder={placeholder}
              onChange={(event) => setCompose(event.target.value)}
              onKeyDown={(event) => {
                if (event.key === "Enter" && !event.shiftKey) {
                  event.preventDefault();
                  void ask(compose);
                }
              }}
            />
            <div className="composer-bar">
              <p>Keep enabling detail, sequences, and unpublished figures out of this student prototype.</p>
              <button type="submit" disabled={pending || !draft}>
                {pending ? "Drafting" : "Send"}
              </button>
            </div>
          </form>
        </main>
      )}
    </div>
  );
}

function ScenarioPage({ onPick }: { onPick: (text: string) => void }) {
  return (
    <section className="page scenarios-page">
      <header className="page-intro">
        <p className="eyebrow">Demonstration set</p>
        <h2>Realistic faculty situations</h2>
        <p>
          Each one is handled from the mentor materials. The useful outcome is a clearer description and a correct handoff, not a yes on patentability.
        </p>
      </header>
      <div className="scenario-cards">
        {SCENARIOS.map((scenario) => (
          <article key={scenario.id} className="card">
            <p className="eyebrow">{scenario.audience}</p>
            <h3>{scenario.label}</h3>
            <p>{scenario.shows}</p>
            <blockquote>{scenario.prompt}</blockquote>
            <button onClick={() => onPick(scenario.prompt)}>Run this scenario</button>
          </article>
        ))}
      </div>
    </section>
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
        <p className="eyebrow">Approved for this prototype</p>
        <h2>What the answers are allowed to use</h2>
        <p>
          Responses cite these documents. Click a source to read it. PDFs and Word files open with their original layout; web pages are linked when a public URL exists.
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

function DeployPage() {
  return (
    <section className="page deploy deploy-page">
      <header className="page-intro">
        <p className="eyebrow">Recommendation, not a production system</p>
        <h2>What a university-supported version would need</h2>
        <p>
          This prototype keeps eight notes for a first conversation and answers process questions from the approved UMKC and UM documents. A university version would use the same pattern inside the UM System ChatGPT environment, with staff still making every legal and commercialization decision.
        </p>
      </header>
      <div className="deploy-grid">
        <article>
          <h3>Keep</h3>
          <ul>
            <li>Eight notes for a first conversation, not the whole UM form. People can skip a note or come back to one. The conversation is not a step-by-step form.</li>
            <li>Plain-language answers to process questions, without citations in the chat. The source library is where the documents are read.</li>
            <li>A caution when someone is about to present, publish, or otherwise share the work, without asking again for a date, venue, or title they already gave.</li>
            <li>Informal answers left as they were given. Funding from the school or the government is enough. No award number or principal investigator is required.</li>
            <li>Notes that can be copied. No Word download of the notes, and no button that submits a disclosure.</li>
            <li>The coach keeps listening after the notes are filled.</li>
            <li>Eric Anderson, Director of the Office of Technology Commercialization, shown in the coach at ericwa@umkc.edu.</li>
          </ul>
        </article>
        <article>
          <h3>Do not add</h3>
          <ul>
            <li>A completed UM disclosure, claim drafting, or a button that submits the form.</li>
            <li>Royalty quotes, inventorship lists, ownership rulings, patentability scores, or freedom-to-operate opinions.</li>
            <li>Open-web browsing as a source of policy.</li>
            <li>Training on faculty conversations. The market guide already warns that some AI tools reuse what you paste.</li>
          </ul>
        </article>
        <article>
          <h3>Corpus and instructions</h3>
          <ul>
            <li>UMKC Impact & Translation Series whitepapers, the disclosure form’s questions and privilege notice, CR&R 100.020, and the UMKC startup licensing guide.</li>
            <li>Peer documents such as the KU license guide only if they are labeled as not UM policy.</li>
            <li>A system instruction that early disclosure is encouraged, especially before publication, and that uncertainty is an acceptable answer.</li>
            <li>A standing refusal for export-control classification, conflicts, and regulatory pathway selection.</li>
          </ul>
        </article>
        <article>
          <h3>Technical shape</h3>
          <ul>
            <li>Retrieval over the approved corpus inside the UM tenant, so answers quote current policy rather than model memory.</li>
            <li>No retention of invention details outside that tenant. The real form is attorney-client privileged.</li>
            <li>Logs that store the question type and the handoff, not the scientific content, unless counsel approves a confidential store.</li>
            <li>A staff review loop when whitepapers or the form change, plus a small evaluation set like the scenarios in this prototype.</li>
            <li>Human contact in the interface: ericwa@umkc.edu (Eric Anderson, Office of Technology Commercialization).</li>
          </ul>
        </article>
      </div>
    </section>
  );
}
