import { useEffect, useRef } from "react";
import type { CoachState } from "../../hooks/useCoach";
import { MessageBubble } from "./MessageBubble";

export function MessageThread({ coach }: { coach: CoachState }) {
  const { messages, pending, bootError, usable, items, draft, lastAdvisorId, suggestions } = coach;
  const threadRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    threadRef.current?.scrollTo({ top: threadRef.current.scrollHeight, behavior: "smooth" });
  }, [messages, pending]);

  return (
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
      {messages.map((msg) => (
        <MessageBubble
          key={msg.id}
          msg={msg}
          suggestions={msg.id === lastAdvisorId && !pending ? suggestions : undefined}
          onSuggestion={(question) => (question.startsWith("Skip") ? coach.skipCurrent() : coach.ask(question))}
        />
      ))}
      {pending && (
        <article className="bubble advisor">
          <p className="who">Coach</p>
          <p>Listening…</p>
        </article>
      )}
    </section>
  );
}
