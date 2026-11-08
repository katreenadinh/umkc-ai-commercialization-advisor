import { useEffect, useRef, useState } from "react";
import { renderAsync } from "docx-preview";
import { getSourceText, sourceFileUrl } from "../../api";
import type { SourceView } from "../../types";
import { errorMessage } from "../../utils/errors";
import { officialPage, sourceFileKind } from "../../utils/sources";

interface Props {
  source: SourceView;
  onClose: () => void;
}

/** Modal reader for one source: PDF iframe, Word (docx-preview) render, or indexed text extract. */
export function SourceViewer({ source, onClose }: Props) {
  const [view, setView] = useState<"doc" | "text">(() => (sourceFileKind(source) ? "doc" : "text"));
  const [text, setText] = useState("");
  const [loading, setLoading] = useState(() => sourceFileKind(source) !== "pdf");
  const [error, setError] = useState("");
  const docxRef = useRef<HTMLDivElement>(null);

  const kind = sourceFileKind(source);
  const pageUrl = officialPage(source.url);
  const fileUrl = sourceFileUrl(source.id);
  const hasDoc = Boolean(kind);

  useEffect(() => {
    const nextKind = sourceFileKind(source);
    setView(nextKind ? "doc" : "text");
    setText("");
    setError("");
    setLoading(!nextKind || nextKind === "docx");
  }, [source]);

  useEffect(() => {
    if (view !== "text") return;
    let cancelled = false;
    setLoading(true);
    setError("");
    getSourceText(source.id)
      .then((next) => {
        if (!cancelled) setText(next);
      })
      .catch((err: unknown) => {
        if (!cancelled) setError(errorMessage(err, "Could not load this source."));
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [source, view]);

  useEffect(() => {
    if (kind !== "docx" || view !== "doc" || !docxRef.current) return;
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
        if (!cancelled) setError(errorMessage(err, "Could not open this Word file."));
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
      host.innerHTML = "";
    };
  }, [source, kind, view, fileUrl]);

  useEffect(() => {
    const onKey = (event: KeyboardEvent) => {
      if (event.key === "Escape") onClose();
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [onClose]);

  return (
    <div className="source-modal" role="dialog" aria-modal="true" aria-labelledby="source-modal-title">
      <button type="button" className="source-backdrop" aria-label="Close source" onClick={onClose} />
      <div className={`source-sheet${hasDoc && view === "doc" ? " has-pdf" : ""}`}>
        <header className="source-sheet-head">
          <div>
            <p className="eyebrow">{source.type}</p>
            <h2 id="source-modal-title">{source.title}</h2>
            <p>{pageUrl || source.fileName}</p>
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
              <a className="source-link" href={fileUrl} download={source.fileName ?? undefined}>
                Download PDF
              </a>
            )}
            {kind === "docx" && (
              <a className="source-link" href={fileUrl} download={source.fileName ?? undefined}>
                Download Word
              </a>
            )}
            {pageUrl && (
              <a className="source-link" href={pageUrl} target="_blank" rel="noreferrer">
                Open official page
              </a>
            )}
            <button type="button" className="ghost" onClick={onClose}>
              Close
            </button>
          </div>
        </header>
        <div className="source-body">
          {view === "doc" && kind === "pdf" ? (
            <iframe title={source.title} src={fileUrl} className="source-pdf" />
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
  );
}
