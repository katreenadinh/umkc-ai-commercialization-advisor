import type { CoachState } from "../../hooks/useCoach";

export function Composer({ coach }: { coach: CoachState }) {
  const { compose, setCompose, pending, draft, placeholder, boxRef } = coach;
  return (
    <form
      className="composer"
      onSubmit={(event) => {
        event.preventDefault();
        void coach.ask(compose);
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
            void coach.ask(compose);
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
  );
}
