import { useCallback, useState } from "react";
import { useSources } from "../../hooks/useSources";
import type { SourceView } from "../../types";
import { SourceViewer } from "./SourceViewer";

export function SourcePage() {
  const { sorted: listed, error: listError } = useSources();
  const [open, setOpen] = useState<SourceView | null>(null);
  const close = useCallback(() => setOpen(null), []);

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
              <h3>{source.title}</h3>
              <p className="eyebrow">{source.type}</p>
            </div>
            <span className="source-open" aria-hidden>
              ›
            </span>
          </button>
        ))}
      </div>

      {open && <SourceViewer source={open} onClose={close} />}
    </section>
  );
}
