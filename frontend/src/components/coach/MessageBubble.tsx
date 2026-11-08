import type { Msg } from "../../types";

interface Props {
  msg: Msg;
  /** Suggestion chips to show under this bubble; omit to show none. */
  suggestions?: string[];
  onSuggestion?: (question: string) => void;
}

export function MessageBubble({ msg, suggestions, onSuggestion }: Props) {
  if (msg.role === "user") {
    return (
      <article className="bubble user">
        <p>{msg.text}</p>
      </article>
    );
  }
  return (
    <article className="bubble advisor">
      <p className="who">Coach</p>
      {(msg.paragraphs || []).map((paragraph, index) => (
        <p key={`${msg.id}-${index}`}>{paragraph}</p>
      ))}
      {suggestions && (
        <div className="follow">
          <p className="who">You could say</p>
          {suggestions.map((question) => (
            <button key={question} onClick={() => onSuggestion?.(question)}>
              {question}
            </button>
          ))}
        </div>
      )}
    </article>
  );
}
