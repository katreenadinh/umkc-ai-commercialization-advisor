import type { CoachState } from "../../hooks/useCoach";
import { fieldDepth, fieldStatus } from "../../utils/fields";

export function NotesPanel({ coach }: { coach: CoachState }) {
  const { items, started, pending, draft, copied } = coach;
  return (
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
              onClick={() => void coach.ask(`Let's go back to ${item.label.toLowerCase()}.`)}
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
        <button onClick={coach.copyNotes} disabled={pending || !draft}>
          {copied ? "Copied" : "Copy preparation notes"}
        </button>
        <button className="ghost" onClick={coach.downloadNotes} disabled={pending || !draft}>
          Download Word
        </button>
        <button className="ghost" onClick={() => void coach.reset()} disabled={pending}>
          Start over
        </button>
      </div>
      <p className="fine">
        Eight notes for a first conversation, not the whole UM form. Skip freely. Early disclosure is encouraged, and these notes are not a confidential submission.
      </p>
    </div>
  );
}
